package com.agentplatform.common.momentum;

import com.agentplatform.common.model.MarketState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic verification of {@link MomentumStateCalculator}.
 * Covers all four classification states and edge cases.
 */
class MomentumStateCalculatorTest {

    // ── classify() — top-level integration ────────────────────────────────

    @Nested
    @DisplayName("classify() — full pipeline")
    class ClassifyTests {

        @Test
        @DisplayName("null signals → CALM")
        void nullSignals_returnCalm() {
            assertEquals(MarketState.CALM,
                MomentumStateCalculator.classify(null, null, null, null));
        }

        @Test
        @DisplayName("fewer than MIN_WINDOW (3) signals → CALM")
        void tooFewSignals_returnCalm() {
            assertEquals(MarketState.CALM,
                MomentumStateCalculator.classify(
                    List.of("BUY", "BUY"), List.of(0.8, 0.9),
                    List.of(false, false), List.of("TRENDING", "TRENDING")));
        }

        @Test
        @DisplayName("empty signals list → CALM")
        void emptySignals_returnCalm() {
            assertEquals(MarketState.CALM,
                MomentumStateCalculator.classify(
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList()));
        }

        @Test
        @DisplayName("CONFIRMED — strong alignment, rising confidence, low divergence, stable regime")
        void confirmedState() {
            // 5/5 = 100% alignment, rising confidence, no divergence, stable regime
            List<String> signals = List.of("BUY", "BUY", "BUY", "BUY", "BUY");
            List<Double> confidences = List.of(0.9, 0.85, 0.8, 0.75, 0.7); // most-recent-first, rising when reversed
            List<Boolean> divergence = List.of(false, false, false, false, false);
            List<String> regimes = List.of("TRENDING", "TRENDING", "TRENDING", "TRENDING", "TRENDING");

            MarketState result = MomentumStateCalculator.classify(signals, confidences, divergence, regimes);
            assertEquals(MarketState.CONFIRMED, result);
        }

        @Test
        @DisplayName("WEAKENING — good alignment but confidence declining")
        void weakeningState_decliningConfidence() {
            // 4/5 = 80% alignment, declining confidence
            List<String> signals = List.of("SELL", "SELL", "SELL", "SELL", "BUY");
            List<Double> confidences = List.of(0.5, 0.6, 0.7, 0.8, 0.9); // most-recent-first, declining when reversed
            List<Boolean> divergence = List.of(false, false, false, false, false);
            List<String> regimes = List.of("VOLATILE", "VOLATILE", "VOLATILE", "VOLATILE", "VOLATILE");

            MarketState result = MomentumStateCalculator.classify(signals, confidences, divergence, regimes);
            assertEquals(MarketState.WEAKENING, result);
        }

        @Test
        @DisplayName("WEAKENING — good alignment but high divergence")
        void weakeningState_highDivergence() {
            // 4/5 = 80% alignment, but 3/5 divergence = 60% > threshold
            List<String> signals = List.of("BUY", "BUY", "BUY", "BUY", "SELL");
            List<Double> confidences = List.of(0.8, 0.8, 0.8, 0.8, 0.8); // stable
            List<Boolean> divergence = List.of(true, true, true, false, false); // 60% divergence
            List<String> regimes = List.of("TRENDING", "TRENDING", "TRENDING", "TRENDING", "TRENDING");

            MarketState result = MomentumStateCalculator.classify(signals, confidences, divergence, regimes);
            assertEquals(MarketState.WEAKENING, result);
        }

        @Test
        @DisplayName("BUILDING — moderate alignment, rising confidence, low divergence")
        void buildingState() {
            // 3/4 = 75% alignment (moderate but not strong 80%), rising confidence
            List<String> signals = List.of("BUY", "BUY", "BUY", "HOLD");
            List<Double> confidences = List.of(0.9, 0.8, 0.7, 0.6); // most-recent-first, rising when reversed
            List<Boolean> divergence = List.of(false, false, false, false);
            List<String> regimes = List.of("TRENDING", "TRENDING", "TRENDING", "TRENDING");

            MarketState result = MomentumStateCalculator.classify(signals, confidences, divergence, regimes);
            assertEquals(MarketState.BUILDING, result);
        }

