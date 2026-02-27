package com.agentplatform.scheduler.job;

import com.agentplatform.common.cognition.TradingSession;
import com.agentplatform.common.cognition.TradingSessionClassifier;
import com.agentplatform.common.event.MarketDataEvent;
import com.agentplatform.common.model.MarketRegime;
import com.agentplatform.scheduler.client.HistoryClient;
import com.agentplatform.scheduler.strategy.AdaptiveTempoStrategy;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Adaptive Tempo Controller — evolves the original fixed-cron scheduler into a
 * regime-aware trigger that dynamically adjusts orchestration frequency based on
 * the last known {@link MarketRegime} for each symbol.
 *
 * <p>On startup, each configured symbol enters an independent scheduling loop:
 * <pre>
 *   delay(interval) → trigger orchestrator → fetch latest regime → compute next interval → repeat
 * </pre>
 *
 * <p>The loop is not infinite nested streams — each cycle is a fresh {@link Mono} pipeline
 * whose terminal {@code .subscribe()} schedules the next cycle via a single recursive call.
 * {@code Mono.delay()} releases the thread during the wait, and the {@code .subscribe()}
 * callback runs on Reactor's parallel scheduler — no thread is blocked.
 *
 * <p><strong>Safety guarantee:</strong> the loop never stops. If the orchestrator call fails
 * or history-service is unreachable, errors are absorbed and the loop reschedules with the
 * {@link AdaptiveTempoStrategy#FALLBACK_INTERVAL fallback interval} (5 minutes).
 */
@Component
public class MarketDataScheduler {

    private static final Logger log = LoggerFactory.getLogger(MarketDataScheduler.class);

    private final WebClient orchestratorClient;
    private final HistoryClient historyClient;

    @Value("${scheduler.symbols:IBM,AAPL,GOOGL}")
    private String symbolsConfig;

    public MarketDataScheduler(WebClient orchestratorClient, HistoryClient historyClient) {
        this.orchestratorClient = orchestratorClient;
        this.historyClient      = historyClient;
    }

    /**
     * Kicks off an independent adaptive scheduling loop for each configured symbol.
     * Initial interval is the UNKNOWN fallback (5 min) — the regime is not yet known
     * because no decisions have been persisted for this container lifecycle.
     */
    @PostConstruct
    public void startAdaptiveScheduling() {
        List<String> symbols = List.of(symbolsConfig.split(","));
        log.info("Adaptive tempo controller started. symbols={}", symbols);

        for (String raw : symbols) {
            String symbol = raw.trim();
            Duration initial = AdaptiveTempoStrategy.resolve(MarketRegime.UNKNOWN);
            log.info("ADAPTIVE_TEMPO_SELECTED symbol={} marketRegime={} nextIntervalSeconds={}",
                     symbol, MarketRegime.UNKNOWN, initial.toSeconds());
            scheduleNextCycle(symbol, initial);
        }
    }

    // ── adaptive loop ─────────────────────────────────────────────────────────

    /**
     * Schedules one orchestration cycle for {@code symbol} after {@code delay}.
     *
     * <p>When the cycle completes (success or failure), the method reschedules
     * itself with the appropriate interval. This is a tail-recursive pattern
     * in the reactive sense — each cycle is a fresh Mono; no stack accumulation.
     */
    private void scheduleNextCycle(String symbol, Duration delay) {
        Mono.delay(delay)
            .then(triggerOrchestration(symbol))
            .then(historyClient.fetchLatestRegime(symbol))
            .subscribe(
                regime -> {
                    Instant now = Instant.now();
                    TradingSession session = TradingSessionClassifier.classify(now);
                    Duration next = AdaptiveTempoStrategy.resolve(regime, now);
                    log.info("ADAPTIVE_TEMPO_SELECTED symbol={} regime={} session={} nextIntervalSeconds={}",
                             symbol, regime, session, next.toSeconds());
                    scheduleNextCycle(symbol, next);
                },
                err -> {
                    log.error("Scheduling cycle failed for symbol={} — rescheduling with fallback interval",
                              symbol, err);
                    scheduleNextCycle(symbol, AdaptiveTempoStrategy.FALLBACK_INTERVAL);
                }
            );
    }

    // ── orchestrator trigger (fire-and-continue) ──────────────────────────────

    /**
     * Fires a single orchestration request for the given symbol.
     *
     * <p>Errors on the orchestrator call are logged and absorbed — the chain
     * continues to the regime fetch so that the scheduling loop never stalls.
     */
    private Mono<Void> triggerOrchestration(String symbol) {
        String traceId = UUID.randomUUID().toString();
        MarketDataEvent event = new MarketDataEvent(symbol, Instant.now(), traceId);

        log.info("Triggering orchestration. symbol={} traceId={}", symbol, traceId);

        return orchestratorClient.post()
            .uri("/api/v1/orchestrate/trigger")
            .header("X-Trace-Id", traceId)
            .bodyValue(event)
            .retrieve()
            .toBodilessEntity()
            .doOnSuccess(r -> log.info("Orchestrator triggered. symbol={} traceId={} status={}",
                                       symbol, traceId, r.getStatusCode()))
            .onErrorResume(e -> {
                log.error("Orchestrator call failed. symbol={} traceId={}", symbol, traceId, e);
                return Mono.empty();
            })
            .then();
    }
}
