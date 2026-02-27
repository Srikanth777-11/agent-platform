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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

public class MarketDataWebClient {

    private static final Logger log = LoggerFactory.getLogger(MarketDataWebClient.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    // ── Active: Alpha Vantage ─────────────────────────────────────────────────
    @Value("${alpha-vantage.api-key:demo}")
    private String apiKey;

    public MarketDataWebClient(WebClient marketDataWebClient, ObjectMapper objectMapper) {
        this.webClient    = marketDataWebClient;
        this.objectMapper = objectMapper;
    }

    public Mono<MarketDataQuote> fetchQuote(String symbol) {
        log.info("Fetching market data. provider=AlphaVantage symbol={}", symbol);
        return fetchAlphaVantage(symbol);
    }

    // ── Alpha Vantage implementation ──────────────────────────────────────────

    private Mono<MarketDataQuote> fetchAlphaVantage(String symbol) {
        return webClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/query")
                .queryParam("function", "TIME_SERIES_DAILY")
                .queryParam("symbol", symbol)
                .queryParam("outputsize", "compact")
                .queryParam("apikey", apiKey)
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
