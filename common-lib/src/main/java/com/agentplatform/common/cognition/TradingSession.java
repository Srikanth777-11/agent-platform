package com.agentplatform.common.cognition;

/**
 * Phase-22 intraday session classifier — US market hours (Eastern time).
 *
 * <ul>
 *   <li>{@link #OPENING_BURST}        — 09:30–10:30 ET: highest volume, prime scalping window</li>
 *   <li>{@link #MIDDAY_CONSOLIDATION} — 10:30–14:45 ET: choppy dead zone, suppress BUY/SELL</li>
 *   <li>{@link #POWER_HOUR}           — 14:45–16:00 ET: institutional close, second scalping window</li>
 *   <li>{@link #OFF_HOURS}            — 16:00–09:30 ET: market closed, all signals suppressed</li>
 * </ul>
 */
public enum TradingSession {
    OPENING_BURST,
    MIDDAY_CONSOLIDATION,
    POWER_HOUR,
    OFF_HOURS;

    /**
     * Returns {@code true} when the session is a prime scalping window.
     * BUY/SELL signals are only appropriate during active sessions.
     */
    public boolean isActiveScalpingWindow() {
        return this == OPENING_BURST || this == POWER_HOUR;
    }
}
