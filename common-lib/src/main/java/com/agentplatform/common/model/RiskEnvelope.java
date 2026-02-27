package com.agentplatform.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Operator-facing risk boundaries derived from momentum state,
 * confidence drift, and divergence trends. Not a trading signal.
 */
public record RiskEnvelope(
    @JsonProperty("softStopPercent")          double softStopPercent,
    @JsonProperty("hardInvalidationPercent")  double hardInvalidationPercent,
    @JsonProperty("reasoning")               String reasoning
) {}
