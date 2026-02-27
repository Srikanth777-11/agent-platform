package com.agentplatform.common.consensus;

import com.agentplatform.common.model.AnalysisResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Performance-aware {@link ConsensusEngine} implementation that uses adaptive agent weights
 * computed by {@link AgentScoreCalculator} instead of equal participation.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Assign each agent its caller-supplied adaptive weight (falls back to {@value #FALLBACK_WEIGHT}
 *       if the agent is absent from the map).</li>
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
 * <p>When called via {@link #compute(List)} (no weights supplied), falls back to
 * equal weight {@value #FALLBACK_WEIGHT} per agent — identical to
 * {@link DefaultWeightedConsensusStrategy} behaviour.
 *
 * <p>This class is stateless and thread-safe.
 */
public class PerformanceWeightedConsensusStrategy implements ConsensusEngine {

    private static final double FALLBACK_WEIGHT = 1.0;

    private static final double BUY_THRESHOLD  =  0.3;
    private static final double SELL_THRESHOLD = -0.3;

    private static final Map<String, Double> SIGNAL_SCORES = Map.of(
        "BUY",   +1.0,
        "SELL",  -1.0,
        "HOLD",   0.0,
        "WATCH", +0.5
    );

    @Override
    public ConsensusResult compute(List<AnalysisResult> results) {
        Map<String, Double> equalWeights = new LinkedHashMap<>();
        for (AnalysisResult r : results) {
            equalWeights.put(r.agentName(), FALLBACK_WEIGHT);
        }
        return computeWithWeights(results, equalWeights);
    }

    @Override
    public ConsensusResult compute(List<AnalysisResult> results, Map<String, Double> adaptiveWeights) {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (AnalysisResult r : results) {
            weights.put(r.agentName(), adaptiveWeights.getOrDefault(r.agentName(), FALLBACK_WEIGHT));
        }
        return computeWithWeights(results, weights);
    }

    private ConsensusResult computeWithWeights(List<AnalysisResult> results, Map<String, Double> weights) {
        double totalWeight = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        double weightedSum = 0.0;
        for (AnalysisResult r : results) {
            double score  = SIGNAL_SCORES.getOrDefault(r.signal(), 0.0);
            double weight = weights.getOrDefault(r.agentName(), FALLBACK_WEIGHT);
            weightedSum  += score * weight;
        }

        double rawScore             = (totalWeight > 0.0) ? weightedSum / totalWeight : 0.0;
        double normalizedConfidence = Math.max(0.0, Math.min(1.0, (rawScore + 1.0) / 2.0));

        return new ConsensusResult(deriveSignal(rawScore), normalizedConfidence, weights);
    }

    private String deriveSignal(double rawScore) {
        if (rawScore >  BUY_THRESHOLD)  return "BUY";
        if (rawScore < SELL_THRESHOLD)  return "SELL";
        if (rawScore >  0.0)            return "WATCH";
        return "HOLD";
    }
}
