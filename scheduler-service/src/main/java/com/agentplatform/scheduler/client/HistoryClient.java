package com.agentplatform.scheduler.client;

import com.agentplatform.common.model.MarketRegime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Fetches the last known {@link MarketRegime} for a symbol from history-service.
 *
 * <p>Used by the adaptive tempo controller to determine the next trigger interval.
 * All errors are absorbed with a {@link MarketRegime#UNKNOWN} fallback so that the
 * scheduling loop never stalls if history-service is unreachable.
 */
@Component
public class HistoryClient {

    private static final Logger log = LoggerFactory.getLogger(HistoryClient.class);

    private final WebClient historyClient;

    public HistoryClient(WebClient historyClient) {
        this.historyClient = historyClient;
    }

    /**
     * @param symbol stock ticker (e.g. "AAPL")
     * @return the most recent {@link MarketRegime} for the symbol;
     *         falls back to {@link MarketRegime#UNKNOWN} on any error
     */
    public Mono<MarketRegime> fetchLatestRegime(String symbol) {
        return historyClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/api/v1/history/latest-regime")
                .queryParam("symbol", symbol)
                .build())
            .retrieve()
            .bodyToMono(String.class)
            .map(regime -> {
                try {
                    return MarketRegime.valueOf(regime);
                } catch (IllegalArgumentException e) {
                    log.warn("Unrecognised regime string '{}' for symbol={} — defaulting to UNKNOWN",
                             regime, symbol);
                    return MarketRegime.UNKNOWN;
                }
            })
            .onErrorResume(e -> {
                log.warn("Regime fetch failed for symbol={} — using UNKNOWN fallback. reason={}",
                         symbol, e.getMessage());
                return Mono.just(MarketRegime.UNKNOWN);
            });
    }
}
