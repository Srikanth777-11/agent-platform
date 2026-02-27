package com.agentplatform.common.risk;

import com.agentplatform.common.model.ActiveTradeContext;
import com.agentplatform.common.model.AdaptiveRiskState;
import com.agentplatform.common.model.ExitAwareness;
import com.agentplatform.common.model.RiskEnvelope;
import com.agentplatform.common.posture.PostureStabilityState;
import com.agentplatform.common.posture.TradePosture;
import com.agentplatform.common.posture.TradePostureInterpreter;
import com.agentplatform.common.structure.StructureInterpreter;
import com.agentplatform.common.structure.StructureSignal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure logic class — computes exit awareness and risk envelope from
 * trade context, decision metrics, and momentum state.
 *
 * <p>No WebClient. No repositories. No logging. No reactive types.
 */
public final class AdaptiveRiskEngine {

    private static final double BASE_SOFT_STOP_PCT = -0.6;
    private static final double BASE_HARD_STOP_PCT = -1.1;
    private static final double SOFT_SMOOTHING     = 0.35;
    private static final double HARD_SMOOTHING     = 0.25;

    /**
     * Input projection of decision metrics needed by the engine.
     */
    public record MetricsInput(
        double lastConfidence,
        double confidenceSlope5,
        int    divergenceStreak,
        int    momentumStreak
    ) {}

    /**
     * Input projection of momentum/market state needed by the engine.
     */
    public record MomentumInput(
        String marketState,
        String confidenceTrend,
        double divergenceRatio
    ) {}

    /**
     * Combined result of exit awareness and risk envelope evaluation.
     */
    public record EvaluationResult(
        ExitAwareness exitAwareness,
        RiskEnvelope  riskEnvelope
    ) {}

    /**
     * Extended result that also carries the updated adaptive risk state
     * and posture stability state.
     */
    public record AdaptiveEvaluationResult(
        ExitAwareness        exitAwareness,
        RiskEnvelope         riskEnvelope,
        AdaptiveRiskState    newState,
        PostureStabilityState postureState
    ) {}

