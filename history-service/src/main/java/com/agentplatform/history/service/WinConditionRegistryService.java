package com.agentplatform.history.service;

import com.agentplatform.common.risk.BayesianEdgeEstimator;
import com.agentplatform.history.model.DecisionHistory;
import com.agentplatform.history.repository.EdgeConditionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Phase-38a: WinConditionRegistry — passive statistical learning layer.
 *
 * <p>After each resolved LIVE_AI trade, records the outcome into the
 * {@code edge_conditions} table keyed by (tradingSession, marketRegime,
 * directionalBias, signal). Over time this builds a statistical picture
 * of which trade setups actually win.
 *
 * <p><strong>Phase-38a = passive only.</strong> This service never blocks
 * any trade — it only collects data. Phase-38b will activate the gate.
 *
 * <p>REPLAY_CONSENSUS_ONLY decisions are excluded — only LIVE_AI decisions
 * contribute to the registry so that backtested data does not bias the learning.
 */
@Service
public class WinConditionRegistryService {

    private static final Logger log = LoggerFactory.getLogger(WinConditionRegistryService.class);

    private final EdgeConditionRepository edgeConditionRepository;

    public WinConditionRegistryService(EdgeConditionRepository edgeConditionRepository) {
        this.edgeConditionRepository = edgeConditionRepository;
    }

    private static final int    MIN_SAMPLES           = 20;    // Phase-38b gate minimum
    private static final double WIN_RATE_THRESHOLD    = 0.52;  // minimum acceptable win rate
    private static final double POSTERIOR_CONFIDENCE  = 0.70;  // Bayesian confidence floor

    /**
     * Phase-45: Bayesian edge evaluation gate (for Phase-38b activation).
     *
     * <p>Replaces the naive static threshold {@code winRate < 0.52 → WATCH} with a
     * statistically honest posterior probability. Accounts for sample size uncertainty:
     * 0.63 win rate on 25 samples is treated as more reliable than 0.55 on 100.
     *
     * <h3>Decision logic</h3>
     * <ul>
     *   <li>No data or {@code totalCount < 20}  → {@code ALLOW} (insufficient data, don't block)</li>
     *   <li>P(winRate > 0.52) ≥ 0.70            → {@code ALLOW} (confident edge exists)</li>
     *   <li>P(winRate > 0.52) < 0.70            → {@code WATCH} (not statistically confident)</li>
     * </ul>
     *
     * <p><strong>Phase-38a/45:</strong> This method exists but is NOT yet wired into the
     * decision pipeline. It will be activated when Phase-38b goes live (Angel One data).
     *
     * @param tradingSession  e.g. OPENING_PHASE_2
     * @param marketRegime    e.g. VOLATILE
     * @param directionalBias e.g. STRONG_BULLISH
     * @param signal          BUY or SELL
     * @return Mono of {@code true} = ALLOW, {@code false} = WATCH
     */
    public Mono<Boolean> evaluateEdge(String tradingSession, String marketRegime,
                                      String directionalBias, String signal) {
        return edgeConditionRepository.findByCondition(tradingSession, marketRegime, directionalBias, signal)
            .map(condition -> {
                int total = condition.getTotalCount();
                if (total < MIN_SAMPLES) {
                    log.debug("[BayesianGate] Insufficient samples ({}/{}) — ALLOW. session={} regime={} bias={} signal={}",
                        total, MIN_SAMPLES, tradingSession, marketRegime, directionalBias, signal);
                    return true; // not enough data to block
                }
                double posterior = BayesianEdgeEstimator.posteriorProbability(
                    condition.getWinCount(), condition.getLossCount(), WIN_RATE_THRESHOLD);
                boolean allow = posterior >= POSTERIOR_CONFIDENCE;
                log.info("[BayesianGate] session={} regime={} bias={} signal={} wins={} losses={} posterior={} → {}",
                    tradingSession, marketRegime, directionalBias, signal,
                    condition.getWinCount(), condition.getLossCount(),
                    String.format("%.3f", posterior), allow ? "ALLOW" : "WATCH");
                return allow;
            })
            .defaultIfEmpty(true) // no registry entry yet → ALLOW (no data to block on)
            .onErrorResume(e -> {
                log.warn("[BayesianGate] Registry query failed (non-fatal) → ALLOW. reason={}", e.getMessage());
                return Mono.just(true);
            });
    }

    /**
     * Records the resolved trade outcome into the edge_conditions registry.
     *
     * <p>Called after outcome_resolved is set to true in HistoryService.
     * Skips REPLAY_CONSENSUS_ONLY rows and any row missing key fields.
     *
     * @param d the fully resolved DecisionHistory entity
     * @return Mono<Void> — non-fatal, errors are logged and swallowed
     */
    public Mono<Void> record(DecisionHistory d) {
        // Only LIVE_AI decisions contribute to the registry
        if (!"LIVE_AI".equals(d.getDecisionMode())) {
            log.debug("[Registry] Skipped REPLAY row. traceId={} mode={}", d.getTraceId(), d.getDecisionMode());
            return Mono.empty();
        }

        // Must be a BUY or SELL — WATCH/HOLD have no outcome to learn from
        String signal = d.getFinalSignal();
        if (!"BUY".equals(signal) && !"SELL".equals(signal)) {
            return Mono.empty();
        }

        // All condition keys must be present
        String session = d.getTradingSession();
        String regime  = d.getMarketRegime();
        String bias    = d.getDirectionalBias();
        if (session == null || regime == null || bias == null || d.getOutcomePercent() == null) {
            log.debug("[Registry] Skipped row with missing fields. traceId={}", d.getTraceId());
            return Mono.empty();
        }

        // Phase-41: use outcomeLabel if available, fall back to raw outcomePercent
        boolean profitable;
        String label = d.getOutcomeLabel();
        if (label != null) {
            profitable = "FAST_WIN".equals(label) || "SLOW_WIN".equals(label) || "TARGET_HIT".equals(label);
        } else {
            profitable = d.getOutcomePercent() > 0.0;
        }
        int win  = profitable ? 1 : 0;
        int loss = profitable ? 0 : 1;

        return edgeConditionRepository.upsertCondition(session, regime, bias, signal, win, loss)
            .doOnSuccess(v -> log.info(
                "[Registry] Recorded. session={} regime={} bias={} signal={} outcome={}% win={}",
                session, regime, bias, signal,
                String.format("%.3f", d.getOutcomePercent()), profitable))
            .onErrorResume(e -> {
                log.warn("[Registry] Failed to record condition (non-fatal). traceId={} reason={}",
                    d.getTraceId(), e.getMessage());
                return Mono.empty();
            });
    }
}
