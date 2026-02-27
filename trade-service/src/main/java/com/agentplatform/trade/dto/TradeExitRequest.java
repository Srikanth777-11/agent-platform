package com.agentplatform.trade.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for POST /api/v1/trades/exit.
 */
public record TradeExitRequest(
    @JsonProperty("symbol")    String symbol,
    @JsonProperty("exitPrice") double exitPrice
) {}
