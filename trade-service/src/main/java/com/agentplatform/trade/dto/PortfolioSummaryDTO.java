package com.agentplatform.trade.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * API response record for portfolio-level summary.
 */
public record PortfolioSummaryDTO(
    @JsonProperty("openPositions")      int openPositions,
    @JsonProperty("totalExposure")      Double totalExposure,
    @JsonProperty("totalUnrealizedPnl") Double totalUnrealizedPnl,
    @JsonProperty("totalRealizedPnl")   Double totalRealizedPnl,
    @JsonProperty("maxDrawdownPct")     Double maxDrawdownPct,
    @JsonProperty("overallRiskLevel")   String overallRiskLevel,
    @JsonProperty("computedAt")         LocalDateTime computedAt
) {}
