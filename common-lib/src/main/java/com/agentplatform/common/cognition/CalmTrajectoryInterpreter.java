package com.agentplatform.common.cognition;

import com.agentplatform.common.model.AnalysisResult;

import java.util.List;

/**
 * Derives {@link CalmTrajectory} from computed stability pressure.
 *
 * <p>Thresholds are intentionally conservative — the trajectory should only
 * shift signal bias at the margins, never drive a decision reversal alone.
 */
public final class CalmTrajectoryInterpreter {

    private static final double STABILIZING_CEILING  = 0.30;
    private static final double DESTABILIZING_FLOOR  = 0.60;

    private CalmTrajectoryInterpreter() {}

    public static CalmTrajectory interpret(double stabilityPressure, List<AnalysisResult> results) {
        if (stabilityPressure < STABILIZING_CEILING)  return CalmTrajectory.STABILIZING;
        if (stabilityPressure > DESTABILIZING_FLOOR)  return CalmTrajectory.DESTABILIZING;
        return CalmTrajectory.NEUTRAL;
    }
}
