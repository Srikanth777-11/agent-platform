package com.agentplatform.common.structure;

import com.agentplatform.common.risk.AdaptiveRiskEngine;

/**
 * Pure logic class — classifies momentum structure from metrics and state.
 * No dependencies. No side effects.
 */
public final class StructureInterpreter {

    public static StructureSignal evaluate(AdaptiveRiskEngine.MetricsInput metrics,
                                           AdaptiveRiskEngine.MomentumInput momentum) {
        if (metrics.divergenceStreak() >= 2 && metrics.confidenceSlope5() < -0.02) {
            return StructureSignal.FATIGUED;
        }
        if (metrics.confidenceSlope5() >= -0.02 && metrics.confidenceSlope5() <= 0.02) {
            return StructureSignal.STALLING;
        }
        return StructureSignal.CONTINUATION;
    }
}
