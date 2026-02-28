package com.agentplatform.common.model;

import com.agentplatform.common.cognition.CalmMood;
import com.agentplatform.common.cognition.CalmTrajectory;
import com.agentplatform.common.cognition.DirectionalBias;
import com.agentplatform.common.cognition.DivergenceTrajectory;
import com.agentplatform.common.cognition.ReflectionPersistence;
import com.agentplatform.common.cognition.ReflectionState;
import com.agentplatform.common.cognition.TradingSession;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Unified immutable intelligence snapshot representing the full decision state
 * at a single point in the orchestration pipeline.
 *
 * <p><strong>Architectural role:</strong> {@code DecisionContext} consolidates the
 * independent parameters that currently flow through the pipeline as separate method
 * arguments — {@code Context}, {@code MarketRegime}, agent results, adaptive weights,
 * AI decision, divergence state — into a single, self-describing domain object.
 *
 * <p>This record is <strong>additive only</strong>. It does not replace
 * {@link FinalDecision} (the persistence/API contract), {@link Context} (the market
 * data carrier), or {@link AIStrategyDecision} (the AI output). Instead, it
 * <em>composes</em> them into one snapshot that any pipeline participant can reason
 * about without needing to reconstruct state from scattered locals.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Assembled inside {@code OrchestratorService} after agents complete and
 *       adaptive weights are computed — before AI evaluation.</li>
 *   <li>Enriched with {@code aiDecision}, {@code divergenceFlag}, and
 *       {@code modelLabel} after the AI strategist returns.</li>
 *   <li>Consumed (read-only) by any downstream component that needs the full
 *       decision context: logging, future risk engines, audit trails.</li>
 * </ol>
 *
 * <h3>Immutability contract</h3>
 * <p>All fields are set at construction time. Fields that are not yet known when
 * the initial snapshot is built (AI decision, divergence) are nullable and populated
 * via the {@link #withAIDecision} copy-factory. The record is never mutated.
 *
 * <h3>Fields — all derived from data already flowing in the system</h3>
 * <ul>
 *   <li>{@code symbol}               — ticker symbol (from {@link Context})</li>
 *   <li>{@code timestamp}            — trigger time (from {@link com.agentplatform.common.event.MarketDataEvent})</li>
 *   <li>{@code traceId}              — correlation ID for the orchestration cycle</li>
 *   <li>{@code regime}               — classified {@link MarketRegime}</li>
 *   <li>{@code agentResults}         — per-agent analysis outputs</li>
 *   <li>{@code adaptiveWeights}      — history-adjusted agent weights</li>
 *   <li>{@code latestClose}          — most recent closing price (from market data)</li>
 *   <li>{@code consensusScore}       — nullable; populated after consensus guardrail runs</li>
 *   <li>{@code aiDecision}           — nullable; populated after AI strategist evaluates</li>
 *   <li>{@code divergenceFlag}       — nullable; true when AI and consensus disagree</li>
 *   <li>{@code modelLabel}           — nullable; the Claude model used ("haiku-fast" / "sonnet-deep")</li>
 *   <li>{@code stabilityPressure}    — nullable; Phase-18 derived pressure score [0.0–1.0]</li>
 *   <li>{@code calmTrajectory}       — nullable; Phase-18 trajectory enum (STABILIZING/NEUTRAL/DESTABILIZING)</li>
 *   <li>{@code divergenceTrajectory} — nullable; Phase-18 agent consensus direction (RISING/STABLE/COOLING)</li>
 *   <li>{@code reflectionState}      — nullable; Phase-19 self-observation state (ALIGNED/DRIFTING/UNSTABLE)</li>
 *   <li>{@code calmMood}             — nullable; Phase-20 operator emotional state (CALM/BALANCED/PRESSURED)</li>
 *   <li>{@code reflectionPersistence} — nullable; Phase-21 rolling-window label (STABLE/SOFT_DRIFT/HARD_DRIFT/CHRONIC_INSTABILITY)</li>
 *   <li>{@code tradingSession}        — nullable; Phase-22 intraday session (OPENING_BURST/MIDDAY_CONSOLIDATION/POWER_HOUR/OFF_HOURS)</li>
 * </ul>
 */
public record DecisionContext(
    String                    symbol,
    Instant                   timestamp,
    String                    traceId,
    MarketRegime              regime,
    List<AnalysisResult>      agentResults,
    Map<String, Double>       adaptiveWeights,
    double                    latestClose,
    Double                    consensusScore,
    AIStrategyDecision        aiDecision,
    Boolean                   divergenceFlag,
    String                    modelLabel,
    // Phase-18 Calm Omega fields — nullable; populated before AI evaluation
    Double                    stabilityPressure,
    CalmTrajectory            calmTrajectory,
    DivergenceTrajectory      divergenceTrajectory,
    // Phase-19 Architect Reflection field — nullable; populated after Omega enrichment
    ReflectionState           reflectionState,
    // Phase-20 CalmMood field — nullable; derived from all Phase-18/19 cognition signals
    CalmMood                  calmMood,
    // Phase-21 ReflectionPersistence — nullable; rolling-window label (last 5 reflectionStates)
    ReflectionPersistence     reflectionPersistence,
    // Phase-22 TradingSession — nullable; intraday session window for scalping awareness
    TradingSession            tradingSession,
    // Phase-33 DirectionalBias — nullable; 5-vote market direction from TrendAgent
    DirectionalBias           directionalBias
) {

    /**
     * Initial assembly — before AI evaluation. AI-dependent fields are null.
     */
    public static DecisionContext assemble(String symbol, Instant timestamp, String traceId,
                                           MarketRegime regime, List<AnalysisResult> agentResults,
                                           Map<String, Double> adaptiveWeights, double latestClose) {
        return new DecisionContext(
            symbol, timestamp, traceId, regime,
            List.copyOf(agentResults),
            Map.copyOf(adaptiveWeights),
            latestClose,
            null,   // consensusScore      — not yet computed
            null,   // aiDecision          — not yet evaluated
            null,   // divergenceFlag      — not yet known
            null,   // modelLabel          — not yet selected
            null,   // stabilityPressure    — Phase-18: enriched pre-AI
            null,   // calmTrajectory       — Phase-18: enriched pre-AI
            null,   // divergenceTrajectory — Phase-18: enriched pre-AI
            null,   // reflectionState      — Phase-19: enriched after Omega
            null,   // calmMood             — Phase-20: enriched after Reflection
            null,   // reflectionPersistence — Phase-21: enriched with rolling window
            null,   // tradingSession        — Phase-22: enriched with session classification
            null    // directionalBias       — Phase-33: enriched after TrendAgent analysis
        );
    }

    /**
     * Enrichment copy-factory — returns a new snapshot with AI-evaluated fields populated.
     * Called after AIStrategistService returns and consensus guardrail runs.
     *
     * <p>The original instance is never mutated.
     */
    public DecisionContext withAIDecision(AIStrategyDecision aiDecision,
                                          Double consensusScore,
                                          Boolean divergenceFlag,
                                          String modelLabel) {
        return new DecisionContext(
            this.symbol, this.timestamp, this.traceId, this.regime,
            this.agentResults, this.adaptiveWeights, this.latestClose,
            consensusScore, aiDecision, divergenceFlag, modelLabel,
            this.stabilityPressure, this.calmTrajectory, this.divergenceTrajectory,
            this.reflectionState, this.calmMood, this.reflectionPersistence,
            this.tradingSession, this.directionalBias
        );
    }

    /**
     * Phase-18 Omega enrichment copy-factory.
     * Called after {@link #assemble} and before AI evaluation.
     * The original instance is never mutated.
     */
    public DecisionContext withCalmOmega(Double stabilityPressure,
                                         CalmTrajectory calmTrajectory,
                                         DivergenceTrajectory divergenceTrajectory) {
        return new DecisionContext(
            this.symbol, this.timestamp, this.traceId, this.regime,
            this.agentResults, this.adaptiveWeights, this.latestClose,
            this.consensusScore, this.aiDecision, this.divergenceFlag, this.modelLabel,
            stabilityPressure, calmTrajectory, divergenceTrajectory,
            this.reflectionState, this.calmMood, this.reflectionPersistence,
            this.tradingSession, this.directionalBias
        );
    }

    /**
     * Phase-19 Architect Reflection enrichment copy-factory (single state only).
     * Prefer {@link #withReflection(ReflectionState, CalmMood, ReflectionPersistence)}
     * from Phase-21 onward.
     */
    public DecisionContext withReflection(ReflectionState reflectionState) {
        return new DecisionContext(
            this.symbol, this.timestamp, this.traceId, this.regime,
            this.agentResults, this.adaptiveWeights, this.latestClose,
            this.consensusScore, this.aiDecision, this.divergenceFlag, this.modelLabel,
            this.stabilityPressure, this.calmTrajectory, this.divergenceTrajectory,
            reflectionState, this.calmMood, this.reflectionPersistence,
            this.tradingSession, this.directionalBias
        );
    }

    /**
     * Phase-19/20 combined Reflection + CalmMood copy-factory.
     * Backwards-compatible — passes {@code null} for reflectionPersistence.
     */
    public DecisionContext withReflection(ReflectionState reflectionState, CalmMood calmMood) {
        return withReflection(reflectionState, calmMood, null);
    }

    /**
     * Phase-21 primary Reflection copy-factory — carries all three cognition signals.
     * Called with {@link com.agentplatform.common.cognition.reflection.ArchitectReflectionInterpreter.ReflectionResult}.
     */
    public DecisionContext withReflection(ReflectionState reflectionState, CalmMood calmMood,
                                          ReflectionPersistence reflectionPersistence) {
        return new DecisionContext(
            this.symbol, this.timestamp, this.traceId, this.regime,
            this.agentResults, this.adaptiveWeights, this.latestClose,
            this.consensusScore, this.aiDecision, this.divergenceFlag, this.modelLabel,
            this.stabilityPressure, this.calmTrajectory, this.divergenceTrajectory,
            reflectionState, calmMood, reflectionPersistence,
            this.tradingSession, this.directionalBias
        );
    }

    /**
     * Phase-20 CalmMood enrichment copy-factory.
     * The original instance is never mutated.
     */
    public DecisionContext withCalmMood(CalmMood calmMood) {
        return new DecisionContext(
            this.symbol, this.timestamp, this.traceId, this.regime,
            this.agentResults, this.adaptiveWeights, this.latestClose,
            this.consensusScore, this.aiDecision, this.divergenceFlag, this.modelLabel,
            this.stabilityPressure, this.calmTrajectory, this.divergenceTrajectory,
            this.reflectionState, calmMood, this.reflectionPersistence,
            this.tradingSession, this.directionalBias
        );
    }

    /**
     * Phase-22 TradingSession enrichment copy-factory.
     * Called after {@link #assemble} and before {@link #withCalmOmega}.
     */
    public DecisionContext withTradingSession(TradingSession tradingSession) {
        return new DecisionContext(
            this.symbol, this.timestamp, this.traceId, this.regime,
            this.agentResults, this.adaptiveWeights, this.latestClose,
            this.consensusScore, this.aiDecision, this.divergenceFlag, this.modelLabel,
            this.stabilityPressure, this.calmTrajectory, this.divergenceTrajectory,
            this.reflectionState, this.calmMood, this.reflectionPersistence,
            tradingSession, this.directionalBias
        );
    }

    /**
     * Phase-33 DirectionalBias enrichment copy-factory.
     * Called after TrendAgent analysis results are available, before AI evaluation.
     */
    public DecisionContext withDirectionalBias(DirectionalBias directionalBias) {
        return new DecisionContext(
            this.symbol, this.timestamp, this.traceId, this.regime,
            this.agentResults, this.adaptiveWeights, this.latestClose,
            this.consensusScore, this.aiDecision, this.divergenceFlag, this.modelLabel,
            this.stabilityPressure, this.calmTrajectory, this.divergenceTrajectory,
            this.reflectionState, this.calmMood, this.reflectionPersistence,
            this.tradingSession, directionalBias
        );
    }
}
