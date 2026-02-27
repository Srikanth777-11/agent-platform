package com.agentplatform.common.cognition;

/**
 * Phase-21 rolling-window persistence label — derived from the last 5
 * {@link ReflectionState} values per symbol.
 *
 * <ul>
 *   <li>STABLE              — 0 UNSTABLE, ≤1 DRIFTING in window</li>
 *   <li>SOFT_DRIFT          — 0 UNSTABLE, ≥2 DRIFTING in window</li>
 *   <li>HARD_DRIFT          — 1–2 UNSTABLE in window</li>
 *   <li>CHRONIC_INSTABILITY — ≥3 UNSTABLE in window</li>
 * </ul>
 */
public enum ReflectionPersistence {
    STABLE,
    SOFT_DRIFT,
    HARD_DRIFT,
    CHRONIC_INSTABILITY
}
