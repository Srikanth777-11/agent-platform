package com.agentplatform.common.momentum;

import com.agentplatform.common.model.MarketState;

import java.util.List;

/**
 * Pure stateless cognition logic that interprets recent decision history
 * to classify overall market momentum stability.
 *
 * <p>This is a <strong>read-only cognitive interpreter</strong> — it does not
 * generate trading signals, does not participate in the orchestration pipeline,
 * and does not modify any state. It reads existing {@code FinalDecision} data
 * and classifies the momentum pattern.
 *
 * <h3>Inputs (all derived from existing persisted data)</h3>
 * <ul>
 *   <li>Recent signal strings (e.g. {@code "BUY", "HOLD", "SELL"})</li>
 *   <li>Confidence score trend (most recent first)</li>
 *   <li>Divergence flag history</li>
 *   <li>Market regime continuity</li>
 * </ul>
 *
 * <h3>Classification logic</h3>
 * <p>The calculator evaluates four dimensions and combines them into a
 * single {@link MarketState}:
 * <ol>
 *   <li><strong>Signal alignment</strong> — fraction of recent signals that match
 *       the dominant signal.</li>
 *   <li><strong>Confidence trend</strong> — whether confidence is rising, stable,
 *       or declining across the window.</li>
 *   <li><strong>Divergence pressure</strong> — fraction of recent decisions where
 *       the AI diverged from consensus.</li>
 *   <li><strong>Regime stability</strong> — whether the market regime has been
 *       consistent across the window.</li>
 * </ol>
 *
 * <h3>Architectural placement</h3>
 * <p>Lives in {@code common-lib} (cognition boundary). Consumed by
 * {@code history-service} (data boundary) via a reactive projection endpoint.
 * No Spring dependencies. No I/O. Pure function.
 */
public final class MomentumStateCalculator {

    /** Minimum number of decisions required for meaningful classification. */
    private static final int MIN_WINDOW = 3;

    /** Signal alignment threshold to consider momentum "aligned". */
    private static final double ALIGNMENT_THRESHOLD = 0.65;

    /** Strong alignment threshold for CONFIRMED state. */
    private static final double STRONG_ALIGNMENT_THRESHOLD = 0.80;

    /** Divergence ratio above which momentum is considered pressured. */
    private static final double DIVERGENCE_PRESSURE_THRESHOLD = 0.40;

    /** Confidence trend slope threshold (positive = rising). */
    private static final double TREND_RISING_THRESHOLD = 0.02;

    /** Confidence trend slope threshold (negative = declining). */
    private static final double TREND_DECLINING_THRESHOLD = -0.03;

    private MomentumStateCalculator() { /* utility class */ }

    /**
     * Classifies the momentum state from a window of recent decisions.
     *
     * <p>All lists must be ordered most-recent-first and have the same length.
     * If fewer than {@value #MIN_WINDOW} decisions are available, returns
     * {@link MarketState#CALM} — insufficient data for meaningful classification.
     *
     * @param signals    recent {@code finalSignal} values (e.g. "BUY", "HOLD")
     * @param confidences recent {@code confidenceScore} values (0.0–1.0)
     * @param divergenceFlags recent {@code divergenceFlag} values (nullable entries treated as false)
     * @param regimes    recent {@code marketRegime} strings (e.g. "TRENDING", "VOLATILE")
     * @return the classified {@link MarketState}; never null
     */
    public static MarketState classify(List<String> signals,
                                       List<Double> confidences,
                                       List<Boolean> divergenceFlags,
                                       List<String> regimes) {
        if (signals == null || signals.size() < MIN_WINDOW) {
            return MarketState.CALM;
        }

        int window = signals.size();

        // ── Dimension 1: Signal alignment ──────────────────────────
        double alignmentScore = computeSignalAlignment(signals);

        // ── Dimension 2: Confidence trend ──────────────────────────
        double confidenceTrend = computeConfidenceTrend(confidences);

        // ── Dimension 3: Divergence pressure ───────────────────────
        double divergenceRatio = computeDivergenceRatio(divergenceFlags);

        // ── Dimension 4: Regime stability ──────────────────────────
        boolean regimeStable = isRegimeStable(regimes);

        // ── Classification decision tree ───────────────────────────
        return resolveState(alignmentScore, confidenceTrend, divergenceRatio, regimeStable);
    }

    // ── Dimension computations ─────────────────────────────────────

