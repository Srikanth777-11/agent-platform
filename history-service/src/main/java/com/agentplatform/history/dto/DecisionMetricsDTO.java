package com.agentplatform.history.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * API response record for per-symbol trend metrics.
 */
public record DecisionMetricsDTO(
    @JsonProperty("symbol")          String symbol,
    @JsonProperty("lastConfidence")  double lastConfidence,
    @JsonProperty("confidenceSlope5") double confidenceSlope5,
    @JsonProperty("divergenceStreak") int divergenceStreak,
    @JsonProperty("momentumStreak")  int momentumStreak,
    @JsonProperty("lastUpdated")     LocalDateTime lastUpdated
) {}
