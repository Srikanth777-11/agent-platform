package com.agentplatform.common.consensus;

import com.agentplatform.common.model.AnalysisResult;

import java.util.List;

/**
 * Strategy contract for computing a consensus trading signal from a list of agent results.
 *
 * <p>Implementations must be:
 * <ul>
 *   <li><b>Stateless</b> — no mutable state; safe to call concurrently</li>
 *   <li><b>Pure</b>      — no logging, no reactive types, no side effects</li>
 *   <li><b>Non-null</b>  — must always return a valid {@link ConsensusResult}</li>
 * </ul>
 *
 * <p>Current implementation: {@link DefaultWeightedConsensusStrategy} (equal agent weights).
 *
 * <p>TODO: Future implementations may use:
 * <ul>
 *   <li>Historical performance-based dynamic weights (PerformanceWeightedConsensusStrategy)</li>
 *   <li>Confidence-scaled weights (ConfidenceScaledConsensusStrategy)</li>
 *   <li>Adaptive reinforcement weights learned from decision outcomes</li>
 * </ul>
 * Register as a Spring {@code @Bean} in {@code OrchestratorConfig} to swap strategies
 * without changing any downstream code.
 */
public interface ConsensusEngine {

    /**
     * Compute a consensus result from the provided agent analysis outputs.
     *
     * @param results non-null, non-empty list of agent results
     * @return a {@link ConsensusResult} — never {@code null}
     */
    ConsensusResult compute(List<AnalysisResult> results);
}
