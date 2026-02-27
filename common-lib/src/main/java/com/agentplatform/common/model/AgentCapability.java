package com.agentplatform.common.model;

import java.util.Map;

/**
 * Declares each agent's primary analytical capability.
 * Used by AgentScoreCalculator for regime boost calculation,
 * replacing fragile agent-name substring matching.
 */
public enum AgentCapability {
    TREND,
    RISK,
    PORTFOLIO,
    DISCIPLINE;

    private static final Map<String, AgentCapability> AGENT_MAPPING = Map.of(
        "TrendAgent",      TREND,
        "RiskAgent",       RISK,
        "PortfolioAgent",  PORTFOLIO,
        "DisciplineCoach", DISCIPLINE
    );

    /**
     * Resolves an agent name to its capability.
     * Returns null for unrecognized agents (no regime boost applied).
     */
    public static AgentCapability fromAgentName(String agentName) {
        return AGENT_MAPPING.get(agentName);
    }
}
