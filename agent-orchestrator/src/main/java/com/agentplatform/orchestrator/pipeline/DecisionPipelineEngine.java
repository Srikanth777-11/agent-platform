package com.agentplatform.orchestrator.pipeline;

import com.agentplatform.common.cognition.DirectionalBias;
import com.agentplatform.common.cognition.MomentumState;
import com.agentplatform.common.cognition.TradingSession;
import com.agentplatform.common.risk.PositionSizingDecision;
import com.agentplatform.common.risk.PositionSizingEngine;
import com.agentplatform.common.consensus.ConsensusEngine;
import com.agentplatform.common.consensus.ConsensusResult;
import com.agentplatform.common.event.MarketDataEvent;
import com.agentplatform.common.guard.DivergenceGuard;
import com.agentplatform.common.model.AIStrategyDecision;
import com.agentplatform.common.model.AnalysisResult;
import com.agentplatform.common.model.FinalDecision;
import com.agentplatform.common.model.MarketRegime;
import com.agentplatform.orchestrator.guard.ConsensusIntegrationGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Phase-39: DecisionPipelineEngine — extracted from OrchestratorService.
 *
 * <p>Owns all gate logic (Phases 35, 36, and future phases 40–43).
 * OrchestratorService is now a pure coordinator: it assembles inputs and
 * delegates the gating + decision building to this engine.
 *
 * <h3>Gate execution order</h3>
 * <ol>
 *   <li>DivergenceGuard override evaluation</li>
 *   <li>Phase-35 Gate 1: Authority Chain — AI overrides downward only</li>
 *   <li>Phase-35 Gate 2: Session gate — MIDDAY/OFF_HOURS → WATCH</li>
 *   <li>Phase-35 Gate 3: Directional bias gate</li>
 *   <li>Phase-35 Gate 4: Divergence penalty</li>
 *   <li>Phase-35 Gate 5: Multi-filter</li>
 *   <li>Phase-36: TradeEligibilityGuard — hard eligibility</li>
 * </ol>
 */
@Component
public class DecisionPipelineEngine {

    private static final Logger log = LoggerFactory.getLogger(DecisionPipelineEngine.class);
    private static final String DECISION_VERSION = "1.0";

    @Value("${spring.application.version:1.0.0}")
    private String orchestratorVersion;

    private final ConsensusEngine consensusEngine;

    public DecisionPipelineEngine(ConsensusEngine consensusEngine) {
        this.consensusEngine = consensusEngine;
    }