    /**
     * Fraction of signals that match the most common signal in the window.
     * Returns 0.0–1.0.
     */
    public static double computeSignalAlignment(List<String> signals) {
        if (signals.isEmpty()) return 0.0;

        // Find dominant signal
        String dominant = signals.stream()
            .filter(s -> s != null)
            .reduce((a, b) -> countOccurrences(signals, a) >= countOccurrences(signals, b) ? a : b)
            .orElse(null);

        if (dominant == null) return 0.0;

        long matchCount = signals.stream()
            .filter(s -> dominant.equals(s))
            .count();

        return (double) matchCount / signals.size();
    }

    /**
     * Computes a simple linear trend slope of confidence scores.
     * Positive = rising, negative = declining.
     * Inputs are most-recent-first; internally reversed for slope calculation.
     */
    public static double computeConfidenceTrend(List<Double> confidences) {
        if (confidences == null || confidences.size() < 2) return 0.0;

        int n = confidences.size();
        // Reverse: oldest-first for natural slope calculation
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            int x = i; // 0 = oldest, n-1 = newest
            double y = confidences.get(n - 1 - i); // reverse order
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }

        double denominator = n * sumX2 - sumX * sumX;
        if (denominator == 0) return 0.0;

        return (n * sumXY - sumX * sumY) / denominator;
    }

    /**
     * Fraction of recent decisions where divergenceFlag was true.
     * Null flags are treated as false (no divergence).
     */
    public static double computeDivergenceRatio(List<Boolean> divergenceFlags) {
        if (divergenceFlags == null || divergenceFlags.isEmpty()) return 0.0;

        long divergentCount = divergenceFlags.stream()
            .filter(flag -> Boolean.TRUE.equals(flag))
            .count();

        return (double) divergentCount / divergenceFlags.size();
    }

    /**
     * Returns true if all regimes in the window match (or are null).
     * A stable regime supports momentum confirmation.
     */
    public static boolean isRegimeStable(List<String> regimes) {
        if (regimes == null || regimes.size() < 2) return true;

        String first = regimes.stream()
            .filter(r -> r != null)
            .findFirst()
            .orElse(null);

        if (first == null) return true;

        return regimes.stream()
            .filter(r -> r != null)
            .allMatch(first::equals);
    }

    // ── State resolution ───────────────────────────────────────────

    /**
     * Resolves the final {@link MarketState} from the four computed dimensions.
     *
     * <p>Decision tree (evaluated top-to-bottom, first match wins):
     *
     * <pre>
     * CONFIRMED:  strong alignment + stable/rising confidence + low divergence + stable regime
     * WEAKENING:  previously had alignment but confidence declining OR divergence rising
     * BUILDING:   moderate alignment + rising confidence + manageable divergence
     * CALM:       default — insufficient momentum pattern
     * </pre>
     */
    public static MarketState resolveState(double alignment, double confidenceTrend,
                                    double divergenceRatio, boolean regimeStable) {

        boolean strongAlignment = alignment >= STRONG_ALIGNMENT_THRESHOLD;
        boolean moderateAlignment = alignment >= ALIGNMENT_THRESHOLD;
        boolean lowDivergence = divergenceRatio < DIVERGENCE_PRESSURE_THRESHOLD;
        boolean confidenceRising = confidenceTrend > TREND_RISING_THRESHOLD;
        boolean confidenceDeclining = confidenceTrend < TREND_DECLINING_THRESHOLD;

        // CONFIRMED: strong alignment + not declining + low divergence + stable regime
        if (strongAlignment && !confidenceDeclining && lowDivergence && regimeStable) {
            return MarketState.CONFIRMED;
        }

        // WEAKENING: had alignment but confidence is declining or divergence is high
        if (moderateAlignment && (confidenceDeclining || !lowDivergence)) {
            return MarketState.WEAKENING;
        }

        // BUILDING: moderate alignment + rising confidence + manageable divergence
        if (moderateAlignment && confidenceRising && lowDivergence) {
            return MarketState.BUILDING;
        }

        // BUILDING (softer): moderate alignment + stable + low divergence
        if (moderateAlignment && !confidenceDeclining && lowDivergence) {
            return MarketState.BUILDING;
        }

        // Default: CALM
        return MarketState.CALM;
    }

    // ── Helpers ────────────────────────────────────────────────────

    private static long countOccurrences(List<String> list, String value) {
        return list.stream().filter(s -> value.equals(s)).count();
    }
}
