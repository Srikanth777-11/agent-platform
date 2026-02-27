package com.agentplatform.trade.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * API response record for trade session data.
 */
public record TradeSessionDTO(
    @JsonProperty("id")              Long id,
    @JsonProperty("symbol")          String symbol,
    @JsonProperty("entryTime")       LocalDateTime entryTime,
    @JsonProperty("entryPrice")      Double entryPrice,
    @JsonProperty("entryConfidence") Double entryConfidence,
    @JsonProperty("entryRegime")     String entryRegime,
    @JsonProperty("entryMomentum")   String entryMomentum,
    @JsonProperty("exitTime")        LocalDateTime exitTime,
    @JsonProperty("exitPrice")       Double exitPrice,
    @JsonProperty("pnl")             Double pnl,
    @JsonProperty("durationMs")      Long durationMs
) {}
