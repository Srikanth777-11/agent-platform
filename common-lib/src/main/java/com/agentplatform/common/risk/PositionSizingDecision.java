package com.agentplatform.common.risk;

/**
 * Phase-42: Output of {@link PositionSizingEngine}.
 *
 * <p>Consumed by trade-service (operator layer) as position sizing guidance.
 * No automation — the operator uses this to set lot size before manual execution.
 *
 * @param riskPercent    Recommended risk as % of capital (e.g. 0.65 = risk 0.65% of account)
 * @param lotMultiplier  Multiplier vs base lot size (e.g. 0.5 = half lot, 1.2 = 1.2x lot)
 * @param reasoning      Human-readable factor breakdown for operator transparency
 */
public record PositionSizingDecision(
    double riskPercent,
    double lotMultiplier,
    String reasoning
) {
    /** Default ALLOW decision — no sizing reduction. */
    public static PositionSizingDecision allow(double riskPercent) {
        return new PositionSizingDecision(riskPercent, 1.0, "Normal sizing");
    }

    /** Default REDUCE decision — halved size (post 2 consecutive losses). */
    public static PositionSizingDecision reduced(double riskPercent) {
        return new PositionSizingDecision(riskPercent * 0.5, 0.5, "Reduced: 2 consecutive losses");
    }
}
