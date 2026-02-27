package com.agentplatform.marketdata.replay;

import com.agentplatform.common.event.MarketDataEvent;
import com.agentplatform.marketdata.client.MarketDataWebClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase-32: Drives the historical replay tuning loop.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Download historical candles from Angel One and POST to history-service for storage.</li>
 *   <li>Load stored candles into {@link HistoricalReplayProvider} in memory.</li>
 *   <li>Iterate candles, advancing the provider cursor and triggering the real
 *       orchestrator pipeline for each candle.</li>
 *   <li>After each pipeline settles (~2 s), explicitly resolve P&L from the next
 *       candle's close price, posting the outcome to history-service so agent weights
 *       update after every trade — exactly as in live mode but compressed.</li>
 * </ol>
 *
 * <p>The scheduler is bypassed entirely: this service drives triggers directly.
 * Active only in profile {@code historical-replay}.
 */
@Service
@Profile("historical-replay")
public class ReplayRunnerService {

    private static final Logger log = LoggerFactory.getLogger(ReplayRunnerService.class);

    /** Milliseconds to wait after each trigger before fetching the decision. */
    private static final long PIPELINE_SETTLE_MS = 2_000;

    private final HistoricalReplayProvider provider;
    private final MarketDataWebClient      marketDataWebClient;
    private final WebClient                historyClient;
    private final WebClient                orchestratorClient;

    private final ReplayState      state       = new ReplayState();
    private final AtomicBoolean    stopFlag    = new AtomicBoolean(false);

    public ReplayRunnerService(
            HistoricalReplayProvider provider,
            MarketDataWebClient marketDataWebClient,
            WebClient.Builder builder,
            @Value("${replay.history-service-url:http://localhost:8085}")     String historyUrl,
            @Value("${replay.orchestrator-url:http://localhost:8081}")         String orchestratorUrl) {
        this.provider            = provider;
        this.marketDataWebClient = marketDataWebClient;
        this.historyClient       = builder.baseUrl(historyUrl).build();
        this.orchestratorClient  = builder.baseUrl(orchestratorUrl).build();
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Downloads historical candles from Angel One for {@code symbol} and stores
     * them in history-service. Returns the number of candles ingested.
     *
     * @param symbol   ticker symbol (e.g. "NIFTY.NSE")
     * @param fromDate start date (ISO, e.g. "2025-08-01")
     * @param toDate   end date   (ISO, e.g. "2026-02-28")
     */
    public Mono<Long> fetchAndStoreHistory(String symbol, String fromDate, String toDate) {
        LocalDate from = LocalDate.parse(fromDate);
        LocalDate to   = LocalDate.parse(toDate);

        log.info("[Replay] Fetching history. symbol={} from={} to={}", symbol, from, to);
        return marketDataWebClient.fetchHistoricalCandles(symbol, from, to, "FIVE_MINUTE")
            .flatMap(candles -> {
                if (candles.isEmpty()) {
                    log.warn("[Replay] No candles returned from Angel One. symbol={}", symbol);
                    return Mono.just(0L);
                }
                log.info("[Replay] Posting {} candles to history-service. symbol={}", candles.size(), symbol);
                return historyClient.post()
                    .uri("/api/v1/history/replay-candles/ingest")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(candles)
                    .retrieve()
                    .bodyToMono(Long.class)
                    .doOnSuccess(n -> log.info("[Replay] history-service ingested {} candles. symbol={}", n, symbol));
            });
    }

    /**
     * Starts the replay loop for {@code symbol} in a background thread.
     * Candles must already be stored in history-service (call fetchAndStoreHistory first).
     */
    public Mono<ReplayState> startReplay(String symbol) {
        if (state.getStatus() == ReplayState.Status.RUNNING) {
            return Mono.error(new IllegalStateException("Replay already running for symbol=" + symbol));
        }
        stopFlag.set(false);

        return loadCandlesFromHistory(symbol)
            .flatMap(candles -> {
                if (candles.isEmpty()) {
                    return Mono.error(new IllegalStateException(
                        "No candles found in history-service for symbol=" + symbol +
                        ". Call fetch-history first."));
                }
                provider.loadCandles(symbol, candles);
                state.start(symbol, candles.size());
                log.info("[Replay] Starting replay loop. symbol={} candles={}", symbol, candles.size());

                // Run the loop on a bounded-elastic thread — uses blocking sleep
                Mono.fromRunnable(() -> runLoop(symbol, candles))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                        v -> {},
                        e -> {
                            log.error("[Replay] Loop failed unexpectedly. symbol={}", symbol, e);
                            state.error(e.getMessage());
                        }
                    );

                return Mono.just(state);
            });
    }

    public Mono<Void> stopReplay() {
        log.info("[Replay] Stop requested.");
        stopFlag.set(true);
        return Mono.empty();
    }

    public ReplayState getState() {
        return state;
    }

