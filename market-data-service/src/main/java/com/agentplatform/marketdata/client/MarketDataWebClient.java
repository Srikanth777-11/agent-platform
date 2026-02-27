package com.agentplatform.marketdata.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentplatform.marketdata.model.MarketDataQuote;
import com.agentplatform.marketdata.replay.ReplayCandleDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import com.agentplatform.marketdata.model.AlphaVantageTimeSeriesResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class MarketDataWebClient {

    private static final Logger log = LoggerFactory.getLogger(MarketDataWebClient.class);

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter AO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    // ── Active: Alpha Vantage (fallback until Angel One keys are set) ─────────
    @Value("${alpha-vantage.api-key:demo}")
    private String alphaVantageApiKey;

    // ── Angel One SmartAPI ─────────────────────────────────────────────────────
    @Value("${angel-one.api-key:}")
    private String angelOneApiKey;

    @Value("#{${angel-one.symbol-tokens:{}}}")
    private Map<String, String> symbolTokens;

    @Autowired(required = false)
    private AngelOneAuthService angelOneAuthService;

    public MarketDataWebClient(WebClient marketDataWebClient, ObjectMapper objectMapper) {
        this.webClient    = marketDataWebClient;
        this.objectMapper = objectMapper;
    }

    public Mono<MarketDataQuote> fetchQuote(String symbol) {
        // Use Angel One if configured, fall back to Alpha Vantage
        if (angelOneApiKey != null && !angelOneApiKey.isBlank() && angelOneAuthService != null) {
            log.info("Fetching market data. provider=AngelOne symbol={}", symbol);
            return fetchAngelOne(symbol);
        }
        log.info("Fetching market data. provider=AlphaVantage symbol={}", symbol);
        return fetchAlphaVantage(symbol);
    }

    // ── Angel One SmartAPI implementation ────────────────────────────────────

    private Mono<MarketDataQuote> fetchAngelOne(String symbol) {
        String token = symbolTokens.getOrDefault(symbol, "26000");
        LocalDateTime now  = LocalDateTime.now(IST);
        // Fetch last 2 trading days of 5-min candles for enough indicator history
        LocalDateTime from = now.toLocalDate().minusDays(3).atTime(9, 15);
        LocalDateTime to   = now.getHour() >= 15 && now.getMinute() >= 30
                             ? now.toLocalDate().atTime(15, 30)
                             : now;

        Map<String, String> body = Map.of(
            "exchange",    "NSE",
            "symboltoken", token,
            "interval",    "FIVE_MINUTE",
            "fromdate",    from.format(AO_FMT),
            "todate",      to.format(AO_FMT)
        );

        return angelOneAuthService.getToken()
            .flatMap(jwt -> webClient.post()
                .uri("/rest/secure/angelbroking/historical/v1/getCandleData")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + jwt)
                .header("X-PrivateKey",  angelOneApiKey)
                .header("X-UserType",    "USER")
                .header("X-SourceID",    "WEB")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class))
            .map(json -> parseAngelOneResponse(symbol, json))
            // On 401, refresh token and retry once
            .onErrorResume(e -> {
                if (e.getMessage() != null && e.getMessage().contains("401")) {
                    log.warn("[AngelOne] 401 received, refreshing token and retrying. symbol={}", symbol);
                    return angelOneAuthService.refreshToken()
                        .flatMap(jwt -> webClient.post()
                            .uri("/rest/secure/angelbroking/historical/v1/getCandleData")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", "Bearer " + jwt)
                            .header("X-PrivateKey",  angelOneApiKey)
                            .header("X-UserType",    "USER")
                            .header("X-SourceID",    "WEB")
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(String.class))
                        .map(json -> parseAngelOneResponse(symbol, json));
                }
                return Mono.error(e);
            })
            .doOnSuccess(q -> log.info("Market data fetched. provider=AngelOne symbol={} latestClose={}", q.symbol(), q.latestClose()))
            .doOnError(e -> log.error("Angel One fetch failed. symbol={}", symbol, e));
    }

    private MarketDataQuote parseAngelOneResponse(String symbol, String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.path("status").asBoolean(false)) {
                throw new RuntimeException("Angel One error: " + root.path("message").asText());
            }
            JsonNode data = root.path("data");
            if (data.isMissingNode() || !data.isArray() || data.size() == 0) {
                throw new RuntimeException("No candle data from Angel One for symbol: " + symbol);
            }
            // Each entry: [datetime, open, high, low, close, volume]
            JsonNode latest = data.get(data.size() - 1); // last = most recent
            double close  = latest.get(4).asDouble();
            double open   = latest.get(1).asDouble();
            double high   = latest.get(2).asDouble();
            double low    = latest.get(3).asDouble();
            long   volume = latest.get(5).asLong();

            List<Double> closingPrices = new ArrayList<>();
            data.forEach(bar -> closingPrices.add(bar.get(4).asDouble()));
            // Reverse so newest is first (consistent with Alpha Vantage ordering)
            java.util.Collections.reverse(closingPrices);

            return new MarketDataQuote(symbol, close, open, high, low, volume,
                closingPrices.subList(0, Math.min(50, closingPrices.size())), Instant.now());

        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Angel One response for symbol: " + symbol, e);
        }
    }

    // ── Phase-32: Angel One Historical Candle Fetch (configurable date range) ──

    /**
     * Fetches historical OHLCV candles from Angel One SmartAPI for a configurable
     * date range. Automatically chunks the request into 30-day windows to avoid
     * API timeouts. Returns candles in chronological order.
     *
     * @param symbol   ticker symbol (e.g. "NIFTY.NSE")
     * @param fromDate start date (inclusive)
     * @param toDate   end date   (inclusive)
     * @param interval Angel One interval string (e.g. "FIVE_MINUTE")
     * @return all candles across the requested range, oldest first
     */
    public Mono<List<ReplayCandleDTO>> fetchHistoricalCandles(
            String symbol, LocalDate fromDate, LocalDate toDate, String interval) {

        if (angelOneApiKey == null || angelOneApiKey.isBlank() || angelOneAuthService == null) {
            return Mono.error(new IllegalStateException(
                "Angel One API key not configured — cannot fetch historical candles"));
        }

        String token = symbolTokens.getOrDefault(symbol, "26000");
        List<LocalDate[]> chunks = buildChunks(fromDate, toDate, 30);
        log.info("[HistoricalFetch] Fetching {} 30-day chunks. symbol={} from={} to={}",
                 chunks.size(), symbol, fromDate, toDate);

        // Fetch each chunk sequentially to avoid overwhelming the API
        return Mono.just(new ArrayList<ReplayCandleDTO>())
            .flatMap(accumulated -> {
                Mono<List<ReplayCandleDTO>> chain = Mono.just(accumulated);
                for (LocalDate[] chunk : chunks) {
                    final LocalDate chunkFrom = chunk[0];
                    final LocalDate chunkTo   = chunk[1];
                    chain = chain.flatMap(acc ->
                        fetchAngelOneChunk(symbol, token, chunkFrom, chunkTo, interval)
                            .doOnSuccess(candles -> {
                                acc.addAll(candles);
                                log.info("[HistoricalFetch] Chunk done. symbol={} from={} to={} candles={}",
                                         symbol, chunkFrom, chunkTo, candles.size());
                            })
                            .thenReturn(acc)
                    );
                }
                return chain;
            })
            .doOnSuccess(all -> log.info("[HistoricalFetch] Complete. symbol={} total={}", symbol, all.size()))
            .doOnError(e -> log.error("[HistoricalFetch] Failed. symbol={}", symbol, e));
    }

    private Mono<List<ReplayCandleDTO>> fetchAngelOneChunk(
            String symbol, String token,
            LocalDate from, LocalDate to, String interval) {

        LocalDateTime fromDt = from.atTime(9, 15);
        LocalDateTime toDt   = to.atTime(15, 30);

        Map<String, String> body = Map.of(
            "exchange",    "NSE",
            "symboltoken", token,
            "interval",    interval,
            "fromdate",    fromDt.format(AO_FMT),
            "todate",      toDt.format(AO_FMT)
        );

        return angelOneAuthService.getToken()
            .flatMap(jwt -> webClient.post()
                .uri("/rest/secure/angelbroking/historical/v1/getCandleData")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + jwt)
                .header("X-PrivateKey",  angelOneApiKey)
                .header("X-UserType",    "USER")
                .header("X-SourceID",    "WEB")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class))
            .map(json -> parseAngelOneHistoricalResponse(symbol, json))
            .onErrorResume(e -> {
                log.warn("[HistoricalFetch] Chunk error (skipping). symbol={} from={} err={}",
                         symbol, from, e.getMessage());
                return Mono.just(List.of());
            });
    }

    private List<ReplayCandleDTO> parseAngelOneHistoricalResponse(String symbol, String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.path("status").asBoolean(false)) {
                log.warn("[HistoricalFetch] API error for symbol={}: {}",
                         symbol, root.path("message").asText());
                return List.of();
            }
            JsonNode data = root.path("data");
            if (data.isMissingNode() || !data.isArray()) return List.of();

            List<ReplayCandleDTO> result = new ArrayList<>();
            data.forEach(bar -> {
                try {
                    // Format: [datetime, open, high, low, close, volume]
                    String   dt     = bar.get(0).asText();
                    double   open   = bar.get(1).asDouble();
                    double   high   = bar.get(2).asDouble();
                    double   low    = bar.get(3).asDouble();
                    double   close  = bar.get(4).asDouble();
                    long     volume = bar.get(5).asLong();
                    // Angel One datetime: "2024-09-12T03:47:00+05:30" or "2024-09-12 03:47"
                    LocalDateTime candleTime = parseAngelOneDatetime(dt);
                    result.add(new ReplayCandleDTO(symbol, candleTime, open, high, low, close, volume));
                } catch (Exception ex) {
                    log.debug("[HistoricalFetch] Skipping malformed candle: {}", bar);
                }
            });
            return result;
        } catch (Exception e) {
            log.warn("[HistoricalFetch] JSON parse error for symbol={}", symbol, e);
            return List.of();
        }
    }

    private static final DateTimeFormatter AO_DT_PLAIN =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private LocalDateTime parseAngelOneDatetime(String dt) {
        // Angel One returns "2024-09-12T03:47:00+05:30" (ISO with offset) or "2024-09-12 03:47" (plain)
        try {
            // OffsetDateTime handles "+05:30" correctly; extract LocalDateTime as-is (already IST)
            return java.time.OffsetDateTime.parse(dt).toLocalDateTime();
        } catch (Exception e) {
            return LocalDateTime.parse(dt, AO_DT_PLAIN);
        }
    }

    /** Splits [from, to] into consecutive N-day windows. */
    private List<LocalDate[]> buildChunks(LocalDate from, LocalDate to, int chunkDays) {
        List<LocalDate[]> chunks = new ArrayList<>();
        LocalDate cursor = from;
        while (!cursor.isAfter(to)) {
            LocalDate end = cursor.plusDays(chunkDays - 1);
            if (end.isAfter(to)) end = to;
            chunks.add(new LocalDate[]{cursor, end});
            cursor = end.plusDays(1);
        }
        return chunks;
    }

    // ── Alpha Vantage implementation ──────────────────────────────────────────

    private Mono<MarketDataQuote> fetchAlphaVantage(String symbol) {
        return webClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/query")
                .queryParam("function", "TIME_SERIES_DAILY")
                .queryParam("symbol", symbol)
                .queryParam("outputsize", "compact")
                .queryParam("apikey", alphaVantageApiKey)
                .build())
            .retrieve()
            .bodyToMono(AlphaVantageTimeSeriesResponse.class)
            .map(response -> mapAlphaVantageToQuote(symbol, response))
            .doOnSuccess(q -> log.info("Market data fetched. provider=AlphaVantage symbol={} latestClose={}",
                q.symbol(), q.latestClose()))
            .doOnError(e -> log.error("Alpha Vantage fetch failed. symbol={}", symbol, e));
    }

    private MarketDataQuote mapAlphaVantageToQuote(String symbol, AlphaVantageTimeSeriesResponse response) {
        if (response == null || response.timeSeriesDaily() == null || response.timeSeriesDaily().isEmpty()) {
            throw new RuntimeException("Empty response from Alpha Vantage for symbol: " + symbol);
        }
        TreeMap<String, AlphaVantageTimeSeriesResponse.OhlcvData> sorted =
            new TreeMap<>((a, b) -> b.compareTo(a));
        sorted.putAll(response.timeSeriesDaily());
        List<Double> closingPrices = new ArrayList<>();
        sorted.values().forEach(ohlcv -> closingPrices.add(ohlcv.closeAsDouble()));
        AlphaVantageTimeSeriesResponse.OhlcvData latest = sorted.firstEntry().getValue();
        return new MarketDataQuote(symbol, latest.closeAsDouble(), latest.openAsDouble(),
            latest.highAsDouble(), latest.lowAsDouble(), latest.volumeAsLong(),
            closingPrices.subList(0, Math.min(50, closingPrices.size())), Instant.now());
    }
}
