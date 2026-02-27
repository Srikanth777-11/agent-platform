package com.agentplatform.common.cognition;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Phase-22 pure stateless classifier — maps a UTC {@link Instant} to a
 * {@link TradingSession} based on NSE/BSE market hours (IST, UTC+5:30).
 *
 * <p>No external calls. No Spring dependency.
 *
 * <h3>Session boundaries (India Standard Time — IST)</h3>
 * <pre>
 *   OPENING_BURST        09:15 – 10:15  (NSE open + first hour momentum)
 *   MIDDAY_CONSOLIDATION 10:15 – 14:30  (low-volatility dead zone)
 *   POWER_HOUR           14:30 – 15:30  (institutional close activity)
 *   OFF_HOURS            15:30 – 09:15  (next day), weekends
 * </pre>
 */
public final class TradingSessionClassifier {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final LocalTime OPEN_START   = LocalTime.of(9,  15);
    private static final LocalTime MIDDAY_START = LocalTime.of(10, 15);
    private static final LocalTime POWER_START  = LocalTime.of(14, 30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    private TradingSessionClassifier() {}

    /**
     * Classifies the intraday session for the given UTC instant.
     *
     * @param now UTC timestamp of the event trigger
     * @return {@link TradingSession} — never null
     */
    public static TradingSession classify(Instant now) {
        ZonedDateTime ist = now.atZone(IST);
        int dow = ist.getDayOfWeek().getValue(); // 1=Monday … 7=Sunday
        if (dow >= 6) return TradingSession.OFF_HOURS; // weekend

        LocalTime time = ist.toLocalTime();
        if (time.isBefore(OPEN_START))   return TradingSession.OFF_HOURS;
        if (time.isBefore(MIDDAY_START)) return TradingSession.OPENING_BURST;
        if (time.isBefore(POWER_START))  return TradingSession.MIDDAY_CONSOLIDATION;
        if (time.isBefore(MARKET_CLOSE)) return TradingSession.POWER_HOUR;
        return TradingSession.OFF_HOURS;
    }
}
