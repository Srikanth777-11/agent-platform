package com.agentplatform.common.posture;

import com.agentplatform.common.model.ExitAwareness;
import com.agentplatform.common.risk.AdaptiveRiskEngine;
import com.agentplatform.common.structure.StructureSignal;

import java.time.Duration;
import java.time.Instant;

/**
 * Pure interpreter — derives a single {@link TradePosture} label from
 * existing signals with stability window suppression.
 */
public final class TradePostureInterpreter {

    private static final long STABILITY_WINDOW_SECONDS = 60;

    public static TradePosture evaluate(AdaptiveRiskEngine.MomentumInput momentum,
                                        StructureSignal structure,
                                        ExitAwareness awareness) {
        return computeTarget(momentum, structure, awareness);
    }

    /**
     * Stability-aware evaluation. Suppresses downgrades in severity within
     * the stability window — only allows same-or-higher severity through.
     *
     * @param previous null on first call
     * @param now      current instant
     * @return resolved posture and updated stability state
     */
    public static StabilityResult evaluateStable(AdaptiveRiskEngine.MomentumInput momentum,
                                                  StructureSignal structure,
                                                  ExitAwareness awareness,
                                                  PostureStabilityState previous,
                                                  Instant now) {
        TradePosture target = computeTarget(momentum, structure, awareness);

        if (previous != null) {
            long elapsed = Duration.between(previous.lastUpdated(), now).getSeconds();
            if (elapsed < STABILITY_WINDOW_SECONDS
                    && target.ordinal() < previous.lastPosture().ordinal()) {
                // Within window and target is lower severity — hold previous
                return new StabilityResult(previous.lastPosture(), previous);
            }
        }

        PostureStabilityState newState = new PostureStabilityState(target, now);
        return new StabilityResult(target, newState);
    }

    public record StabilityResult(
        TradePosture         posture,
        PostureStabilityState state
    ) {}

    private static TradePosture computeTarget(AdaptiveRiskEngine.MomentumInput momentum,
                                               StructureSignal structure,
                                               ExitAwareness awareness) {
        if ("CONFIRMED".equals(momentum.marketState())
                && structure == StructureSignal.CONTINUATION
                && "FRESH".equals(awareness.durationSignal())) {
            return TradePosture.CONFIDENT_HOLD;
        }

        if (structure == StructureSignal.STALLING) {
            return TradePosture.WATCH_CLOSELY;
        }

        if (structure == StructureSignal.FATIGUED
                || "EXTENDED".equals(awareness.durationSignal())) {
            return TradePosture.DEFENSIVE_HOLD;
        }

        if ("WEAKENING".equals(momentum.marketState())
                && awareness.confidenceDrift() < -0.1) {
            return TradePosture.EXIT_CANDIDATE;
        }

        return TradePosture.WATCH_CLOSELY;
    }
}
