package com.agentplatform.marketdata.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentplatform.marketdata.model.MarketDataQuote;
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
