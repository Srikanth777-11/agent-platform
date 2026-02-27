package com.agentplatform.trade.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Mirrors history-service MarketStateDTO for deserialization.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketStateResponse(
    @JsonProperty("symbol")          String symbol,
    @JsonProperty("marketState")     String marketState,
    @JsonProperty("dominantSignal")  String dominantSignal,
    @JsonProperty("signalAlignment") double signalAlignment,
    @JsonProperty("confidenceTrend") String confidenceTrend,
    @JsonProperty("divergenceRatio") double divergenceRatio,
    @JsonProperty("regime")          String regime,
    @JsonProperty("windowSize")      int windowSize
) {}
