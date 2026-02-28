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
 * <h3>Session boundaries (India Standard Time — IST) — Phase-40 micro-sessions</h3>
 * <pre>
 *   OPENING_PHASE_1      09:15 – 09:25  (price discovery — strict gates)
 *   OPENING_PHASE_2      09:25 – 09:40  (directional expansion — prime scalping window)
 *   OPENING_PHASE_3      09:40 – 10:15  (continuation or trap — momentum gated)
 *   MIDDAY_CONSOLIDATION 10:15 – 14:30  (low-volatility dead zone)
 *   POWER_HOUR           14:30 – 15:30  (institutional close activity)
 *   OFF_HOURS            15:30 – 09:15  (next day), weekends
 * </pre>
 */
public final class TradingSessionClassifier {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final LocalTime OPEN_START      = LocalTime.of(9,  15);
    private static final LocalTime PHASE2_START    = LocalTime.of(9,  25); // Phase-40
    private static final LocalTime PHASE3_START    = LocalTime.of(9,  40); // Phase-40
    private static final LocalTime MIDDAY_START    = LocalTime.of(10, 15);
    private static final LocalTime POWER_START     = LocalTime.of(14, 30);
    private static final LocalTime MARKET_CLOSE    = LocalTime.of(15, 30);

    private TradingSessionClassifier() {}

    /**
     * Classifies the intraday session for the given UTC instant.
     * Phase-40: OPENING_BURST is now sub-divided into three micro-sessions.
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
        if (time.isBefore(PHASE2_START)) return TradingSession.OPENING_PHASE_1;  // 09:15–09:25
        if (time.isBefore(PHASE3_START)) return TradingSession.OPENING_PHASE_2;  // 09:25–09:40
        if (time.isBefore(MIDDAY_START)) return TradingSession.OPENING_PHASE_3;  // 09:40–10:15
        if (time.isBefore(POWER_START))  return TradingSession.MIDDAY_CONSOLIDATION;
        if (time.isBefore(MARKET_CLOSE)) return TradingSession.POWER_HOUR;
        return TradingSession.OFF_HOURS;
    }
}
