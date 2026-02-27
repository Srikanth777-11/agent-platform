package com.agentplatform.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Snapshot of a single agent's historical performance used by
 * {@link com.agentplatform.common.consensus.AgentScoreCalculator} to derive
 * adaptive consensus weights.
 *
 * <ul>
 *   <li>{@code historicalAccuracyScore} – average confidence score across all
 *       stored decisions this agent participated in ([0.0, 1.0]).</li>
 *   <li>{@code latencyWeight} – normalized latency penalty for this agent
 *       ([0.0, 1.0]; 1.0 = slowest agent observed).</li>
 *   <li>{@code totalDecisions} – how many persisted decisions contributed to
 *       these statistics.</li>
 * </ul>
 */
public record AgentPerformanceModel(
    @JsonProperty("agentName")               String agentName,
    @JsonProperty("historicalAccuracyScore") double historicalAccuracyScore,
    @JsonProperty("latencyWeight")           double latencyWeight,
    @JsonProperty("totalDecisions")          long   totalDecisions
) {}
