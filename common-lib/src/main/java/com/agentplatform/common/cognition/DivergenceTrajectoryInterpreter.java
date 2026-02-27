package com.agentplatform.common.cognition;

import com.agentplatform.common.model.AnalysisResult;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Derives {@link DivergenceTrajectory} from current agent signal spread.
 *
 * <p>COOLING = strong consensus forming; RISING = agents fragmenting;
 * STABLE = moderate spread, no clear directional change.
 */
public final class DivergenceTrajectoryInterpreter {

    private DivergenceTrajectoryInterpreter() {}

    public static DivergenceTrajectory interpret(List<AnalysisResult> results,
                                                  Map<String, Double> adaptiveWeights) {
        if (results == null || results.size() < 2) return DivergenceTrajectory.STABLE;

        Map<String, Long> votes = results.stream()
            .collect(Collectors.groupingBy(AnalysisResult::signal, Collectors.counting()));

        int distinctSignals = votes.size();
        double consensusRatio = (double) votes.values().stream()
            .mapToLong(Long::longValue).max().orElse(1L) / results.size();

        if (distinctSignals == 1 || consensusRatio >= 0.75) return DivergenceTrajectory.COOLING;
        if (distinctSignals >= 3 || consensusRatio <= 0.40) return DivergenceTrajectory.RISING;
        return DivergenceTrajectory.STABLE;
    }
}
