package com.agentplatform.common.cognition;

/**
 * Aggregate directional market bias computed by TrendAgent via a 5-factor vote.
 *
 * <p>Score mapping (net bullish votes minus bearish votes, range −5 to +5):
 * <ul>
 *   <li>≥ 3  → STRONG_BULLISH</li>
 *   <li>1–2  → BULLISH</li>
 *   <li>0    → NEUTRAL</li>
 *   <li>−1 to −2 → BEARISH</li>
 *   <li>≤ −3 → STRONG_BEARISH</li>
 * </ul>
 *
 * <p>Phase-33 addition — stored in TrendAgent metadata and propagated through
 * {@link com.agentplatform.common.model.DecisionContext} to the AI prompt.
 */
public enum DirectionalBias {
    STRONG_BULLISH,
    BULLISH,
    NEUTRAL,
    BEARISH,
    STRONG_BEARISH;

    /** True when bias supports LONG (BUY) entries. */
    public boolean isLongBias() {
        return this == BULLISH || this == STRONG_BULLISH;
    }

    /** True when bias supports SHORT (SELL) entries. */
    public boolean isShortBias() {
        return this == BEARISH || this == STRONG_BEARISH;
    }
}
