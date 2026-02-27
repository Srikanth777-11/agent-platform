package com.agentplatform.common.model;

/**
 * Detected market condition used by the orchestration pipeline to apply
 * regime-aware weight boosts inside {@link com.agentplatform.common.consensus.AgentScoreCalculator}.
 */
public enum MarketRegime {
    TRENDING,
    RANGING,
    VOLATILE,
    CALM,
    UNKNOWN
}
