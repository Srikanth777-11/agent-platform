package com.agentplatform.trade.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * Mirrors history-service DecisionMetricsDTO for deserialization.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DecisionMetricsResponse(
    @JsonProperty("symbol")           String symbol,
    @JsonProperty("lastConfidence")   double lastConfidence,
    @JsonProperty("confidenceSlope5") double confidenceSlope5,
    @JsonProperty("divergenceStreak") int divergenceStreak,
    @JsonProperty("momentumStreak")   int momentumStreak,
    @JsonProperty("lastUpdated")      LocalDateTime lastUpdated
) {}
