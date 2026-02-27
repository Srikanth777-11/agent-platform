package com.agentplatform.common.consensus;

import com.agentplatform.common.model.AgentCapability;
import com.agentplatform.common.model.AgentFeedback;
import com.agentplatform.common.model.AgentPerformanceModel;
import com.agentplatform.common.model.AnalysisResult;
import com.agentplatform.common.model.MarketRegime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateless calculator that converts {@link AgentPerformanceModel} and optional
 * {@link AgentFeedback} data into per-agent adaptive weights for the consensus pipeline.
 *
 * <p><b>Base formula</b> (per agent):
 * <pre>
 *   adaptiveWeight = (historicalAccuracyScore × 0.5) − (latencyWeight × 0.2)
 *   adaptiveWeight = max(adaptiveWeight, 0.1)
 * </pre>
 *
 * <p><b>Feedback boost</b> (applied when {@link AgentFeedback} is available):
 * <pre>
 *   feedbackBoost  = (winRate × 0.4) + (avgConfidence × 0.3) − (avgLatencyMsNormalized × 0.2)
 *   adaptiveWeight = clamp(adaptiveWeight + feedbackBoost, 0.1, 2.0)
 * </pre>
 *
 * <p><b>Regime boost</b> (applied on top of feedback weight when {@link MarketRegime} is known):
 * <pre>
 *   TRENDING  → TrendAgent     +0.20
 *   VOLATILE  → RiskAgent      +0.20
 *   RANGING   → PortfolioAgent +0.15
 *   CALM      → no boost
 *   UNKNOWN   → no boost
 * </pre>
 * Agent matching uses {@link AgentCapability} enum for explicit, type-safe lookup.
 *
 * <p>{@code avgLatencyMsNormalized} is each agent's average latency divided by the
 * maximum average latency observed across all agents in the current feedback map,
 * giving a value in [0.0, 1.0].
 *
 * <p>If no performance record or no feedback exists for an agent, each missing
 * component falls back gracefully ({@value FALLBACK_WEIGHT} for missing performance;
 * no boost applied for missing feedback).
 */
public final class AgentScoreCalculator {

    static final double ACCURACY_COEFF  = 0.5;
    static final double LATENCY_COEFF   = 0.2;
    static final double MIN_WEIGHT      = 0.1;
    static final double MAX_WEIGHT      = 2.0;
    static final double FALLBACK_WEIGHT = 1.0;

    // Feedback boost coefficients
    static final double WIN_RATE_COEFF     = 0.4;
    static final double FB_CONF_COEFF      = 0.3;
    static final double FB_LATENCY_COEFF   = 0.2;

    private AgentScoreCalculator() {}

    /**
     * Compute adaptive weights using performance data only (no feedback, no regime).
     * Retained for callers that do not yet supply feedback.
     *
     * @param results           analysis results produced this cycle
     * @param performanceModels per-agent historical data (may be empty)
     * @return map of agentName → adaptiveWeight
     */
    public static Map<String, Double> compute(
            List<AnalysisResult> results,
            Map<String, AgentPerformanceModel> performanceModels) {
        return compute(results, performanceModels, Map.of(), MarketRegime.UNKNOWN);
    }

    /**
     * Compute adaptive weights with optional feedback boost and no regime influence.
     * Delegates to the full 4-arg overload with {@link MarketRegime#UNKNOWN}.
     *
     * @param results           analysis results produced this cycle
     * @param performanceModels per-agent historical data (may be empty)
     * @param feedbackMap       per-agent feedback derived from stored outcomes (may be empty)
     * @return map of agentName → adaptiveWeight; every agent is guaranteed an entry
     */
    public static Map<String, Double> compute(
            List<AnalysisResult> results,
            Map<String, AgentPerformanceModel> performanceModels,
            Map<String, AgentFeedback> feedbackMap) {
        return compute(results, performanceModels, feedbackMap, MarketRegime.UNKNOWN);
    }

    /**
     * Full computation: performance base + feedback boost + regime influence.
     *
     * @param results           analysis results produced this cycle
     * @param performanceModels per-agent historical data (may be empty)
     * @param feedbackMap       per-agent feedback (may be empty)
     * @param regime            detected market regime ({@link MarketRegime#UNKNOWN} = no boost)
     * @return map of agentName → adaptiveWeight; every agent is guaranteed an entry
     */
    public static Map<String, Double> compute(
            List<AnalysisResult> results,
            Map<String, AgentPerformanceModel> performanceModels,
            Map<String, AgentFeedback> feedbackMap,
            MarketRegime regime) {

        // Pre-compute max avgLatencyMs across the feedback map for normalization
        double maxAvgLatencyMs = feedbackMap.values().stream()
            .mapToDouble(AgentFeedback::avgLatencyMs)
            .max()
            .orElse(1.0);
        if (maxAvgLatencyMs <= 0.0) maxAvgLatencyMs = 1.0;

        Map<String, Double> weights = new HashMap<>();

        for (AnalysisResult result : results) {
            // ── base weight from performance model ────────────────────────────
            AgentPerformanceModel model = performanceModels.get(result.agentName());
            double weight;
            if (model == null) {
                weight = FALLBACK_WEIGHT;
            } else {
                weight = (model.historicalAccuracyScore() * ACCURACY_COEFF)
                         - (model.latencyWeight() * LATENCY_COEFF);
                weight = Math.max(weight, MIN_WEIGHT);
            }

            // ── feedback boost (additive, only when data is present) ──────────
            AgentFeedback feedback = feedbackMap.get(result.agentName());
            if (feedback != null) {
                double normalizedLatency = feedback.avgLatencyMs() / maxAvgLatencyMs;
                double feedbackBoost = (feedback.winRate()       * WIN_RATE_COEFF)
                                     + (feedback.avgConfidence() * FB_CONF_COEFF)
                                     - (normalizedLatency        * FB_LATENCY_COEFF);
                weight = weight + feedbackBoost;
            }

            // ── regime boost ─────────────────────────────────────────────────
            weight = weight + regimeBoost(result.agentName(), regime);

            // ── clamp to [MIN_WEIGHT, MAX_WEIGHT] ────────────────────────────
            weight = Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, weight));
            weights.put(result.agentName(), weight);
        }

        return weights;
    }

    /**
     * Returns the regime-specific additive boost for a single agent.
     * Uses {@link AgentCapability} enum for explicit capability-based matching.
     */
    private static double regimeBoost(String agentName, MarketRegime regime) {
        if (regime == null || regime == MarketRegime.UNKNOWN || regime == MarketRegime.CALM) {
            return 0.0;
        }
        AgentCapability capability = AgentCapability.fromAgentName(agentName);
        if (capability == null) {
            return 0.0;
        }
        return switch (regime) {
            case TRENDING -> capability == AgentCapability.TREND     ? 0.20 : 0.0;
            case VOLATILE -> capability == AgentCapability.RISK      ? 0.20 : 0.0;
            case RANGING  -> capability == AgentCapability.PORTFOLIO ? 0.15 : 0.0;
            default       -> 0.0;
        };
    }
}
