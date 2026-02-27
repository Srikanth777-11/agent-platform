package com.agentplatform.common.model;

/**
 * High-level momentum stability classification derived from recent decision history.
 *
 * <p>This is a <strong>read-only cognitive interpretation</strong> — not a trading signal,
 * not a prediction, and not part of the orchestration pipeline. It exists purely to
 * help a human operator gauge whether market momentum has stabilised enough to
 * warrant attention.
 *
 * <h3>State definitions</h3>
 * <ul>
 *   <li>{@link #CALM}       — Mixed or weak signals. Confidence unstable. No clear direction.</li>
 *   <li>{@link #BUILDING}   — Confidence gradually rising. Signal alignment improving.
 *                              Momentum forming but not yet confirmed.</li>
 *   <li>{@link #CONFIRMED}  — Multiple consecutive aligned signals. Low divergence.
 *                              Stable or rising confidence trend. Momentum suitable
 *                              for operator attention.</li>
 *   <li>{@link #WEAKENING}  — Momentum losing stability. Confidence declining or
 *                              divergence increasing after a period of alignment.</li>
 * </ul>
 *
 * <h3>Architectural role</h3>
 * <p>Computed by {@code MomentumStateCalculator} (pure stateless logic in {@code common-lib}).
 * Consumed by {@code history-service} via a read-only projection endpoint and rendered
 * in the UI as a calm informational banner.
 */
public enum MarketState {

    /** Mixed or weak signals. Confidence unstable. */
    CALM,

    /** Confidence rising. Alignment improving. Momentum forming but not confirmed. */
    BUILDING,

    /** Consecutive aligned signals. Low divergence. Stable confidence. Operator attention warranted. */
    CONFIRMED,

    /** Momentum losing stability or confidence declining. */
    WEAKENING
}
