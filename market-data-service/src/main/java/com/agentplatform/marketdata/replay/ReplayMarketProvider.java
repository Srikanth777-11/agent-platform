package com.agentplatform.marketdata.replay;

import com.agentplatform.marketdata.model.MarketDataQuote;
import com.agentplatform.marketdata.provider.MarketDataProvider;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Replay adapter — loads last N decisions from history-service once per symbol,
 * then cycles through them tick-by-tick on each scheduler-driven quote request.
 *
 * <p>Prices are synthesized from {@code confidenceScore}:
 * {@code close = referencePrice * (0.9 + confidence * 0.2)}
 * giving a [referencePrice*0.9 .. referencePrice*1.1] range suitable for UI testing.
 *
 * <p>Active only when Spring profile {@code replay} is set.
 * {@code @Primary} ensures it wins over the live {@link com.agentplatform.marketdata.service.MarketDataService} bean.
 */
@Service
@Primary
@Profile("replay")
public class ReplayMarketProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(ReplayMarketProvider.class);

    private final WebClient historyClient;
    private final int       candleLimit;
    private final double    referencePrice;

    private final ConcurrentHashMap<String, List<ReplayDecision>> cache   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger>        cursors = new ConcurrentHashMap<>();

    public ReplayMarketProvider(
            WebClient.Builder builder,
            @Value("${replay.history-service-url:http://localhost:8084}") String historyUrl,
            @Value("${replay.candle-limit:50}")       int    candleLimit,
            @Value("${replay.reference-price:100.0}") double referencePrice) {
        this.historyClient  = builder.baseUrl(historyUrl).build();
        this.candleLimit    = candleLimit;
        this.referencePrice = referencePrice;
    }

    @Override
    public Mono<MarketDataQuote> getQuote(String symbol) {
        return loadDecisions(symbol)
            .map(decisions -> {
                int idx = cursors
                    .computeIfAbsent(symbol, k -> new AtomicInteger(0))
                    .getAndUpdate(i -> (i + 1) % decisions.size());
                ReplayDecision current = decisions.get(idx);
                log.debug("[Replay] symbol={} tick={}/{} signal={} confidence={}",
                    symbol, idx + 1, decisions.size(), current.finalSignal(), current.confidence());
                return toQuote(symbol, decisions, idx);
            });
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private Mono<List<ReplayDecision>> loadDecisions(String symbol) {
        List<ReplayDecision> cached = cache.get(symbol);
        if (cached != null && !cached.isEmpty()) return Mono.just(cached);

        log.info("[Replay] Loading candles from history-service. symbol={} limit={}", symbol, candleLimit);
        return historyClient.get()
            .uri("/api/v1/history/replay/{symbol}?limit={limit}", symbol, candleLimit)
            .retrieve()
            .bodyToFlux(ReplayDecision.class)
            .collectList()
            .doOnSuccess(list -> {
                if (!list.isEmpty()) {
                    cache.put(symbol, list);
                    log.info("[Replay] Loaded {} candles for symbol={}", list.size(), symbol);
                } else {
                    log.warn("[Replay] No history found for symbol={} — UI will show empty data", symbol);
                }
            });
    }

    private MarketDataQuote toQuote(String symbol, List<ReplayDecision> decisions, int idx) {
        ReplayDecision current = decisions.get(idx);
        double close = referencePrice * (0.9 + current.confidence() * 0.2);

        // Sliding closing-price window: current candle first, then preceding candles
        List<Double> window = new ArrayList<>();
        for (int i = 0; i < decisions.size(); i++) {
            int pos = (idx - i + decisions.size()) % decisions.size();
            window.add(referencePrice * (0.9 + decisions.get(pos).confidence() * 0.2));
        }

        return new MarketDataQuote(
            symbol,
            close,
            close * 0.998,   // synthetic open  (−0.2%)
            close * 1.005,   // synthetic high  (+0.5%)
            close * 0.995,   // synthetic low   (−0.5%)
            1_000_000L,
            window,
            Instant.now()
        );
    }

    // ── local DTO — mirrors history-service SnapshotDecisionDTO ──────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReplayDecision(
        @JsonProperty("symbol")        String        symbol,
        @JsonProperty("finalSignal")   String        finalSignal,
        @JsonProperty("confidence")    double        confidence,
        @JsonProperty("marketRegime")  String        marketRegime,
        @JsonProperty("divergenceFlag") Boolean      divergenceFlag,
        @JsonProperty("aiReasoning")   String        aiReasoning,
        @JsonProperty("savedAt")       LocalDateTime savedAt
    ) {}
}
