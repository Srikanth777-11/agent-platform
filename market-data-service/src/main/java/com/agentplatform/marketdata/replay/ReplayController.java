package com.agentplatform.marketdata.replay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Phase-32: REST API for the Historical Replay Tuning Engine.
 *
 * <p>Active only in profile {@code historical-replay}.
 *
 * <p>Typical operator flow:
 * <ol>
 *   <li>POST /fetch-history?symbol=NIFTY.NSE&fromDate=2025-08-01&toDate=2026-02-28
 *       — downloads from Angel One → stores in history-service</li>
 *   <li>GET  /status — confirms N candles stored</li>
 *   <li>POST /start?symbol=NIFTY.NSE — starts the replay loop</li>
 *   <li>GET  /status — polls progress (candles, trades, win rate)</li>
 *   <li>POST /stop — pauses if needed</li>
 *   <li>POST /reset?symbol=NIFTY.NSE — wipes candles + state</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/market-data/replay")
@Profile("historical-replay")
public class ReplayController {

    private static final Logger log = LoggerFactory.getLogger(ReplayController.class);

    private final ReplayRunnerService runner;

    public ReplayController(ReplayRunnerService runner) {
        this.runner = runner;
    }

    /**
     * Downloads historical OHLCV candles from Angel One and stores them
     * in history-service. Safe to call multiple times — duplicate candles
     * are silently skipped by the UNIQUE(symbol, candle_time) constraint.
     *
     * @param symbol   ticker symbol, e.g. "NIFTY.NSE"
     * @param fromDate ISO date string, e.g. "2025-08-01"
     * @param toDate   ISO date string, e.g. "2026-02-28"
     */
    @PostMapping("/fetch-history")
    public Mono<ResponseEntity<Map<String, Object>>> fetchHistory(
            @RequestParam String symbol,
            @RequestParam String fromDate,
            @RequestParam String toDate) {
        log.info("[ReplayAPI] fetch-history. symbol={} from={} to={}", symbol, fromDate, toDate);
        return runner.fetchAndStoreHistory(symbol, fromDate, toDate)
            .map(n -> ResponseEntity.ok(Map.<String, Object>of(
                "symbol", symbol, "candlesIngested", n, "fromDate", fromDate, "toDate", toDate)))
            .onErrorResume(e -> {
                log.error("[ReplayAPI] fetch-history error. symbol={}", symbol, e);
                return Mono.just(ResponseEntity.<Map<String, Object>>status(500)
                    .body(Map.of("error", e.getMessage())));
            });
    }

    /**
     * Starts the replay loop. Candles must be stored first via fetch-history.
     */
    @PostMapping("/start")
    public Mono<ResponseEntity<Map<String, Object>>> start(@RequestParam String symbol) {
        log.info("[ReplayAPI] start. symbol={}", symbol);
        return runner.startReplay(symbol)
            .map(s -> ResponseEntity.ok(stateToMap(s)))
            .onErrorResume(e -> {
                log.error("[ReplayAPI] start error. symbol={}", symbol, e);
                return Mono.just(ResponseEntity.<Map<String, Object>>status(400)
                    .body(Map.of("error", e.getMessage())));
            });
    }

    /** Signals the replay loop to stop after the current candle. */
    @PostMapping("/stop")
    public Mono<ResponseEntity<String>> stop() {
        log.info("[ReplayAPI] stop requested");
        return runner.stopReplay()
            .then(Mono.just(ResponseEntity.ok("Stop signal sent")));
    }

    /**
     * Deletes stored candles and resets state.
     * Stops a running replay first.
     */
    @PostMapping("/reset")
    public Mono<ResponseEntity<String>> reset(@RequestParam String symbol) {
        log.info("[ReplayAPI] reset. symbol={}", symbol);
        return runner.reset(symbol)
            .then(Mono.just(ResponseEntity.ok("Reset complete for symbol=" + symbol)))
            .onErrorResume(e -> Mono.just(ResponseEntity.<String>status(500)
                .body("Reset failed: " + e.getMessage())));
    }

    /** Returns current replay progress and statistics. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(stateToMap(runner.getState()));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private Map<String, Object> stateToMap(ReplayState s) {
        return Map.of(
            "status",         s.getStatus().name(),
            "symbol",         s.getSymbol() != null ? s.getSymbol() : "",
            "currentCandle",  s.getCurrentIdx(),
            "totalCandles",   s.getTotalCandles(),
            "progressPct",    Math.round(s.getProgressPct() * 10.0) / 10.0,
            "tradesSignaled", s.getTradesSignaled(),
            "tradesResolved", s.getTradesResolved(),
            "winRate",        Math.round(s.getWinRate() * 1000.0) / 1000.0,
            "startedAt",      s.getStartedAt() != null ? s.getStartedAt().toString() : "",
            "errorMessage",   s.getErrorMessage() != null ? s.getErrorMessage() : ""
        );
    }
}
