package com.agentplatform.common.consensus;

import com.agentplatform.common.model.AnalysisResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@link ConsensusEngine} implementation using uniform agent weights.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Assign each agent a weight of {@value #DEFAULT_WEIGHT} (equal participation).</li>
 *   <li>Map each agent's signal to a numeric score:
 *       BUY=+1.0, SELL=−1.0, HOLD=0.0, WATCH=+0.5.</li>
 *   <li>Compute {@code rawScore = Σ(signalScore × weight) / totalWeight}
 *       → result in [−1.0, +1.0].</li>
 *   <li>Normalize to confidence: {@code (rawScore + 1.0) / 2.0} → [0.0, 1.0].</li>
 *   <li>Derive final signal via thresholds on {@code rawScore}.</li>
 * </ol>
 *
 * <h3>Signal derivation thresholds</h3>
 * <pre>
 *   rawScore >  0.3  → BUY
 *   rawScore < -0.3  → SELL
 *   rawScore >  0.0  → WATCH
 *   otherwise        → HOLD
 * </pre>
 *
 * <p>This class is stateless and thread-safe.
 * It does NOT modify {@link AnalysisResult} instances.
 */
public class DefaultWeightedConsensusStrategy implements ConsensusEngine {

    private static final double DEFAULT_WEIGHT = 1.0;

    private static final double BUY_THRESHOLD  =  0.3;
    private static final double SELL_THRESHOLD = -0.3;

    /** Numeric score assigned to each signal string. Unmapped signals score 0.0. */
    private static final Map<String, Double> SIGNAL_SCORES = Map.of(
        "BUY",   +1.0,
        "SELL",  -1.0,
        "HOLD",   0.0,
        "WATCH", +0.5
    );

    @Override
    public ConsensusResult compute(List<AnalysisResult> results) {
        // Build agentWeights map preserving insertion order (LinkedHashMap)
        Map<String, Double> agentWeights = new LinkedHashMap<>();
        for (AnalysisResult r : results) {
            agentWeights.put(r.agentName(), DEFAULT_WEIGHT);
        }

        double totalWeight  = agentWeights.values().stream()
                                          .mapToDouble(Double::doubleValue).sum();
        double weightedSum  = 0.0;
        for (AnalysisResult r : results) {
            double score  = SIGNAL_SCORES.getOrDefault(r.signal(), 0.0);
            double weight = agentWeights.getOrDefault(r.agentName(), DEFAULT_WEIGHT);
            weightedSum  += score * weight;
        }

        // rawScore in [-1.0, +1.0]
        double rawScore = (totalWeight > 0.0) ? weightedSum / totalWeight : 0.0;

        // Map [-1, 1] → [0, 1], clamped for floating-point safety
        double normalizedConfidence = Math.max(0.0, Math.min(1.0, (rawScore + 1.0) / 2.0));

        return new ConsensusResult(deriveSignal(rawScore), normalizedConfidence, agentWeights);
    }

    private String deriveSignal(double rawScore) {
        if (rawScore >  BUY_THRESHOLD)  return "BUY";
        if (rawScore < SELL_THRESHOLD)  return "SELL";
        if (rawScore >  0.0)            return "WATCH";
        return "HOLD";
    }
}
