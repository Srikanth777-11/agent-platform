package com.agentplatform.orchestrator.pipeline;

/**
 * Phase-43: Outcome of the DailyRiskGovernor evaluation.
 *
 * <ul>
 *   <li>{@link #ALLOW}       — All conditions normal. Trade allowed.</li>
 *   <li>{@link #REDUCE_SIZE} — 2 consecutive losses. Allow trade but halve position size.</li>
 *   <li>{@link #HALT}        — Kill switch triggered. Force all signals to HOLD for rest of session.</li>
 * </ul>
 */
public enum GovernorDecision {
    ALLOW,
    REDUCE_SIZE,
    HALT
}
