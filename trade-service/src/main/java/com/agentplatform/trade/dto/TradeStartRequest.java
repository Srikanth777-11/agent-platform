package com.agentplatform.trade.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for POST /api/v1/trades/start.
 */
public record TradeStartRequest(
    @JsonProperty("symbol")          String symbol,
    @JsonProperty("entryPrice")      double entryPrice,
    @JsonProperty("entryConfidence") double entryConfidence,
    @JsonProperty("entryRegime")     String entryRegime,
    @JsonProperty("entryMomentum")   String entryMomentum
) {}
