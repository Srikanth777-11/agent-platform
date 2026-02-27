package com.agentplatform.orchestrator.guard;

import com.agentplatform.common.consensus.ConsensusEngine;
import com.agentplatform.common.consensus.ConsensusResult;
import com.agentplatform.common.model.AnalysisResult;

import java.util.List;
import java.util.Map;

/**
 * Pipeline safety wrapper for {@link ConsensusEngine} invocations.
 *
 * <p>Prevents the orchestration pipeline from failing when agent results are
 * null or empty — conditions that would cause {@link ConsensusEngine#compute}
 * to produce undefined behaviour.
 *
 * <p>This class is a pure utility:
 * <ul>
 *   <li>No reactive types</li>
 *   <li>No logging</li>
 *   <li>No state</li>
 *   <li>No Spring dependency</li>
 * </ul>
 */
public final class ConsensusIntegrationGuard {

    /** Fallback returned when results are null or empty — safe default for downstream. */
    private static final ConsensusResult FALLBACK =
            new ConsensusResult("HOLD", 0.0, Map.of());

    private ConsensusIntegrationGuard() {}

    /**
     * Delegates to {@code engine.compute(results)} after null/empty guard.
     *
     * <p>If {@code results} is {@code null} or empty, returns a safe fallback
     * ({@code HOLD} signal, 0.0 confidence, empty weights) without invoking the engine.
     *
     * @param results the agent analysis outputs (may be null or empty)
     * @param engine  the consensus engine to delegate to when results are valid
     * @return a valid {@link ConsensusResult} — never {@code null}
     */
    public static ConsensusResult resolve(List<AnalysisResult> results, ConsensusEngine engine) {
        if (results == null || results.isEmpty()) {
            return FALLBACK;
        }
        return engine.compute(results);
    }

    /**
     * Delegates to {@code engine.compute(results, weights)} after null/empty guard.
     * Passes caller-supplied adaptive weights to implementations that support them.
     *
     * @param results the agent analysis outputs (may be null or empty)
     * @param engine  the consensus engine to delegate to when results are valid
     * @param weights adaptive weight per agentName from {@code AgentScoreCalculator}
     * @return a valid {@link ConsensusResult} — never {@code null}
     */
    public static ConsensusResult resolve(List<AnalysisResult> results, ConsensusEngine engine,
                                          Map<String, Double> weights) {
        if (results == null || results.isEmpty()) {
            return FALLBACK;
        }
        return engine.compute(results, weights);
    }
}
