package com.agentplatform.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * Snapshot of an active trade's entry context — operator awareness only.
 * No automation linkage.
 */
public record ActiveTradeContext(
    @JsonProperty("symbol")          String symbol,
    @JsonProperty("entryPrice")      double entryPrice,
    @JsonProperty("entryTime")       LocalDateTime entryTime,
    @JsonProperty("entryConfidence") double entryConfidence,
    @JsonProperty("entryRegime")     String entryRegime,
    @JsonProperty("entryMomentum")   String entryMomentum
) {}