    public Mono<Void> reset(String symbol) {
        if (state.getStatus() == ReplayState.Status.RUNNING) {
            stopFlag.set(true);
        }
        state.reset();
        return historyClient.delete()
            .uri("/api/v1/history/replay-candles/{symbol}", symbol)
            .retrieve()
            .bodyToMono(Void.class)
            .doOnSuccess(v -> log.info("[Replay] Reset complete. symbol={}", symbol));
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private Mono<List<ReplayCandleDTO>> loadCandlesFromHistory(String symbol) {
        return historyClient.get()
            .uri("/api/v1/history/replay-candles/{symbol}", symbol)
            .retrieve()
            .bodyToFlux(ReplayCandleDTO.class)
            .collectList()
            .doOnSuccess(c -> log.info("[Replay] Loaded {} candles from history-service. symbol={}", c.size(), symbol));
    }

    /**
     * Core replay loop — runs on a bounded-elastic thread.
     * Blocking operations (Thread.sleep, WebClient.block) are intentional here.
     */
    @SuppressWarnings("BusyWait")
    private void runLoop(String symbol, List<ReplayCandleDTO> candles) {
        log.info("[Replay] Loop started. symbol={} totalCandles={}", symbol, candles.size());

        for (int i = 0; i < candles.size(); i++) {
            if (stopFlag.get()) {
                log.info("[Replay] Stop flag set — exiting loop at candle {}/{}", i, candles.size());
                state.complete();
                return;
            }

            state.advanceCandle(i);
            provider.setCursor(symbol, i);

            ReplayCandleDTO candle = candles.get(i);
            String traceId = UUID.randomUUID().toString();

            // ── 1. Trigger the full pipeline ──────────────────────────────────
            try {
                MarketDataEvent event = new MarketDataEvent(
                    symbol,
                    candle.candleTime().toInstant(ZoneOffset.UTC),
                    traceId
                );
                orchestratorClient.post()
                    .uri("/api/v1/orchestrate/trigger")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(event)
                    .retrieve()
                    .bodyToMono(String.class)  // orchestrator returns List<AnalysisResult> JSON
                    .block();
            } catch (Exception e) {
                log.warn("[Replay] Trigger failed for candle {}. traceId={} err={}", i, traceId, e.getMessage());
                continue;
            }

            // ── 2. Wait for pipeline to settle ────────────────────────────────
            try {
                Thread.sleep(PIPELINE_SETTLE_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.info("[Replay] Interrupted during sleep — stopping.");
                state.complete();
                return;
            }

            // ── 3. Fetch latest decision snapshot for the symbol ──────────────
            SnapshotDecision decision = fetchLatestDecision(symbol);
            if (decision == null) {
                log.debug("[Replay] No decision found for candle {}. symbol={}", i, symbol);
                continue;
            }

            // ── 4. Resolve P&L if BUY or SELL ────────────────────────────────
            if ("BUY".equals(decision.finalSignal()) || "SELL".equals(decision.finalSignal())) {
                state.incrementSignaled();

                double entryPrice = decision.entryPrice() != null && decision.entryPrice() > 0.0
                    ? decision.entryPrice() : candle.close();
                int holdMinutes = decision.estimatedHoldMinutes() != null
                    ? decision.estimatedHoldMinutes() : 5;
                int exitIdx = Math.min(i + Math.max(1, holdMinutes / 5), candles.size() - 1);

                double exitClose  = candles.get(exitIdx).close();
                double outcome    = (exitClose - entryPrice) / entryPrice * 100.0;
                if ("SELL".equals(decision.finalSignal())) outcome = -outcome;

                final double finalOutcome  = outcome;
                final int    finalHold     = holdMinutes;
                final String finalTraceId  = traceId;

                try {
                    historyClient.post()
                        .uri("/api/v1/history/outcome/{traceId}", finalTraceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("outcomePercent", finalOutcome, "holdMinutes", finalHold))
                        .retrieve()
                        .bodyToMono(Void.class)
                        .block();

                    state.incrementResolved(finalOutcome > 0);
                    log.debug("[Replay] P&L resolved. candle={} signal={} entry={} exit={} outcome={}%",
                        i, decision.finalSignal(), entryPrice, exitClose,
                        String.format("%.2f", finalOutcome));
                } catch (Exception e) {
                    log.warn("[Replay] Outcome post failed. traceId={} err={}", finalTraceId, e.getMessage());
                }
            }

            if (i % 100 == 0) {
                log.info("[Replay] Progress. symbol={} candle={}/{} signaled={} resolved={} winRate={}",
                    symbol, i, candles.size(), state.getTradesSignaled(),
                    state.getTradesResolved(), String.format("%.2f", state.getWinRate()));
            }
        }

        state.complete();
        log.info("[Replay] Loop complete. symbol={} signaled={} resolved={} winRate={}",
            symbol, state.getTradesSignaled(), state.getTradesResolved(),
            String.format("%.2f", state.getWinRate()));
    }

    private SnapshotDecision fetchLatestDecision(String symbol) {
        try {
            List<SnapshotDecision> snapshots = historyClient.get()
                .uri("/api/v1/history/snapshot")
                .retrieve()
                .bodyToFlux(SnapshotDecision.class)
                .collectList()
                .block();
            if (snapshots == null) return null;
            return snapshots.stream()
                .filter(s -> symbol.equals(s.symbol()))
                .findFirst()
                .orElse(null);
        } catch (Exception e) {
            log.warn("[Replay] Snapshot fetch failed. err={}", e.getMessage());
            return null;
        }
    }

    // ── Local DTO mirrors history-service's SnapshotDecisionDTO ─────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SnapshotDecision(
        @JsonProperty("symbol")               String  symbol,
        @JsonProperty("finalSignal")          String  finalSignal,
        @JsonProperty("entryPrice")           Double  entryPrice,
        @JsonProperty("estimatedHoldMinutes") Integer estimatedHoldMinutes
    ) {}
}
