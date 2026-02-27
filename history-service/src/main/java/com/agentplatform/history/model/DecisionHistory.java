package com.agentplatform.history.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Persisted record of every FinalDecision that passed through the orchestrator.
 *
 * Column mapping (R2DBC snake_case convention):
 *   finalSignal         → final_signal
 *   confidenceScore     → confidence_score
 *   savedAt             → saved_at
 *   decisionVersion     → decision_version
 *   orchestratorVersion → orchestrator_version
 *   agentCount          → agent_count
 *   decisionLatencyMs   → decision_latency_ms
 *   consensusScore      → consensus_score
 *   agentWeightSnapshot → agent_weight_snapshot
 *   marketRegime        → market_regime
 *
 * agents   — JSON-serialised List<AnalysisResult>
 * metadata — JSON-serialised Map<String, Object>  (signalVotes, agentCount, etc.)
 */
@Data
@NoArgsConstructor
@Table("decision_history")
public class DecisionHistory {

    @Id
    private Long id;

    private String symbol;

    private LocalDateTime timestamp;

    /** JSON-serialised {@code List<AnalysisResult>} */
    private String agents;

    private String finalSignal;

    private double confidenceScore;

    /** JSON-serialised {@code Map<String, Object>} */
    private String metadata;

    private String traceId;

    private LocalDateTime savedAt;

    // ── v2 observability fields (additive — nullable for older persisted records) ──

    private String decisionVersion;

    private String orchestratorVersion;

    private Integer agentCount;

    private Long decisionLatencyMs;

    // ── v3 consensus fields (additive — nullable for older persisted records) ──

    private Double consensusScore;

    /** JSON-serialised {@code Map<String, Double>} — per-agent weight snapshot */
    private String agentWeightSnapshot;

    // ── v4 adaptive performance fields (additive — nullable for older persisted records) ──

    /** JSON-serialised {@code Map<String, Double>} — adaptive weights used this cycle */
    private String adaptiveAgentWeights;

    // ── v5 market regime field (additive — nullable for older persisted records) ──

    /** Enum name of the detected {@link com.agentplatform.common.model.MarketRegime} */
    private String marketRegime;

    // ── v6 AI strategist field (additive — nullable for older persisted records) ──

    /** Strategic reasoning text produced by {@code AIStrategistService} */
    private String aiReasoning;

    // ── v7 divergence awareness field (additive — nullable for older persisted records) ──

    /** True when AI signal diverges from consensus signal */
    private Boolean divergenceFlag;

    // ── v8 scalping intelligence fields (additive — nullable for older persisted records) ──

    private String  tradingSession;
    private Double  entryPrice;
    private Double  targetPrice;
    private Double  stopLoss;
    private Integer estimatedHoldMinutes;

    // ── v8 P&L outcome fields — populated by outcome tracker ~5–10 min after decision ──

    /** (exitPrice − entryPrice) / entryPrice × 100 — positive = profitable */
    private Double  outcomePercent;

    /** Actual hold duration in minutes when outcome was resolved */
    private Integer outcomeHoldMinutes;

    /** False/null = open; true = outcome recorded */
    private Boolean outcomeResolved;
}
