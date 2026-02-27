package com.agentplatform.trade.client;

import com.agentplatform.trade.client.dto.DecisionMetricsResponse;
import com.agentplatform.trade.client.dto.MarketStateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Read-only client for history-service projection endpoints.
 * No write operations. No orchestrator interaction.
 */
@Component
public class HistoryServiceClient {

    private static final Logger log = LoggerFactory.getLogger(HistoryServiceClient.class);

    private final WebClient webClient;

    public HistoryServiceClient(@Value("${history-service.base-url}") String baseUrl,
                                WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }

    /**
     * Reads per-symbol decision metrics from the projection table.
     */
    public Mono<DecisionMetricsResponse> getDecisionMetrics(String symbol) {
        return webClient.get()
            .uri("/api/v1/history/decision-metrics/{symbol}", symbol)
            .retrieve()
            .bodyToMono(DecisionMetricsResponse.class)
            .doOnError(e -> log.warn("Failed to fetch decision metrics. symbol={}", symbol, e))
            .onErrorResume(e -> Mono.empty());
    }

    /**
     * Reads market state (momentum classification) for a symbol.
     * Filters from the Flux endpoint to find the matching symbol.
     */
    public Mono<MarketStateResponse> getMarketState(String symbol) {
        return webClient.get()
            .uri("/api/v1/history/market-state")
            .retrieve()
            .bodyToFlux(MarketStateResponse.class)
            .filter(ms -> symbol.equals(ms.symbol()))
            .next()
            .doOnError(e -> log.warn("Failed to fetch market state. symbol={}", symbol, e))
            .onErrorResume(e -> Mono.empty());
    }
}