        @Test
        @DisplayName("CALM — no clear alignment")
        void calmState_noAlignment() {
            // 2/5 = 40% alignment — below threshold
            List<String> signals = List.of("BUY", "SELL", "HOLD", "BUY", "SELL");
            List<Double> confidences = List.of(0.5, 0.5, 0.5, 0.5, 0.5);
            List<Boolean> divergence = List.of(false, false, false, false, false);
            List<String> regimes = List.of("VOLATILE", "TRENDING", "VOLATILE", "RANGING", "TRENDING");

            MarketState result = MomentumStateCalculator.classify(signals, confidences, divergence, regimes);
            assertEquals(MarketState.CALM, result);
        }

        @Test
        @DisplayName("Deterministic — same input always produces same output")
        void deterministicBehavior() {
            List<String> signals = List.of("BUY", "BUY", "BUY", "BUY", "SELL");
            List<Double> confidences = List.of(0.85, 0.82, 0.78, 0.75, 0.7);
            List<Boolean> divergence = List.of(false, false, false, false, true);
            List<String> regimes = List.of("TRENDING", "TRENDING", "TRENDING", "TRENDING", "TRENDING");

            MarketState first = MomentumStateCalculator.classify(signals, confidences, divergence, regimes);
            for (int i = 0; i < 100; i++) {
                assertEquals(first, MomentumStateCalculator.classify(signals, confidences, divergence, regimes),
                    "Classification must be deterministic on iteration " + i);
            }
        }
    }

    // ── Signal Alignment ──────────────────────────────────────────────────

    @Nested
    @DisplayName("computeSignalAlignment()")
    class SignalAlignmentTests {

        @Test
        @DisplayName("all same signal → 1.0")
        void allSame() {
            assertEquals(1.0,
                MomentumStateCalculator.computeSignalAlignment(List.of("BUY", "BUY", "BUY")));
        }

        @Test
        @DisplayName("all different signals → low alignment")
        void allDifferent() {
            double result = MomentumStateCalculator.computeSignalAlignment(
                List.of("BUY", "SELL", "HOLD"));
            assertTrue(result <= 0.34, "Expected low alignment but got " + result);
        }

        @Test
        @DisplayName("empty list → 0.0")
        void emptyList() {
            assertEquals(0.0,
                MomentumStateCalculator.computeSignalAlignment(Collections.emptyList()));
        }

        @Test
        @DisplayName("handles null entries gracefully")
        void nullEntries() {
            double result = MomentumStateCalculator.computeSignalAlignment(
                Arrays.asList("BUY", null, "BUY", null, "BUY"));
            // 3 BUYs out of 5 entries = 0.6
            assertEquals(0.6, result, 0.001);
        }
    }

    // ── Confidence Trend ──────────────────────────────────────────────────

    @Nested
    @DisplayName("computeConfidenceTrend()")
    class ConfidenceTrendTests {

        @Test
        @DisplayName("rising confidence → positive slope")
        void risingTrend() {
            // most-recent-first: [0.9, 0.8, 0.7] → reversed to [0.7, 0.8, 0.9]
            double slope = MomentumStateCalculator.computeConfidenceTrend(
                List.of(0.9, 0.8, 0.7));
            assertTrue(slope > 0, "Expected positive slope but got " + slope);
        }

        @Test
        @DisplayName("declining confidence → negative slope")
        void decliningTrend() {
            // most-recent-first: [0.5, 0.7, 0.9] → reversed to [0.9, 0.7, 0.5]
            double slope = MomentumStateCalculator.computeConfidenceTrend(
                List.of(0.5, 0.7, 0.9));
            assertTrue(slope < 0, "Expected negative slope but got " + slope);
        }

        @Test
        @DisplayName("flat confidence → near-zero slope")
        void flatTrend() {
            double slope = MomentumStateCalculator.computeConfidenceTrend(
                List.of(0.8, 0.8, 0.8, 0.8));
            assertEquals(0.0, slope, 0.001);
        }

        @Test
        @DisplayName("null input → 0.0")
        void nullInput() {
            assertEquals(0.0, MomentumStateCalculator.computeConfidenceTrend(null));
        }

        @Test
        @DisplayName("single element → 0.0")
        void singleElement() {
            assertEquals(0.0, MomentumStateCalculator.computeConfidenceTrend(List.of(0.8)));
        }
    }

    // ── Divergence Ratio ──────────────────────────────────────────────────

    @Nested
    @DisplayName("computeDivergenceRatio()")
    class DivergenceRatioTests {