    public EvaluationResult evaluate(ActiveTradeContext context,
                                     MetricsInput metrics,
                                     MomentumInput state) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Instant nowInstant = now.toInstant(ZoneOffset.UTC);
        AwarenessResult awarenessResult = computeExitAwareness(
            context, metrics, state, now, null, nowInstant);
        RiskEnvelope envelope = computeRiskEnvelope(context, metrics, state);
        return new EvaluationResult(awarenessResult.awareness(), envelope);
    }

    /**
     * Moderately responsive evaluation — smooths stop levels toward targets
     * rather than jumping abruptly, unless momentum is WEAKENING.
     *
     * @param previousState null on first call (starts from BASE stops)
     */
    public AdaptiveEvaluationResult evaluateAdaptive(ActiveTradeContext context,
                                                     MetricsInput metrics,
                                                     MomentumInput momentum,
                                                     AdaptiveRiskState previousState,
                                                     PostureStabilityState previousPostureState) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Instant nowInstant = now.toInstant(ZoneOffset.UTC);
        AwarenessResult awarenessResult = computeExitAwareness(
            context, metrics, momentum, now, previousPostureState, nowInstant);
        ExitAwareness awareness = awarenessResult.awareness();
        RiskEnvelope targetEnvelope = computeRiskEnvelope(context, metrics, momentum);

        double targetSoft = targetEnvelope.softStopPercent();
        double targetHard = targetEnvelope.hardInvalidationPercent();

        double newSoft;
        double newHard;

        if (previousState == null || "WEAKENING".equals(momentum.marketState())) {
            // First call or abrupt shift — jump directly to target
            newSoft = targetSoft;
            newHard = targetHard;
        } else {
            // Moderately responsive smoothing
            newSoft = previousState.currentSoftStop()
                + (targetSoft - previousState.currentSoftStop()) * SOFT_SMOOTHING;
            newHard = previousState.currentHardStop()
                + (targetHard - previousState.currentHardStop()) * HARD_SMOOTHING;
        }

        newSoft = Math.round(newSoft * 100.0) / 100.0;
        newHard = Math.round(newHard * 100.0) / 100.0;

        String reasoning = targetEnvelope.reasoning();
        if ("EXTENDED".equals(awareness.durationSignal())) {
            reasoning = reasoning + ". Trade duration extended — momentum fatigue possible";
        }

        RiskEnvelope smoothedEnvelope = new RiskEnvelope(newSoft, newHard, reasoning);

        AdaptiveRiskState newState = new AdaptiveRiskState(
            context.symbol(), newSoft, newHard, LocalDateTime.now(ZoneOffset.UTC));

        return new AdaptiveEvaluationResult(awareness, smoothedEnvelope, newState, awarenessResult.postureState());
    }

    private record AwarenessResult(ExitAwareness awareness, PostureStabilityState postureState) {}

    private AwarenessResult computeExitAwareness(ActiveTradeContext context,
                                                  MetricsInput metrics,
                                                  MomentumInput state,
                                                  LocalDateTime now,
                                                  PostureStabilityState previousPostureState,
                                                  Instant nowInstant) {
        boolean momentumShift = "WEAKENING".equals(state.marketState())
            && !"WEAKENING".equalsIgnoreCase(context.entryMomentum());

        double confidenceDrift = metrics.lastConfidence() - context.entryConfidence();

        boolean divergenceGrowing = metrics.divergenceStreak() >= 2;

        long minutes = Duration.between(context.entryTime(), now).toMinutes();
        String durationSignal = minutes < 3 ? "FRESH" : minutes <= 8 ? "AGING" : "EXTENDED";

        StructureSignal structureSignal = StructureInterpreter.evaluate(metrics, state);

        // Build intermediate awareness (without posture) for posture evaluation
        ExitAwareness intermediate = new ExitAwareness(
            momentumShift, confidenceDrift, divergenceGrowing,
            durationSignal, structureSignal, null);

        TradePostureInterpreter.StabilityResult stabilityResult =
            TradePostureInterpreter.evaluateStable(
                state, structureSignal, intermediate, previousPostureState, nowInstant);

        ExitAwareness awareness = new ExitAwareness(momentumShift, confidenceDrift, divergenceGrowing,
            durationSignal, structureSignal, stabilityResult.posture());

        return new AwarenessResult(awareness, stabilityResult.state());
    }

    private RiskEnvelope computeRiskEnvelope(ActiveTradeContext context,
                                             MetricsInput metrics,
                                             MomentumInput state) {
        double softStop = BASE_SOFT_STOP_PCT;
        double hardStop = BASE_HARD_STOP_PCT;
        List<String> reasons = new ArrayList<>();

        if ("WEAKENING".equals(state.marketState())) {
            softStop *= 0.6;
            reasons.add("Momentum WEAKENING — soft stop tightened to " +
                String.format("%.1f%%", softStop));
        }

        double confidenceDrift = metrics.lastConfidence() - context.entryConfidence();
        if (confidenceDrift < -0.1) {
            softStop *= 0.8;
            hardStop *= 0.85;
            reasons.add(String.format("Confidence drifted %.2f from entry — stops narrowed",
                confidenceDrift));
        }

        if (metrics.divergenceStreak() >= 2) {
            reasons.add("Divergence streak at " + metrics.divergenceStreak() +
                " — AI/consensus disagreement persisting");
        }

        if (metrics.confidenceSlope5() < -0.05) {
            softStop *= 0.9;
            reasons.add(String.format("Confidence slope negative (%.3f) — trend deteriorating",
                metrics.confidenceSlope5()));
        }

        if (reasons.isEmpty()) {
            reasons.add("Conditions stable — default risk envelope applied");
        }

        return new RiskEnvelope(
            Math.round(softStop * 100.0) / 100.0,
            Math.round(hardStop * 100.0) / 100.0,
            String.join(". ", reasons)
        );
    }
}
