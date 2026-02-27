package com.agentplatform.history.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Phase-26 observation analytics — edge validation report for a symbol.
 *
 * <p>Surfaces the 6 correlation metrics + risk-adjusted outcome so the operator
 * can determine whether the system has real edge after 30–50 trades.
 */
public record EdgeReportDTO(
    @JsonProperty("symbol")           String symbol,
    @JsonProperty("sampleSize")       int    sampleSize,

    // ── Core edge metrics ─────────────────────────────────────────────────────
    @JsonProperty("winRate")          double winRate,
    @JsonProperty("avgGain")          double avgGain,          // avg outcomePercent when positive
    @JsonProperty("avgLoss")          double avgLoss,          // avg |outcomePercent| when negative
    @JsonProperty("riskRewardRatio")  double riskRewardRatio,  // avgGain / avgLoss
    @JsonProperty("expectancy")       double expectancy,       // (winRate×avgGain)−(lossRate×avgLoss)
    @JsonProperty("maxDrawdown")      double maxDrawdown,      // largest single loss %

    // ── Session edge ──────────────────────────────────────────────────────────
    @JsonProperty("openingBurstWinRate")  double openingBurstWinRate,
    @JsonProperty("powerHourWinRate")     double powerHourWinRate,
    @JsonProperty("middayWinRate")        double middayWinRate,

    // ── Stability correlation ─────────────────────────────────────────────────
    @JsonProperty("stableAvgOutcome")    double stableAvgOutcome,
    @JsonProperty("driftingAvgOutcome")  double driftingAvgOutcome,
    @JsonProperty("unstableAvgOutcome")  double unstableAvgOutcome,

    // ── Confidence calibration ────────────────────────────────────────────────
    @JsonProperty("highConfWinRate")  double highConfWinRate,   // confidence > 0.75
    @JsonProperty("lowConfWinRate")   double lowConfWinRate,    // confidence <= 0.75

    // ── Trade frequency ────────────────────────────────────────────────────────
    @JsonProperty("avgTradesPerSession") double avgTradesPerSession,

    // ── Verdict ────────────────────────────────────────────────────────────────
    @JsonProperty("hasEdge")  boolean hasEdge,   // expectancy > 0 AND riskReward > 1.0
    @JsonProperty("verdict")  String  verdict
) {}
