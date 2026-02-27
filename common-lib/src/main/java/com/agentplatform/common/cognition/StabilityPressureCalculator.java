package com.agentplatform.common.cognition;

import com.agentplatform.common.model.AnalysisResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives a stability pressure score [0.0 – 1.0] from agent signal entropy
 * and confidence dispersion. Higher = more instability.
 *
 * <p>Pure static utility — no state, no Spring dependency.
 * Inputs are already present in the pipeline at enrichment time.
 */
public final class StabilityPressureCalculator {

    private StabilityPressureCalculator() {}

    public static double compute(List<AnalysisResult> results, Map<String, Double> adaptiveWeights) {
        if (results == null || results.isEmpty()) return 0.5;

        // Signal diversity: 0.0 = unanimous, 1.0 = maximum disagreement
        Map<String, Long> votes = new HashMap<>();
        for (AnalysisResult r : results) {
            votes.merge(r.signal(), 1L, Long::sum);
        }
        long maxVote = votes.values().stream().mapToLong(Long::longValue).max().orElse(1L);
        double signalDiversity = 1.0 - ((double) maxVote / results.size());

        // Confidence dispersion: average absolute deviation from mean
        double avgConf = results.stream()
            .mapToDouble(AnalysisResult::confidenceScore).average().orElse(0.5);
        double confDispersion = results.stream()
            .mapToDouble(r -> Math.abs(r.confidenceScore() - avgConf))
            .average().orElse(0.0);

        // Weight penalty: depressed adaptive weights signal degraded agent reliability
        double weightPenalty = adaptiveWeights.values().stream()
            .mapToDouble(Double::doubleValue)
            .map(w -> Math.max(0.0, 1.0 - w))
            .average().orElse(0.0);

        return Math.min(1.0, (signalDiversity * 0.5) + (confDispersion * 0.3) + (weightPenalty * 0.2));
    }
}
