package com.agentplatform.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record AnalysisResult(
    @JsonProperty("agentName") String agentName,
    @JsonProperty("summary") String summary,
    @JsonProperty("signal") String signal,              // BUY / SELL / HOLD / WATCH
    @JsonProperty("confidenceScore") double confidenceScore,
    @JsonProperty("metadata") Map<String, Object> metadata
) {
    public static AnalysisResult of(String agentName, String summary,
                                     String signal, double confidence,
                                     Map<String, Object> metadata) {
        return new AnalysisResult(agentName, summary, signal, confidence, metadata);
    }
}
