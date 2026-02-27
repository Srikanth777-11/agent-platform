package com.agentplatform.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * Tracks the current adaptive risk stop levels for a symbol.
 * Used for moderately responsive smoothing between evaluation cycles.
 */
public record AdaptiveRiskState(
    @JsonProperty("symbol")           String symbol,
    @JsonProperty("currentSoftStop")  double currentSoftStop,
    @JsonProperty("currentHardStop")  double currentHardStop,
    @JsonProperty("lastUpdated")      LocalDateTime lastUpdated
) {}
