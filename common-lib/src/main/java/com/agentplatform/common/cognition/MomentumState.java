package com.agentplatform.common.cognition;

/**
 * Phase-40: Per-candle price momentum direction, derived from the last 3 candle closes.
 *
 * <p>Distinct from {@link com.agentplatform.common.model.MarketState} (which classifies
 * historical momentum from multiple decisions). {@code MomentumState} is a real-time
 * per-cycle snapshot used by {@link DecisionPipelineEngine} to block OPENING_PHASE_3
 * entries when momentum is decelerating.
 *
 * <h3>Classification (prices[0] = most recent)</h3>
 * <ul>
 *   <li>{@link #RISING}    — prices[0] > prices[1] > prices[2]: consecutive upward movement</li>
 *   <li>{@link #WEAKENING} — prices[0] > prices[1] but prices[1] ≤ prices[2]: deceleration</li>
 *   <li>{@link #FALLING}   — prices[0] < prices[1]: downward movement</li>
 *   <li>{@link #UNKNOWN}   — fewer than 3 price points available</li>
 * </ul>
 */
public enum MomentumState {
    RISING,
    WEAKENING,
    FALLING,
    UNKNOWN
}
