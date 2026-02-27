package com.agentplatform.history.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Lightweight projection of the computed {@link com.agentplatform.common.model.MarketState}
 * for a single symbol — designed for the calm momentum banner in the UI.
 *
 * <p>One instance per symbol. Read-only cognitive interpretation, not a trading signal.
 */
public record MarketStateDTO(
    @JsonProperty("symbol")          String symbol,
    @JsonProperty("marketState")     String marketState,
    @JsonProperty("dominantSignal")  String dominantSignal,
    @JsonProperty("signalAlignment") double signalAlignment,
    @JsonProperty("confidenceTrend") String confidenceTrend,
    @JsonProperty("divergenceRatio") double divergenceRatio,
    @JsonProperty("regime")          String regime,
    @JsonProperty("windowSize")      int windowSize
) {}
