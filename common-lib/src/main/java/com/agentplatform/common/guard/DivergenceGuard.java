package com.agentplatform.common.guard;

/**
 * Divergence Safety Override — Path 12.
 *
 * <p>Applies two rules when the AI strategist and consensus guardrail disagree:
 *
 * <h3>Rule 1 — Consensus Override (single-cycle)</h3>
 * <p>When {@code divergenceFlag = true} AND {@code consensusConfidence ≥ 0.65},
 * the consensus signal and confidence replace the AI output entirely.
 * Rationale: a strongly-confident consensus disagreeing with the AI is a high-value
 * safety signal — the AI is making a contrarian call the statistics do not support.
 *
 * <h3>Rule 2 — Confidence Dampening (multi-cycle streak)</h3>
 * <p>When {@code divergenceFlag = true} AND the recent divergence streak is ≥ 2,
 * the AI signal is kept but confidence is multiplied by {@value #CONFIDENCE_DAMPEN_FACTOR}.
 * Rationale: persistent disagreement suggests the AI is pattern-locked; reduce
 * certainty without silencing it.
 *
 * <p>Both rules are skipped when {@code divergenceFlag = false} — no disagreement,
 * no intervention.
 *
 * <p>This class is stateless, pure, and thread-safe.
 */
public final class DivergenceGuard {

    /** Minimum consensus confidence to trigger a full signal override. */
    public static final double OVERRIDE_CONSENSUS_THRESHOLD = 0.65;

    /** Consecutive-divergence streak length that triggers confidence dampening. */
    public static final int STREAK_DAMPEN_THRESHOLD = 2;

    /** Multiplier applied to AI confidence when streak dampening fires. */
    public static final double CONFIDENCE_DAMPEN_FACTOR = 0.80;

    /** Minimum confidence value after dampening — prevents sub-neutral signals. */
    public static final double CONFIDENCE_FLOOR = 0.50;

    private DivergenceGuard() {}

    /**
     * Evaluates whether the AI signal/confidence should be overridden or dampened.
     *
     * @param aiSignal              AI strategist's final signal
     * @param aiConfidence          AI strategist's confidence [0.0–1.0]
     * @param consensusSignal       performance-weighted consensus signal (guardrail)
     * @param consensusConfidence   consensus normalized confidence [0.0–1.0]
     * @param currentDivergenceFlag true when AI and consensus disagree this cycle
     * @param recentDivergenceStreak consecutive divergence count from recent history
     * @return {@link OverrideResult} — signal/confidence to use; never null
     */
    public static OverrideResult evaluate(
            String aiSignal,
            double aiConfidence,
            String consensusSignal,
            double consensusConfidence,
            boolean currentDivergenceFlag,
            int recentDivergenceStreak) {

        if (!currentDivergenceFlag) {
            return OverrideResult.passThrough(aiSignal, aiConfidence);
        }

        // Rule 1: high-confidence consensus → full override
        if (consensusConfidence >= OVERRIDE_CONSENSUS_THRESHOLD) {
            return new OverrideResult(
                consensusSignal,
                consensusConfidence,
                true,
                String.format("ConsensusOverride consensus=%.2f≥%.2f AI=%s→%s",
                    consensusConfidence, OVERRIDE_CONSENSUS_THRESHOLD, aiSignal, consensusSignal)
            );
        }

        // Rule 2: persistent streak → dampen confidence (floor at CONFIDENCE_FLOOR)
        if (recentDivergenceStreak >= STREAK_DAMPEN_THRESHOLD) {
            double dampened = Math.max(CONFIDENCE_FLOOR, aiConfidence * CONFIDENCE_DAMPEN_FACTOR);
            return new OverrideResult(
                aiSignal,
                dampened,
                true,
                String.format("ConfidenceDampen streak=%d confidence %.2f→%.2f",
                    recentDivergenceStreak, aiConfidence, dampened)
            );
        }

        return OverrideResult.passThrough(aiSignal, aiConfidence);
    }

    public record OverrideResult(
        String  finalSignal,
        double  confidence,
        boolean overrideApplied,
        String  reason
    ) {
        static OverrideResult passThrough(String signal, double confidence) {
            return new OverrideResult(signal, confidence, false, null);
        }
    }
}
