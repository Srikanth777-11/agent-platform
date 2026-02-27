package com.agentplatform.common.cognition;

/**
 * Pure interpreter — derives operator {@link CalmMood} from existing
 * Phase-18/19 cognition signals.
 *
 * <p>No external calls. No Spring dependency. No numeric index exposed.
 * Maps four internal signals → one calm operator state.
 *
 * <h3>Derivation logic</h3>
 * <pre>
 * CALM      — ALIGNED reflection + non-destabilizing trajectory
 *             + pressure below threshold + consensus cooling/stable
 *
 * PRESSURED — UNSTABLE reflection OR (DESTABILIZING + RISING combination)
 *             OR pressure exceeds ceiling
 *
 * BALANCED  — all other signal combinations (mixed or inconclusive)
 * </pre>
 */
public final class CalmMoodInterpreter {

    private static final double CALM_PRESSURE_CEILING    = 0.40;
    private static final double PRESSURED_PRESSURE_FLOOR = 0.65;

    private CalmMoodInterpreter() {}

    public static CalmMood interpret(Double stabilityPressure,
                                     CalmTrajectory calmTrajectory,
                                     DivergenceTrajectory divergenceTrajectory,
                                     ReflectionState reflectionState) {
        double pressure = stabilityPressure != null ? stabilityPressure : 0.5;

        // ── PRESSURED: any severe combined signal ─────────────────────────────
        if (reflectionState == ReflectionState.UNSTABLE)                        return CalmMood.PRESSURED;
        if (calmTrajectory == CalmTrajectory.DESTABILIZING
                && divergenceTrajectory == DivergenceTrajectory.RISING)         return CalmMood.PRESSURED;
        if (pressure > PRESSURED_PRESSURE_FLOOR)                                return CalmMood.PRESSURED;

        // ── CALM: all indicators pointing toward stability ────────────────────
        if (reflectionState == ReflectionState.ALIGNED
                && isNonDestabilizing(calmTrajectory)
                && isConverging(divergenceTrajectory)
                && pressure < CALM_PRESSURE_CEILING) {
            return CalmMood.CALM;
        }

        // ── BALANCED: mixed or inconclusive signals ───────────────────────────
        return CalmMood.BALANCED;
    }

    // ── predicates ───────────────────────────────────────────────────────────

    private static boolean isNonDestabilizing(CalmTrajectory t) {
        return t == null
            || t == CalmTrajectory.STABILIZING
            || t == CalmTrajectory.NEUTRAL;
    }

    private static boolean isConverging(DivergenceTrajectory t) {
        return t == null
            || t == DivergenceTrajectory.COOLING
            || t == DivergenceTrajectory.STABLE;
    }
}
