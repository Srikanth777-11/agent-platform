package com.agentplatform.history.service;

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

        boolean profitable = d.getOutcomePercent() > 0.0;
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