        @Test
        @DisplayName("no divergence → 0.0")
        void noDivergence() {
            assertEquals(0.0,
                MomentumStateCalculator.computeDivergenceRatio(
                    List.of(false, false, false)));
        }

        @Test
        @DisplayName("all divergent → 1.0")
        void allDivergent() {
            assertEquals(1.0,
                MomentumStateCalculator.computeDivergenceRatio(
                    List.of(true, true, true)));
        }

        @Test
        @DisplayName("null flags treated as false")
        void nullFlags() {
            // 1 true out of 4 = 0.25
            assertEquals(0.25,
                MomentumStateCalculator.computeDivergenceRatio(
                    Arrays.asList(true, null, false, null)), 0.001);
        }

        @Test
        @DisplayName("null list → 0.0")
        void nullList() {
            assertEquals(0.0, MomentumStateCalculator.computeDivergenceRatio(null));
        }
    }

    // ── Regime Stability ──────────────────────────────────────────────────

    @Nested
    @DisplayName("isRegimeStable()")
    class RegimeStabilityTests {

        @Test
        @DisplayName("all same regime → true")
        void allSame() {
            assertTrue(MomentumStateCalculator.isRegimeStable(
                List.of("TRENDING", "TRENDING", "TRENDING")));
        }

        @Test
        @DisplayName("mixed regimes → false")
        void mixed() {
            assertFalse(MomentumStateCalculator.isRegimeStable(
                List.of("TRENDING", "VOLATILE", "TRENDING")));
        }

        @Test
        @DisplayName("null entries ignored")
        void nullEntries() {
            assertTrue(MomentumStateCalculator.isRegimeStable(
                Arrays.asList("TRENDING", null, "TRENDING", null)));
        }

        @Test
        @DisplayName("null list → true (insufficient data)")
        void nullList() {
            assertTrue(MomentumStateCalculator.isRegimeStable(null));
        }

        @Test
        @DisplayName("single element → true")
        void singleElement() {
            assertTrue(MomentumStateCalculator.isRegimeStable(List.of("VOLATILE")));
        }
    }

    // ── resolveState() — decision tree ────────────────────────────────────

    @Nested
    @DisplayName("resolveState() — decision tree boundaries")
    class ResolveStateTests {

        @Test
        @DisplayName("CONFIRMED boundary — exactly at strong threshold")
        void confirmedBoundary() {
            assertEquals(MarketState.CONFIRMED,
                MomentumStateCalculator.resolveState(0.80, 0.0, 0.0, true));
        }

        @Test
        @DisplayName("CONFIRMED not granted if regime unstable")
        void confirmedDenied_unstableRegime() {
            assertNotEquals(MarketState.CONFIRMED,
                MomentumStateCalculator.resolveState(0.90, 0.05, 0.0, false));
        }

        @Test
        @DisplayName("CONFIRMED not granted if confidence declining")
        void confirmedDenied_declining() {
            // confidenceTrend < -0.03 = declining
            assertNotEquals(MarketState.CONFIRMED,
                MomentumStateCalculator.resolveState(0.90, -0.05, 0.0, true));
        }

        @Test
        @DisplayName("WEAKENING — moderate alignment with declining confidence")
        void weakening_declining() {
            assertEquals(MarketState.WEAKENING,
                MomentumStateCalculator.resolveState(0.70, -0.05, 0.1, true));
        }

        @Test
        @DisplayName("WEAKENING — moderate alignment with high divergence")
        void weakening_highDivergence() {
            assertEquals(MarketState.WEAKENING,
                MomentumStateCalculator.resolveState(0.70, 0.0, 0.50, true));
        }

        @Test
        @DisplayName("BUILDING — moderate alignment + rising + low divergence")
        void building_rising() {
            assertEquals(MarketState.BUILDING,
                MomentumStateCalculator.resolveState(0.70, 0.05, 0.1, true));
        }

        @Test
        @DisplayName("BUILDING (softer) — moderate alignment + stable + low divergence")
        void building_stable() {
            assertEquals(MarketState.BUILDING,
                MomentumStateCalculator.resolveState(0.65, 0.0, 0.1, true));
        }

        @Test
        @DisplayName("CALM — below alignment threshold")
        void calm_lowAlignment() {
            assertEquals(MarketState.CALM,
                MomentumStateCalculator.resolveState(0.50, 0.05, 0.0, true));
        }
    }
}
