package com.agentplatform.marketdata.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentplatform.marketdata.model.MarketDataQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// ─────────────────────────────────────────────────────────────────────────────
// PROVIDER SWITCH GUIDE
//
// TODAY  → Twelve Data  (active below)
// Switch back → uncomment Alpha Vantage section, comment Twelve Data section,
//               update application.yml + WebClientConfig.java baseUrl comment
// ─────────────────────────────────────────────────────────────────────────────

public class MarketDataWebClient {

    private static final Logger log = LoggerFactory.getLogger(MarketDataWebClient.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    // ── Active: Twelve Data ───────────────────────────────────────────────────
    @Value("${twelve-data.api-key:demo}")
    private String apiKey;

    // ── Tomorrow: Alpha Vantage (uncomment to switch back) ───────────────────
    // @Value("${alpha-vantage.api-key:demo}")
    // private String apiKey;

    public MarketDataWebClient(WebClient marketDataWebClient, ObjectMapper objectMapper) {
        this.webClient    = marketDataWebClient;
        this.objectMapper = objectMapper;
    }

    public Mono<MarketDataQuote> fetchQuote(String symbol) {
        log.info("Fetching market data. provider=TwelveData symbol={}", symbol);
        return fetchTwelveData(symbol);

        // ── Alpha Vantage (uncomment tomorrow to switch back) ─────────────────
        // log.info("Fetching market data. provider=AlphaVantage symbol={}", symbol);
        // return fetchAlphaVantage(symbol);
    }

    // ── Twelve Data implementation ────────────────────────────────────────────

    private Mono<MarketDataQuote> fetchTwelveData(String symbol) {
        return webClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/time_series")
                .queryParam("symbol", symbol)
                .queryParam("interval", "5min")
                .queryParam("outputsize", "50")
                .queryParam("apikey", apiKey)
                .build())
            .retrieve()
            .bodyToMono(String.class)
            .map(json -> parseTwelveDataResponse(symbol, json))
            .doOnSuccess(q -> log.info("Market data fetched. provider=TwelveData symbol={} latestClose={}",
                q.symbol(), q.latestClose()))
            .doOnError(e -> log.error("Twelve Data fetch failed. symbol={}", symbol, e));
    }

    private MarketDataQuote parseTwelveDataResponse(String symbol, String json) {
        try {
            JsonNode root = objectMapper.readTree(json);

            // Check for API errors
            if (root.has("status") && "error".equals(root.path("status").asText())) {
                throw new RuntimeException("Twelve Data error: " + root.path("message").asText());
            }

            JsonNode values = root.path("values");
            if (values.isMissingNode() || !values.isArray() || values.size() == 0) {
                throw new RuntimeException("No data returned from Twelve Data for symbol: " + symbol);
            }

            // values[0] = most recent bar
            JsonNode latest = values.get(0);
            double close  = latest.path("close").asDouble(0);
            double open   = latest.path("open").asDouble(close);
            double high   = latest.path("high").asDouble(close);
            double low    = latest.path("low").asDouble(close);
            long   volume = latest.path("volume").asLong(0);

            List<Double> closingPrices = new ArrayList<>();
            values.forEach(bar -> closingPrices.add(bar.path("close").asDouble(0)));

            return new MarketDataQuote(symbol, close, open, high, low, volume, closingPrices, Instant.now());

        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Twelve Data response for symbol: " + symbol, e);
        }
    }

    // ── Alpha Vantage implementation (commented — uncomment tomorrow) ─────────

    // private Mono<MarketDataQuote> fetchAlphaVantage(String symbol) {
    //     return webClient.get()
    //         .uri(uriBuilder -> uriBuilder
    //             .path("/query")
    //             .queryParam("function", "TIME_SERIES_DAILY")
    //             .queryParam("symbol", symbol)
    //             .queryParam("outputsize", "compact")
    //             .queryParam("apikey", apiKey)
    //             .build())
    //         .retrieve()
    //         .bodyToMono(AlphaVantageTimeSeriesResponse.class)
    //         .map(response -> mapAlphaVantageToQuote(symbol, response))
    //         .doOnSuccess(q -> log.info("Market data fetched. provider=AlphaVantage symbol={} latestClose={}",
    //             q.symbol(), q.latestClose()))
    //         .doOnError(e -> log.error("Alpha Vantage fetch failed. symbol={}", symbol, e));
    // }
    //
    // private MarketDataQuote mapAlphaVantageToQuote(String symbol, AlphaVantageTimeSeriesResponse response) {
    //     if (response == null || response.timeSeriesDaily() == null || response.timeSeriesDaily().isEmpty()) {
    //         throw new RuntimeException("Empty response from Alpha Vantage for symbol: " + symbol);
    //     }
    //     TreeMap<String, AlphaVantageTimeSeriesResponse.OhlcvData> sorted =
    //         new TreeMap<>((a, b) -> b.compareTo(a));
    //     sorted.putAll(response.timeSeriesDaily());
    //     List<Double> closingPrices = new ArrayList<>();
    //     sorted.values().forEach(ohlcv -> closingPrices.add(ohlcv.closeAsDouble()));
    //     AlphaVantageTimeSeriesResponse.OhlcvData latest = sorted.firstEntry().getValue();
    //     return new MarketDataQuote(symbol, latest.closeAsDouble(), latest.openAsDouble(),
    //         latest.highAsDouble(), latest.lowAsDouble(), latest.volumeAsLong(),
    //         closingPrices.subList(0, Math.min(50, closingPrices.size())), Instant.now());
    // }
}
