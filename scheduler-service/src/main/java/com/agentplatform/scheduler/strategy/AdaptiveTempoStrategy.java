package com.agentplatform.scheduler.strategy;

import com.agentplatform.common.cognition.TradingSession;
import com.agentplatform.common.cognition.TradingSessionClassifier;
import com.agentplatform.common.model.MarketRegime;

import java.time.Duration;
import java.time.Instant;

/**
 * Phase-7 regime-aware + Phase-25 session-aware tempo strategy.
 *
 * <p>Session gates override regime intervals:
 * <ul>
 *   <li>OFF_HOURS            — pause 30 min (no signals needed, saves API quota)</li>
 *   <li>MIDDAY_CONSOLIDATION — fixed 15 min (maintain state, suppress BUY/SELL via AI)</li>
 *   <li>OPENING_PHASE_1/2/3  — regime-driven (30s–2min) [Phase-40 micro-sessions]</li>
 *   <li>OPENING_BURST        — regime-driven (30s–2min) [backward compat alias]</li>
 *   <li>POWER_HOUR           — regime-driven (30s–2min)</li>
 * </ul>
 */
public final class AdaptiveTempoStrategy {

    public static final Duration FALLBACK_INTERVAL  = Duration.ofMinutes(5);
    public static final Duration OFF_HOURS_INTERVAL = Duration.ofMinutes(30);
    public static final Duration MIDDAY_INTERVAL    = Duration.ofMinutes(15);

    private AdaptiveTempoStrategy() {}

    /**
     * Session-aware resolution — primary entry point from Phase-25 onward.
     * Active scalping windows use regime-driven interval.
     * Inactive windows use fixed slow intervals.
     */
    public static Duration resolve(MarketRegime regime, Instant now) {
        TradingSession session = TradingSessionClassifier.classify(now);
        return switch (session) {
            case OFF_HOURS            -> OFF_HOURS_INTERVAL;
            case MIDDAY_CONSOLIDATION -> MIDDAY_INTERVAL;
            // Phase-40 micro-sessions + legacy alias + POWER_HOUR: all regime-driven
            case OPENING_PHASE_1, OPENING_PHASE_2, OPENING_PHASE_3,
                 OPENING_BURST, POWER_HOUR -> resolveByRegime(regime);
        };
    }

    /** Backwards-compatible regime-only resolution (no session awareness). */
    public static Duration resolve(MarketRegime regime) {
        return resolveByRegime(regime);
    }

    private static Duration resolveByRegime(MarketRegime regime) {
        return switch (regime) {
            case CALM     -> Duration.ofMinutes(10);
            case RANGING  -> Duration.ofMinutes(5);
            case TRENDING -> Duration.ofMinutes(2);
            case VOLATILE -> Duration.ofSeconds(30);
            case UNKNOWN  -> FALLBACK_INTERVAL;
        };
    }
}
