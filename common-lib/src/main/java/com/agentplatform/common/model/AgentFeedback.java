package com.agentplatform.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Feedback snapshot for a single agent derived from historical decision outcomes.
 *
 * <ul>
 *   <li>{@code winRate}       – fraction of decisions where the agent's signal
 *       matched the consensus finalSignal ([0.0, 1.0]).</li>
 *   <li>{@code avgConfidence} – mean confidenceScore across all decisions
 *       this agent participated in ([0.0, 1.0]).</li>
 *   <li>{@code avgLatencyMs}  – mean decision-level latency (ms) recorded for
 *       decisions this agent contributed to.</li>
 *   <li>{@code totalDecisions} – number of persisted decisions that count
 *       toward these statistics.</li>
 * </ul>
 *
 * No logic — pure model.
 */
public record AgentFeedback(
    @JsonProperty("agentName")       String agentName,
    @JsonProperty("winRate")         double winRate,
    @JsonProperty("avgConfidence")   double avgConfidence,
    @JsonProperty("avgLatencyMs")    double avgLatencyMs,
    @JsonProperty("totalDecisions")  long   totalDecisions
) {}
