package com.agentplatform.common.model;

import com.agentplatform.common.posture.TradePosture;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Lightweight posture-level trade outcome statistics.
 * Operator reflection only — no automation linkage.
 */
public record TradeReflectionStats(
    @JsonProperty("posture")     TradePosture posture,
    @JsonProperty("totalTrades") long totalTrades,
    @JsonProperty("wins")        long wins,
    @JsonProperty("losses")      long losses,
    @JsonProperty("avgPnl")      double avgPnl
) {}
