package com.agentplatform.common.risk;

import com.agentplatform.common.cognition.DirectionalBias;
import com.agentplatform.common.cognition.TradingSession;
import com.agentplatform.common.model.MarketRegime;

/**
 * Phase-42: Risk-adjusted position sizing engine.
 *
 * <p>Converts a binary trade signal into a risk-scaled position size recommendation.
 * The operator uses this guidance to set lot size before manual execution.
 * No automation — read-only intelligence layer.
 *
 * <h3>5-factor formula (all factors multiply base risk)</h3>
 * <pre>
 *   base risk = 1% capital
 *   × confidenceFactor   (0.65–0.90 → proportional)
 *   × regimeFactor       (VOLATILE:1.0, TRENDING:0.9, RANGING:0.6, CALM:0.3)
 *   × divergenceFactor   (divergenceFlag → 0.6, else 1.0)
 *   × edgeFactor         (winRate >0.60 → 1.2, <0.52 → 0.5, else 1.0)
 *   × microSessionFactor (PHASE_1:0.5, PHASE_2:1.0, PHASE_3:0.7, POWER_HOUR:0.8, else 0.5)
 *   = positionRisk %
 *   lotMultiplier = positionRisk / stopLossPercent (clamped to [0.25, 2.0])
 * </pre>
 */
public final class PositionSizingEngine {

    private static final double BASE_RISK_PERCENT = 1.0; // 1% of capital per trade

    private PositionSizingEngine() {}

    /**
     * Computes the position sizing decision for the given trade parameters.
     *
     * @param confidence    AI confidence score (0.0–1.0)
     * @param regime        classified market regime
     * @param directionalBias 5-vote directional bias from TrendAgent
     * @param divergenceFlag  true if AI and consensus disagree
     * @param edgeWinRate     win rate from edge_conditions registry (0.0 if unknown)
     * @param session         current trading micro-session
     * @param governorMultiplier  1.0 = normal, 0.5 = REDUCE_SIZE from DailyRiskGovernor
     * @return {@link PositionSizingDecision} — never null
     */
    public static PositionSizingDecision compute(double confidence, MarketRegime regime,
                                                  DirectionalBias directionalBias,
                                                  boolean divergenceFlag, double edgeWinRate,
                                                  TradingSession session,
                                                  double governorMultiplier) {
        // Factor 1: Confidence (proportional, floor at 0.65)
        double confidenceFactor = Math.max(0.65, Math.min(confidence, 0.90));

        // Factor 2: Regime
        double regimeFactor = switch (regime != null ? regime : MarketRegime.UNKNOWN) {
            case VOLATILE  -> 1.0;
            case TRENDING  -> 0.9;
            case RANGING   -> 0.6;
            case CALM      -> 0.3;
            default        -> 0.5;
        };

        // Factor 3: Divergence penalty
        double divergenceFactor = divergenceFlag ? 0.6 : 1.0;

        // Factor 4: Edge quality from WinConditionRegistry
        double edgeFactor;
        if (edgeWinRate > 0.60)      edgeFactor = 1.2;
        else if (edgeWinRate < 0.52) edgeFactor = 0.5;
        else                          edgeFactor = 1.0;
        // If no edge data yet (winRate = 0.0), treat as neutral
        if (edgeWinRate == 0.0) edgeFactor = 1.0;

        // Factor 5: Micro-session risk (Phase-40 sessions)
        double sessionFactor = switch (session != null ? session : TradingSession.OFF_HOURS) {
            case OPENING_PHASE_1 -> 0.5;  // Price discovery: half size
            case OPENING_PHASE_2 -> 1.0;  // Prime window: full size
            case OPENING_PHASE_3 -> 0.7;  // Continuation/trap: reduced
            case POWER_HOUR      -> 0.8;  // Institutional close: slightly reduced
            case OPENING_BURST   -> 0.8;  // Legacy compat
            default              -> 0.5;  // MIDDAY / OFF_HOURS: minimal (should not reach here)
        };

        // Governor multiplier (1.0 = ALLOW, 0.5 = REDUCE_SIZE from DailyRiskGovernor)
        double safeGovernor = (governorMultiplier > 0 && governorMultiplier <= 1.0) ? governorMultiplier : 1.0;

        double positionRisk = BASE_RISK_PERCENT
            * confidenceFactor
            * regimeFactor
            * divergenceFactor
            * edgeFactor
            * sessionFactor
            * safeGovernor;

        // Clamp to [0.1, 1.5] % of capital
        positionRisk = Math.max(0.1, Math.min(positionRisk, 1.5));

        // LotMultiplier relative to base (positionRisk / 1.0 base)
        double lotMultiplier = Math.max(0.25, Math.min(positionRisk / BASE_RISK_PERCENT, 2.0));

        String reasoning = String.format(
            "conf=%.2f regime=%s div=%b edge=%.2f session=%s gov=%.1f → risk=%.3f%% lot=%.2fx",
            confidenceFactor, regime, divergenceFlag, edgeWinRate, session, safeGovernor,
            positionRisk, lotMultiplier);

        return new PositionSizingDecision(positionRisk, lotMultiplier, reasoning);
    }
}