    /**
     * Runs all discipline gates and builds the {@link FinalDecision}.
     * This is the single entry point for all gate logic.
     */
    public FinalDecision buildDecision(MarketDataEvent event, List<AnalysisResult> results,
                                       long latencyMs, Map<String, Double> adaptiveWeights,
                                       MarketRegime regime, AIStrategyDecision aiDecision,
                                       TradingSession session, int divergenceStreak,
                                       DirectionalBias directionalBias, MomentumState momentumState,
                                       GovernorDecision governorDecision, boolean replayMode) {

        // ── Phase-43 Gate-0: DailyRiskGovernor — pre-empts all other gates ──
        if (governorDecision == GovernorDecision.HALT) {
            log.warn("[Phase43-DailyRiskGovernor] HALT — session kill switch active. Forcing HOLD. traceId={}", event.traceId());
            // Build minimal HOLD decision without running any other gate
            ConsensusResult haltConsensus = ConsensusIntegrationGuard.resolve(results, consensusEngine, adaptiveWeights);
            Map<String, Object> haltMeta = new HashMap<>();
            haltMeta.put("agentCount", results.size());
            haltMeta.put("decision_mode", replayMode ? "REPLAY_CONSENSUS_ONLY" : "LIVE_AI");
            haltMeta.put("governorDecision", "HALT");
            return FinalDecision.v9(event.symbol(), event.triggeredAt(), results,
                "HOLD", 0.0, haltMeta, event.traceId(), DECISION_VERSION, orchestratorVersion,
                results.size(), latencyMs, haltConsensus.normalizedConfidence(),
                haltConsensus.agentWeights(), adaptiveWeights, regime,
                "DailyRiskGovernor: session halted", false,
                session != null ? session.name() : null,
                null, null, null, null,
                com.agentplatform.common.model.TradeDirection.FLAT.name(),
                directionalBias != null ? directionalBias.name() : null);
        }

        // Consensus runs as safety guardrail — its normalizedConfidence is stored
        // alongside the AI signal for observability and divergence tracking.
        ConsensusResult consensus = ConsensusIntegrationGuard.resolve(results, consensusEngine, adaptiveWeights);

        // v7 — divergence awareness: AI signal vs consensus signal (always computed pre-override)
        boolean divergenceFlag = !aiDecision.finalSignal().equals(consensus.finalSignal());

        // Path 12 — Divergence Safety Override: may replace AI signal or dampen confidence
        DivergenceGuard.OverrideResult override = DivergenceGuard.evaluate(
            aiDecision.finalSignal(), aiDecision.confidence(),
            consensus.finalSignal(), consensus.normalizedConfidence(),
            divergenceFlag, divergenceStreak);

        if (override.overrideApplied()) {
            log.info("[DivergenceGuard] Override applied. reason={} traceId={}", override.reason(), event.traceId());
        }

        // ── Phase-35 Gate 1: Authority Chain — AI can only be overridden DOWNWARD (to WATCH/HOLD) ──
        String workingSignal     = override.finalSignal();
        double workingConfidence = override.confidence();
        boolean overrideKept     = override.overrideApplied();
        String  overrideReason   = override.reason();

        boolean overrideIsPassive = "WATCH".equals(workingSignal) || "HOLD".equals(workingSignal);
        if (overrideKept && !overrideIsPassive) {
            log.info("[Phase35-AuthorityChain] Blocked upward override. consensus={} keeping ai={} traceId={}",
                workingSignal, aiDecision.finalSignal(), event.traceId());
            workingSignal     = aiDecision.finalSignal();
            workingConfidence = aiDecision.confidence();
            overrideKept      = false;
            overrideReason    = null;
        }

        // ── Phase-35 Gate 2: Session gate — MIDDAY_CONSOLIDATION / OFF_HOURS → force WATCH ──
        if (session == TradingSession.MIDDAY_CONSOLIDATION || session == TradingSession.OFF_HOURS) {
            if ("BUY".equals(workingSignal) || "SELL".equals(workingSignal)) {
                log.info("[Phase35-SessionGate] {} forced WATCH. session={} traceId={}",
                    workingSignal, session, event.traceId());
                workingSignal = "WATCH";
            }
        }

        // ── Phase-40: Micro-session confidence and momentum gates ──
        if (session == TradingSession.OPENING_PHASE_1) {
            // Price discovery: require STRONG bias and higher confidence floor
            boolean weakBias = directionalBias == DirectionalBias.BULLISH
                || directionalBias == DirectionalBias.BEARISH
                || directionalBias == DirectionalBias.NEUTRAL;
            if (("BUY".equals(workingSignal) || "SELL".equals(workingSignal)) && weakBias) {
                log.info("[Phase40-Phase1Gate] {} blocked — STRONG bias required in OPENING_PHASE_1. bias={} traceId={}",
                    workingSignal, directionalBias, event.traceId());
                workingSignal = "WATCH";
            }
            if (("BUY".equals(workingSignal) || "SELL".equals(workingSignal)) && workingConfidence < 0.70) {
                log.info("[Phase40-Phase1Gate] {} blocked — confidence={} below 0.70 in OPENING_PHASE_1. traceId={}",
                    workingSignal, String.format("%.3f", workingConfidence), event.traceId());
                workingSignal = "WATCH";
            }
            if (("BUY".equals(workingSignal) || "SELL".equals(workingSignal)) && divergenceFlag) {
                log.info("[Phase40-Phase1Gate] {} blocked — divergenceFlag true in OPENING_PHASE_1. traceId={}", workingSignal, event.traceId());
                workingSignal = "WATCH";
            }
        } else if (session == TradingSession.OPENING_PHASE_3) {
            // Continuation/trap: block on weakening momentum or divergence streak
            boolean momentumWeak = momentumState == MomentumState.WEAKENING
                || momentumState == MomentumState.FALLING;
            if (("BUY".equals(workingSignal) || "SELL".equals(workingSignal)) && momentumWeak) {
                log.info("[Phase40-Phase3Gate] {} blocked — momentum={} in OPENING_PHASE_3. traceId={}",
                    workingSignal, momentumState, event.traceId());
                workingSignal = "WATCH";
            }
            if (("BUY".equals(workingSignal) || "SELL".equals(workingSignal)) && divergenceStreak >= 1) {
                log.info("[Phase40-Phase3Gate] {} blocked — divergenceStreak={} in OPENING_PHASE_3. traceId={}",
                    workingSignal, divergenceStreak, event.traceId());
                workingSignal = "WATCH";
            }
        }

        // ── Phase-35 Gate 3: Directional bias gate ──
        if ("BUY".equals(workingSignal) &&
                (directionalBias == DirectionalBias.BEARISH || directionalBias == DirectionalBias.STRONG_BEARISH)) {
            log.info("[Phase35-BiasGate] BUY blocked in {} market. traceId={}", directionalBias, event.traceId());
            workingSignal = "WATCH";
        }
        if ("SELL".equals(workingSignal) &&
                (directionalBias == DirectionalBias.BULLISH || directionalBias == DirectionalBias.STRONG_BULLISH)) {
            log.info("[Phase35-BiasGate] SELL blocked in {} market. traceId={}", directionalBias, event.traceId());
            workingSignal = "WATCH";
        }

        // ── Phase-35 Gate 4: Divergence penalty ──
        if (divergenceFlag) {
            workingConfidence *= 0.85;
        }
        if (divergenceStreak >= 2) {
            log.info("[Phase35-DivergencePenalty] streak={} forced WATCH. traceId={}", divergenceStreak, event.traceId());
            workingSignal = "WATCH";
        }

        // ── Phase-35 Gate 5: Multi-filter — BUY/SELL only if all conditions met ──
        boolean sessionActive = session != null && session.isActiveScalpingWindow();
        if (("BUY".equals(workingSignal) || "SELL".equals(workingSignal))
                && (workingConfidence < 0.65 || divergenceFlag || !sessionActive)) {
            log.info("[Phase35-MultiFilter] Filtered to WATCH. confidence={} divergence={} session={} traceId={}",
                String.format("%.3f", workingConfidence), divergenceFlag, session, event.traceId());
            workingSignal = "WATCH";
        }

        // ── Phase-36: TradeEligibilityGuard — hard eligibility gate (BUY/SELL must pass ALL conditions) ──
        if ("BUY".equals(workingSignal)) {
            boolean buyEligible = sessionActive
                && (regime == MarketRegime.VOLATILE || regime == MarketRegime.TRENDING)
                && (directionalBias == DirectionalBias.BULLISH || directionalBias == DirectionalBias.STRONG_BULLISH)
                && workingConfidence >= 0.65
                && !divergenceFlag;
            if (!buyEligible) {
                log.info("[Phase36-EligibilityGuard] BUY blocked. session={} regime={} bias={} confidence={} divergence={} traceId={}",
                    session, regime, directionalBias, String.format("%.3f", workingConfidence), divergenceFlag, event.traceId());
                workingSignal = "WATCH";
            }
        } else if ("SELL".equals(workingSignal)) {
            // Phase-40: SELL allowed in OPENING_PHASE_1/2 (and legacy OPENING_BURST), not in PHASE_3
            boolean sellInOpeningWindow = session == TradingSession.OPENING_PHASE_1
                || session == TradingSession.OPENING_PHASE_2
                || session == TradingSession.OPENING_BURST;
            boolean sellEligible = sellInOpeningWindow
                && regime == MarketRegime.VOLATILE
                && (directionalBias == DirectionalBias.BEARISH || directionalBias == DirectionalBias.STRONG_BEARISH)
                && workingConfidence >= 0.65
                && !divergenceFlag;
            if (!sellEligible) {
                log.info("[Phase36-EligibilityGuard] SELL blocked. session={} regime={} bias={} confidence={} divergence={} traceId={}",
                    session, regime, directionalBias, String.format("%.3f", workingConfidence), divergenceFlag, event.traceId());
                workingSignal = "WATCH";
            }
        }

        String finalReasoning = overrideKept
            ? aiDecision.reasoning() + " [OVERRIDE: " + overrideReason + "]"
            : aiDecision.reasoning();

        Map<String, Long> signalVotes = results.stream()
            .collect(Collectors.groupingBy(AnalysisResult::signal, Collectors.counting()));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("agentCount", results.size());
        metadata.put("signalVotes", signalVotes);
        metadata.put("decision_mode", replayMode ? "REPLAY_CONSENSUS_ONLY" : "LIVE_AI");
        // Phase-43: record governor decision for operator visibility
        double governorMultiplier = 1.0;
        if (governorDecision != null && governorDecision != GovernorDecision.ALLOW) {
            metadata.put("governorDecision", governorDecision.name());
            if (governorDecision == GovernorDecision.REDUCE_SIZE) {
                governorMultiplier = 0.5;
                metadata.put("sizeMultiplier", governorMultiplier);
            }
        }

        // Phase-42: PositionSizingEngine — compute risk-adjusted lot guidance
        if ("BUY".equals(workingSignal) || "SELL".equals(workingSignal)) {
            PositionSizingDecision sizing = PositionSizingEngine.compute(
                workingConfidence, regime, directionalBias, divergenceFlag,
                0.0,  // edgeWinRate = 0.0 until Phase-38b provides live registry data
                session, governorMultiplier);
            metadata.put("positionRiskPercent", sizing.riskPercent());
            metadata.put("lotMultiplier",       sizing.lotMultiplier());
            metadata.put("sizingReasoning",     sizing.reasoning());
            log.info("[Phase42-PositionSizing] signal={} risk={}% lotMult={} traceId={}",
                workingSignal, String.format("%.3f", sizing.riskPercent()),
                String.format("%.2f", sizing.lotMultiplier()), event.traceId());
        }

        // Phase-33: resolve tradeDirection from final working signal
        String tradeDirection = com.agentplatform.common.model.TradeDirection.fromSignal(workingSignal).name();

        return FinalDecision.v9(
            event.symbol(),
            event.triggeredAt(),
            results,
            workingSignal,
            workingConfidence,
            metadata,
            event.traceId(),
            DECISION_VERSION,
            orchestratorVersion,
            results.size(),
            latencyMs,
            consensus.normalizedConfidence(),
            consensus.agentWeights(),
            adaptiveWeights,
            regime,
            finalReasoning,
            divergenceFlag,
            session != null ? session.name() : null,
            aiDecision.entryPrice(),
            aiDecision.targetPrice(),
            aiDecision.stopLoss(),
            aiDecision.estimatedHoldMinutes(),
            tradeDirection,
            directionalBias != null ? directionalBias.name() : null
        );
    }
}
