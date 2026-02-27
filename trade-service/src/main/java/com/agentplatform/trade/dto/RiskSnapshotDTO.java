package com.agentplatform.trade.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * API response record for risk snapshot data.
 */
public record RiskSnapshotDTO(
    @JsonProperty("symbol")         String symbol,
    @JsonProperty("positionSize")   Double positionSize,
    @JsonProperty("unrealizedPnl")  Double unrealizedPnl,
    @JsonProperty("drawdownPct")    Double drawdownPct,
    @JsonProperty("exposurePct")    Double exposurePct,
    @JsonProperty("riskLevel")      String riskLevel,
    @JsonProperty("computedAt")     LocalDateTime computedAt
) {}
