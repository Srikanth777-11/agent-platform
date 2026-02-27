package com.agentplatform.common.consensus;

import java.util.Map;

/**
 * Immutable output of a {@link ConsensusEngine} run.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code finalSignal}          — consensus trading signal: BUY / SELL / HOLD / WATCH</li>
 *   <li>{@code normalizedConfidence} — weighted signal strength mapped to [0.0, 1.0]</li>
 *   <li>{@code agentWeights}         — snapshot of per-agent weights used in this computation</li>
 * </ul>
 *
 * <p>This record is pure data — no reactive types, no Spring dependencies.
 */
public record ConsensusResult(
    String finalSignal,
    double normalizedConfidence,
    Map<String, Double> agentWeights
) {}
