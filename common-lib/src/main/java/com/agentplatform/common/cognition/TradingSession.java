package com.agentplatform.common.cognition;

/**
 * Phase-22 intraday session classifier — NSE/BSE market hours (IST).
 *
 * <p>Phase-40 added micro-session segmentation for OPENING_BURST:
 * <ul>
 *   <li>{@link #OPENING_PHASE_1}      — 09:15–09:25 IST: price discovery, high noise, strict gates</li>
 *   <li>{@link #OPENING_PHASE_2}      — 09:25–09:40 IST: directional expansion, PRIME SCALPING WINDOW</li>
 *   <li>{@link #OPENING_PHASE_3}      — 09:40–10:15 IST: continuation or trap, momentum-gated</li>
 *   <li>{@link #MIDDAY_CONSOLIDATION} — 10:15–14:30 IST: low-volatility dead zone, WATCH/HOLD only</li>
 *   <li>{@link #POWER_HOUR}           — 14:30–15:30 IST: institutional close, BUY-only window</li>
 *   <li>{@link #OFF_HOURS}            — outside above / weekends: all signals suppressed</li>
 * </ul>
 *
 * <p>{@link #OPENING_BURST} is retained as an alias for backward compatibility with
 * replay data and any code that references it before Phase-40 migration is complete.
 */
public enum TradingSession {
    // Phase-40 micro-sessions (replaces OPENING_BURST)
    OPENING_PHASE_1,       // 09:15–09:25: price discovery — strict confidence + STRONG bias required
    OPENING_PHASE_2,       // 09:25–09:40: expansion — prime scalping window, normal rules
    OPENING_PHASE_3,       // 09:40–10:15: continuation/trap — blocked on WEAKENING momentum

    // Retained sessions
    MIDDAY_CONSOLIDATION,  // 10:15–14:30: WATCH/HOLD only
    POWER_HOUR,            // 14:30–15:30: BUY only
    OFF_HOURS,             // outside market hours

    // Backward-compatibility alias — kept for REPLAY_CONSENSUS_ONLY rows in DB
    OPENING_BURST;

    /**
     * Returns {@code true} when the session is a prime scalping window.
     * BUY/SELL signals are only appropriate during active sessions.
     */
    public boolean isActiveScalpingWindow() {
        return this == OPENING_PHASE_1
            || this == OPENING_PHASE_2
            || this == OPENING_PHASE_3
            || this == POWER_HOUR
            || this == OPENING_BURST; // backward compat
    }

    /**
     * Returns {@code true} if this is any opening phase (1, 2, or 3, or legacy OPENING_BURST).
     */
    public boolean isOpeningPhase() {
        return this == OPENING_PHASE_1
            || this == OPENING_PHASE_2
            || this == OPENING_PHASE_3
            || this == OPENING_BURST;
    }
}
