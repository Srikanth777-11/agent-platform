package com.agentplatform.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.agentplatform.common.model.MarketRegime;

public record FinalDecision(
    // ── existing fields (unchanged) ──────────────────────────────────────────
    @JsonProperty("symbol")          String symbol,
    @JsonProperty("timestamp")       Instant timestamp,
    @JsonProperty("agents")          List<AnalysisResult> agents,
    @JsonProperty("finalSignal")     String finalSignal,
    @JsonProperty("confidenceScore") double confidenceScore,
    @JsonProperty("metadata")        Map<String, Object> metadata,
    @JsonProperty("traceId")         String traceId,

    // ── v2 observability fields (additive — null/0 safe for older records) ───
    @JsonProperty("decisionVersion")     String decisionVersion,
    @JsonProperty("orchestratorVersion") String orchestratorVersion,
    @JsonProperty("agentCount")          int agentCount,
    @JsonProperty("decisionLatencyMs")   long decisionLatencyMs,

    // ── v3 consensus fields (additive — null/0 safe for older records) ───────
    @JsonProperty("consensusScore")       double consensusScore,
    @JsonProperty("agentWeightSnapshot")  Map<String, Double> agentWeightSnapshot,

    // ── v4 adaptive performance fields (additive — null safe for older records) ─
    @JsonProperty("adaptiveAgentWeights") Map<String, Double> adaptiveAgentWeights,

    // ── v5 market regime field (additive — null safe for older records) ────────
    @JsonProperty("marketRegime") MarketRegime marketRegime,

    // ── v6 AI strategist field (additive — null safe for older records) ────────
    @JsonProperty("aiReasoning") String aiReasoning,

    // ── v7 divergence awareness field (additive — null safe for older records) ─
    @JsonProperty("divergenceFlag") Boolean divergenceFlag,

    // ── v8 scalping intelligence fields (additive — null safe for older records) ─
    @JsonProperty("tradingSession")        String  tradingSession,
    @JsonProperty("entryPrice")            Double  entryPrice,
    @JsonProperty("targetPrice")           Double  targetPrice,
    @JsonProperty("stopLoss")              Double  stopLoss,
    @JsonProperty("estimatedHoldMinutes")  Integer estimatedHoldMinutes,

    // ── v9 directional bias fields (additive — null safe for older records) ─────
    @JsonProperty("tradeDirection")        String  tradeDirection,
    @JsonProperty("directionalBias")       String  directionalBias
) {
    /**
     * v9 factory — primary from Phase-33 onward.
     * Adds tradeDirection (LONG/SHORT/FLAT) and directionalBias (TrendAgent 5-vote).
     */
    public static FinalDecision v9(String symbol, Instant timestamp,
                                   List<AnalysisResult> agents,
                                   String finalSignal, double confidenceScore,
                                   Map<String, Object> metadata, String traceId,
                                   String decisionVersion, String orchestratorVersion,
                                   int agentCount, long decisionLatencyMs,
                                   double consensusScore,
                                   Map<String, Double> agentWeightSnapshot,
                                   Map<String, Double> adaptiveAgentWeights,
                                   MarketRegime marketRegime,
                                   String aiReasoning,
                                   Boolean divergenceFlag,
                                   String tradingSession,
                                   Double entryPrice, Double targetPrice,
                                   Double stopLoss, Integer estimatedHoldMinutes,
                                   String tradeDirection, String directionalBias) {
        return new FinalDecision(symbol, timestamp, agents, finalSignal, confidenceScore,
                                 metadata, traceId, decisionVersion, orchestratorVersion,
                                 agentCount, decisionLatencyMs,
                                 consensusScore, agentWeightSnapshot,
                                 adaptiveAgentWeights, marketRegime, aiReasoning, divergenceFlag,
                                 tradingSession, entryPrice, targetPrice, stopLoss, estimatedHoldMinutes,
                                 tradeDirection, directionalBias);
    }

    /**
     * v8 factory — retained for backward compatibility. v9 fields default to null.
     */
    public static FinalDecision of(String symbol, Instant timestamp,
                                   List<AnalysisResult> agents,
                                   String finalSignal, double confidenceScore,
                                   Map<String, Object> metadata, String traceId,
                                   String decisionVersion, String orchestratorVersion,
                                   int agentCount, long decisionLatencyMs,
                                   double consensusScore,
                                   Map<String, Double> agentWeightSnapshot,
                                   Map<String, Double> adaptiveAgentWeights,
                                   MarketRegime marketRegime,
                                   String aiReasoning,
                                   Boolean divergenceFlag,
                                   String tradingSession,
                                   Double entryPrice, Double targetPrice,
                                   Double stopLoss, Integer estimatedHoldMinutes) {
        return new FinalDecision(symbol, timestamp, agents, finalSignal, confidenceScore,
                                 metadata, traceId, decisionVersion, orchestratorVersion,
                                 agentCount, decisionLatencyMs,
                                 consensusScore, agentWeightSnapshot,
                                 adaptiveAgentWeights, marketRegime, aiReasoning, divergenceFlag,
                                 tradingSession, entryPrice, targetPrice, stopLoss, estimatedHoldMinutes,
                                 null, null);
    }

    /**
     * v7 factory — retained for backward compatibility. v8/v9 fields default to null.
     */
    public static FinalDecision of(String symbol, Instant timestamp,
                                   List<AnalysisResult> agents,
                                   String finalSignal, double confidenceScore,
                                   Map<String, Object> metadata, String traceId,
                                   String decisionVersion, String orchestratorVersion,
                                   int agentCount, long decisionLatencyMs,
                                   double consensusScore,
                                   Map<String, Double> agentWeightSnapshot,
                                   Map<String, Double> adaptiveAgentWeights,
                                   MarketRegime marketRegime,
                                   String aiReasoning,
                                   Boolean divergenceFlag) {
        return new FinalDecision(symbol, timestamp, agents, finalSignal, confidenceScore,
                                 metadata, traceId, decisionVersion, orchestratorVersion,
                                 agentCount, decisionLatencyMs,
                                 consensusScore, agentWeightSnapshot,
                                 adaptiveAgentWeights, marketRegime, aiReasoning, divergenceFlag,
                                 null, null, null, null, null, null, null);
    }

    /**
     * v6 factory — retained for backward compatibility.
     * v7 divergenceFlag defaults to {@code null}.
     */
    public static FinalDecision of(String symbol, Instant timestamp,
                                   List<AnalysisResult> agents,
                                   String finalSignal, double confidenceScore,
                                   Map<String, Object> metadata, String traceId,
                                   String decisionVersion, String orchestratorVersion,
                                   int agentCount, long decisionLatencyMs,
                                   double consensusScore,
                                   Map<String, Double> agentWeightSnapshot,
                                   Map<String, Double> adaptiveAgentWeights,
                                   MarketRegime marketRegime,
                                   String aiReasoning) {
        return new FinalDecision(symbol, timestamp, agents, finalSignal, confidenceScore,
                                 metadata, traceId, decisionVersion, orchestratorVersion,
                                 agentCount, decisionLatencyMs,
                                 consensusScore, agentWeightSnapshot,
                                 adaptiveAgentWeights, marketRegime, aiReasoning, null,
                                 null, null, null, null, null, null, null);
    }

    /**
     * v5 factory — retained for backward compatibility.
     * v6, v7, v8, v9 fields default to {@code null}.
     */
    public static FinalDecision of(String symbol, Instant timestamp,
                                   List<AnalysisResult> agents,
                                   String finalSignal, double confidenceScore,
                                   Map<String, Object> metadata, String traceId,
                                   String decisionVersion, String orchestratorVersion,
                                   int agentCount, long decisionLatencyMs,
                                   double consensusScore,
                                   Map<String, Double> agentWeightSnapshot,
                                   Map<String, Double> adaptiveAgentWeights,
                                   MarketRegime marketRegime) {
        return new FinalDecision(symbol, timestamp, agents, finalSignal, confidenceScore,
                                 metadata, traceId, decisionVersion, orchestratorVersion,
                                 agentCount, decisionLatencyMs,
                                 consensusScore, agentWeightSnapshot,
                                 adaptiveAgentWeights, marketRegime, null, null,
                                 null, null, null, null, null, null, null);
    }

    /**
     * v4 factory — retained for backward compatibility.
     * v5+ fields default to {@code null}.
     */
    public static FinalDecision of(String symbol, Instant timestamp,
                                   List<AnalysisResult> agents,
                                   String finalSignal, double confidenceScore,
                                   Map<String, Object> metadata, String traceId,
                                   String decisionVersion, String orchestratorVersion,
                                   int agentCount, long decisionLatencyMs,
                                   double consensusScore,
                                   Map<String, Double> agentWeightSnapshot,
                                   Map<String, Double> adaptiveAgentWeights) {
        return new FinalDecision(symbol, timestamp, agents, finalSignal, confidenceScore,
                                 metadata, traceId, decisionVersion, orchestratorVersion,
                                 agentCount, decisionLatencyMs,
                                 consensusScore, agentWeightSnapshot, adaptiveAgentWeights,
                                 null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * v3 factory — retained for backward compatibility.
     * v4+ fields default to {@code null}.
     */
    public static FinalDecision of(String symbol, Instant timestamp,
                                   List<AnalysisResult> agents,
                                   String finalSignal, double confidenceScore,
                                   Map<String, Object> metadata, String traceId,
                                   String decisionVersion, String orchestratorVersion,
                                   int agentCount, long decisionLatencyMs,
                                   double consensusScore,
                                   Map<String, Double> agentWeightSnapshot) {
        return new FinalDecision(symbol, timestamp, agents, finalSignal, confidenceScore,
                                 metadata, traceId, decisionVersion, orchestratorVersion,
                                 agentCount, decisionLatencyMs,
                                 consensusScore, agentWeightSnapshot,
                                 null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * v2 factory — retained for backward compatibility.
     * v3+ fields default to {@code 0.0} / {@code null}.
     */
    public static FinalDecision of(String symbol, Instant timestamp,
                                   List<AnalysisResult> agents,
                                   String finalSignal, double confidenceScore,
                                   Map<String, Object> metadata, String traceId,
                                   String decisionVersion, String orchestratorVersion,
                                   int agentCount, long decisionLatencyMs) {
        return new FinalDecision(symbol, timestamp, agents, finalSignal, confidenceScore,
                                 metadata, traceId, decisionVersion, orchestratorVersion,
                                 agentCount, decisionLatencyMs, 0.0, null,
                                 null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Legacy factory — retained for backward compatibility. All versioned fields default to null/0.
     */
    public static FinalDecision of(String symbol, Instant timestamp,
                                   List<AnalysisResult> agents,
                                   String finalSignal, double confidenceScore,
                                   Map<String, Object> metadata, String traceId) {
        return new FinalDecision(symbol, timestamp, agents, finalSignal, confidenceScore,
                                 metadata, traceId, null, null, 0, 0L, 0.0, null,
                                 null, null, null, null, null, null, null, null, null, null, null);
    }
}
