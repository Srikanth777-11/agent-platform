package com.agentplatform.common.cognition.reflection;

import com.agentplatform.common.cognition.CalmMood;
import com.agentplatform.common.cognition.CalmMoodInterpreter;
import com.agentplatform.common.cognition.CalmTrajectory;
import com.agentplatform.common.cognition.DivergenceTrajectory;
import com.agentplatform.common.cognition.ReflectionPersistence;
import com.agentplatform.common.cognition.ReflectionPersistenceCalculator;
import com.agentplatform.common.cognition.ReflectionState;
import com.agentplatform.common.model.AnalysisResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure derived computation — maps current pipeline signals to a {@link ReflectionResult}
 * containing {@link ReflectionState}, {@link CalmMood}, and {@link ReflectionPersistence}.
 *
 * <p>CalmMood is derived here — inside the cognition layer — so no separate
 * orchestration step is needed. A single {@code interpret()} call produces
 * all three signals in one pass.
 *
 * <p>ReflectionPersistence (Phase-21) tracks the rolling window of the last 5
 * ReflectionState values per symbol — distinguishing momentary vs persistent instability.
 *
 * <p>No external calls. No async. No Spring dependency.
 *
 * <h3>Mapping rules</h3>
 * <ul>
 *   <li>UNSTABLE   — signalVariance > 0.60, or pressureLevel > 0.70,
 *                    or (DESTABILIZING + RISING)</li>
 *   <li>ALIGNED    — signalVariance < 0.25 and pressureLevel < 0.35
 *                    and trajectoryFlipRate ≤ 0.25</li>
 *   <li>DRIFTING   — all other cases</li>
 * </ul>
 */
public final class ArchitectReflectionInterpreter {

    private static final double UNSTABLE_VARIANCE_THRESHOLD  = 0.60;
    private static final double UNSTABLE_PRESSURE_THRESHOLD  = 0.70;
    private static final double ALIGNED_VARIANCE_CEILING     = 0.25;
    private static final double ALIGNED_PRESSURE_CEILING     = 0.35;
    private static final double ALIGNED_FLIP_CEILING         = 0.25;

    private ArchitectReflectionInterpreter() {}

    /**
     * Carries all three derived signals from a single interpretation pass.
     * Phase-21: includes {@link ReflectionPersistence} from the per-symbol rolling window.
     */
    public record ReflectionResult(ReflectionState reflectionState,
                                   CalmMood calmMood,
                                   ReflectionPersistence reflectionPersistence) {}

    /**
     * Symbol-keyed overload — preferred from Phase-21 onward.
     * Feeds the per-symbol rolling window in {@link ReflectionPersistenceCalculator}.
     */
    public static ReflectionResult interpret(List<AnalysisResult> results,
                                             Double stabilityPressure,
                                             CalmTrajectory calmTrajectory,
                                             DivergenceTrajectory divergenceTrajectory,
                                             String symbol) {
        double signalVariance = computeSignalVariance(results);
        double pressureLevel  = stabilityPressure != null ? stabilityPressure : 0.5;
        double flipRate       = computeTrajectoryFlipRate(calmTrajectory, divergenceTrajectory);

        ReflectionState reflectionState;

        // UNSTABLE — any severe indicator
        if (signalVariance > UNSTABLE_VARIANCE_THRESHOLD
                || pressureLevel > UNSTABLE_PRESSURE_THRESHOLD
                || flipRate >= 2.0) {
            reflectionState = ReflectionState.UNSTABLE;

        // ALIGNED — all indicators calm
        } else if (signalVariance < ALIGNED_VARIANCE_CEILING
                && pressureLevel < ALIGNED_PRESSURE_CEILING
                && flipRate <= ALIGNED_FLIP_CEILING) {
            reflectionState = ReflectionState.ALIGNED;

        } else {
            reflectionState = ReflectionState.DRIFTING;
        }

        // CalmMood derived in same pass — no extra orchestration step
        CalmMood calmMood = CalmMoodInterpreter.interpret(
            stabilityPressure, calmTrajectory, divergenceTrajectory, reflectionState);

        // ReflectionPersistence from per-symbol rolling window
        ReflectionPersistence persistence =
            ReflectionPersistenceCalculator.compute(symbol, reflectionState);

        return new ReflectionResult(reflectionState, calmMood, persistence);
    }

    /**
     * Backwards-compatible overload — uses a global window key.
     * Prefer the symbol-keyed overload for multi-symbol pipelines.
     */
    public static ReflectionResult interpret(List<AnalysisResult> results,
                                             Double stabilityPressure,
                                             CalmTrajectory calmTrajectory,
                                             DivergenceTrajectory divergenceTrajectory) {
        return interpret(results, stabilityPressure, calmTrajectory, divergenceTrajectory, "_global");
    }

    // ── sub-metric derivations ────────────────────────────────────────────────

    private static double computeSignalVariance(List<AnalysisResult> results) {
        if (results == null || results.isEmpty()) return 0.5;
        Map<String, Long> votes = new HashMap<>();
        for (AnalysisResult r : results) votes.merge(r.signal(), 1L, Long::sum);
        long maxVote = votes.values().stream().mapToLong(Long::longValue).max().orElse(1L);
        return 1.0 - ((double) maxVote / results.size());
    }

    /**
     * Composite instability score derived from trajectory combination.
     *
     * <pre>
     * DESTABILIZING → +1.5   NEUTRAL → +0.5   STABILIZING → 0.0
     * RISING        → +1.0   STABLE  → +0.25  COOLING     → 0.0
     * </pre>
     *
     * Combined score ≥ 2.0 indicates UNSTABLE territory (e.g. DESTABILIZING + RISING = 2.5).
     */
    private static double computeTrajectoryFlipRate(CalmTrajectory calm, DivergenceTrajectory div) {
        double rate = 0.0;
        if (calm != null) {
            rate += switch (calm) {
                case DESTABILIZING -> 1.5;
                case NEUTRAL       -> 0.5;
                case STABILIZING   -> 0.0;
            };
        }
        if (div != null) {
            rate += switch (div) {
                case RISING  -> 1.0;
                case STABLE  -> 0.25;
                case COOLING -> 0.0;
            };
        }
        return rate;
    }
}
