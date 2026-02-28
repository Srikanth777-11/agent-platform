package com.agentplatform.orchestrator.service;

import com.agentplatform.common.classifier.MarketRegimeClassifier;
import com.agentplatform.common.cognition.CalmTrajectory;
import com.agentplatform.common.cognition.CalmTrajectoryInterpreter;
import com.agentplatform.common.cognition.DirectionalBias;
import com.agentplatform.common.cognition.DivergenceTrajectory;
import com.agentplatform.common.cognition.DivergenceTrajectoryInterpreter;
import com.agentplatform.common.cognition.MomentumState;
import com.agentplatform.common.cognition.StabilityPressureCalculator;
import com.agentplatform.common.cognition.TradingSession;
import com.agentplatform.common.cognition.TradingSessionClassifier;
import com.agentplatform.common.cognition.reflection.ArchitectReflectionInterpreter;
import com.agentplatform.common.cognition.reflection.ArchitectReflectionInterpreter.ReflectionResult;
import com.agentplatform.common.consensus.AgentScoreCalculator;
import com.agentplatform.common.consensus.ConsensusEngine;
import com.agentplatform.common.consensus.ConsensusResult;
import com.agentplatform.common.guard.DivergenceGuard;
import com.agentplatform.common.decision.DecisionEventPublisher;
import com.agentplatform.common.event.MarketDataEvent;
import com.agentplatform.common.model.AIStrategyDecision;
import com.agentplatform.common.model.AnalysisResult;
import com.agentplatform.common.model.Context;
import com.agentplatform.common.model.DecisionContext;
import com.agentplatform.common.model.FinalDecision;
import com.agentplatform.common.model.MarketRegime;
import com.agentplatform.common.trace.TraceContextUtil;
import com.agentplatform.orchestrator.adapter.AgentFeedbackAdapter;
import com.agentplatform.orchestrator.adapter.PerformanceWeightAdapter;
import com.agentplatform.orchestrator.ai.AIStrategistService;
import com.agentplatform.orchestrator.ai.ModelSelector;
import com.agentplatform.orchestrator.guard.ConsensusIntegrationGuard;
import com.agentplatform.orchestrator.logger.DecisionFlowLogger;
import com.agentplatform.orchestrator.pipeline.DailyRiskGovernor;
import com.agentplatform.orchestrator.pipeline.DecisionPipelineEngine;
import com.agentplatform.orchestrator.pipeline.GovernorDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class OrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorService.class);
    private static final String DECISION_VERSION = "1.0";

    @Value("${spring.application.version:1.0.0}")
    private String orchestratorVersion;

    private final WebClient marketDataClient;
    private final WebClient analysisEngineClient;
    private final WebClient historyClient;
    private final ObjectMapper objectMapper;
    private final DecisionEventPublisher decisionEventPublisher;
    private final DecisionFlowLogger decisionFlowLogger;
    private final ConsensusEngine consensusEngine;
    private final PerformanceWeightAdapter performanceWeightAdapter;
    private final AgentFeedbackAdapter agentFeedbackAdapter;
    private final AIStrategistService aiStrategistService;
    // Phase-39: extracted gate + decision logic
    private final DecisionPipelineEngine decisionPipelineEngine;
    // Phase-43: daily intraday risk governor
    private final DailyRiskGovernor dailyRiskGovernor;

    public OrchestratorService(
            WebClient marketDataClient,
            WebClient analysisEngineClient,
            WebClient historyClient,
            ObjectMapper objectMapper,
            DecisionEventPublisher decisionEventPublisher,
            DecisionFlowLogger decisionFlowLogger,
            ConsensusEngine consensusEngine,
            PerformanceWeightAdapter performanceWeightAdapter,
            AgentFeedbackAdapter agentFeedbackAdapter,
            AIStrategistService aiStrategistService,
            DecisionPipelineEngine decisionPipelineEngine,
            DailyRiskGovernor dailyRiskGovernor) {
        this.marketDataClient         = marketDataClient;
        this.analysisEngineClient     = analysisEngineClient;
        this.historyClient            = historyClient;
        this.objectMapper             = objectMapper;
        this.decisionEventPublisher   = decisionEventPublisher;
        this.decisionFlowLogger       = decisionFlowLogger;
        this.consensusEngine          = consensusEngine;
        this.performanceWeightAdapter = performanceWeightAdapter;
        this.agentFeedbackAdapter     = agentFeedbackAdapter;
        this.aiStrategistService      = aiStrategistService;
        this.decisionPipelineEngine   = decisionPipelineEngine;
        this.dailyRiskGovernor        = dailyRiskGovernor;
    }

    public Mono<List<AnalysisResult>> orchestrate(MarketDataEvent event, boolean replayMode) {
        return Mono.defer(() -> {
            final long startTime = System.currentTimeMillis();
            MDC.put("traceId", event.traceId());
            log.info("Orchestration started. symbol={} traceId={}", event.symbol(), event.traceId());

            // Captured after MARKET_DATA_FETCHED; read inside the terminal flatMap.
            final MarketRegime[]    regime          = {MarketRegime.UNKNOWN};
            final TradingSession[]  capturedSession = {TradingSession.OFF_HOURS};
            // Captured after MARKET_DATA_FETCHED; passed to AIStrategistService.
            final Context[]         capturedCtx     = {null};

            Mono<List<AnalysisResult>> pipeline = Mono.just(event)
                .doOnEach(decisionFlowLogger.stage(DecisionFlowLogger.TRIGGER_RECEIVED))
                .flatMap(this::fetchMarketDataAndBuildContext)
                .doOnNext(ctx -> {
                    capturedCtx[0] = ctx;
                    log.info("Market data fetched. pricePoints={}", ctx.prices().size());
                    regime[0] = MarketRegimeClassifier.classify(ctx);
                    log.info("Market regime classified. regime={} traceId={}", regime[0], event.traceId());
                    capturedSession[0] = TradingSessionClassifier.classify(event.triggeredAt());
                    log.info("[Session] tradingSession={} symbol={} traceId={}",
                        capturedSession[0], event.symbol(), event.traceId());
                    // Phase-24: resolve any open outcomes from previous cycles (fire-and-forget)
                    resolveOpenOutcomes(event.symbol(), ctx);
                })
                .doOnEach(decisionFlowLogger.stage(DecisionFlowLogger.MARKET_DATA_FETCHED))
                .flatMap(ctx -> callAnalysisEngine(ctx, replayMode))
                .doOnEach(decisionFlowLogger.stage(DecisionFlowLogger.AGENTS_COMPLETED))
                // Fetch adaptive weights → fetch feedback → evaluate AI strategy → build FinalDecision.
                // The chain stays fully non-blocking: each flatMap composes reactive sources.
                .flatMap(results -> {
                    log.info("Analysis complete. agentsRan={}", results.size());
                    return performanceWeightAdapter.fetchPerformanceWeights(event.traceId())
                        .flatMap(perf ->
                            agentFeedbackAdapter.fetchFeedback(event.traceId())
                                .flatMap(feedback -> {
                                    Map<String, Double> adaptiveWeights =
                                        AgentScoreCalculator.compute(results, perf, feedback, regime[0]);

                                    // ── Phase-33: extract directional bias from TrendAgent metadata ──
                                    DirectionalBias directionalBias = results.stream()
                                        .filter(r -> "TrendAgent".equals(r.agentName()))
                                        .map(r -> r.metadata().get("directionalBias"))
                                        .filter(Objects::nonNull)
                                        .map(v -> DirectionalBias.valueOf(String.valueOf(v)))
                                        .findFirst()
                                        .orElse(DirectionalBias.NEUTRAL);

                                    // ── Phase-40: extract momentumState from TrendAgent metadata ──
                                    MomentumState momentumState = results.stream()
                                        .filter(r -> "TrendAgent".equals(r.agentName()))
                                        .map(r -> r.metadata().get("momentumState"))
                                        .filter(Objects::nonNull)
                                        .map(v -> MomentumState.valueOf(String.valueOf(v)))
                                        .findFirst()
                                        .orElse(MomentumState.UNKNOWN);

                                    // ── Assemble DecisionContext (pre-AI snapshot) ──
                                    double latestClose = capturedCtx[0].prices() != null
                                        && !capturedCtx[0].prices().isEmpty()
                                        ? capturedCtx[0].prices().get(0) : 0.0;
                                    DecisionContext decisionCtx = DecisionContext.assemble(
                                        event.symbol(), event.triggeredAt(), event.traceId(),
                                        regime[0], results, adaptiveWeights, latestClose)
                                        .withTradingSession(capturedSession[0])
                                        .withDirectionalBias(directionalBias)
                                        .withMomentumState(momentumState);

                                    // ── Phase-18 Calm Omega enrichment (pre-AI, derived cognition) ──
                                    double stabilityPressure = StabilityPressureCalculator.compute(results, adaptiveWeights);
                                    CalmTrajectory calmTrajectory = CalmTrajectoryInterpreter.interpret(stabilityPressure, results);
                                    DivergenceTrajectory divergenceTrajectory = DivergenceTrajectoryInterpreter.interpret(results, adaptiveWeights);
                                    DecisionContext omegaCtx = decisionCtx.withCalmOmega(stabilityPressure, calmTrajectory, divergenceTrajectory);
                                    log.debug("[Omega] stabilityPressure={} calmTrajectory={} divergenceTrajectory={} traceId={}",
                                        stabilityPressure, calmTrajectory, divergenceTrajectory, event.traceId());

                                    // ── Phase-19/20/21 Reflection + CalmMood + Persistence (single cognition pass) ──
                                    ReflectionResult reflection = ArchitectReflectionInterpreter.interpret(
                                        results, stabilityPressure, calmTrajectory, divergenceTrajectory,
                                        event.symbol());
                                    DecisionContext reflectedCtx = omegaCtx.withReflection(
                                        reflection.reflectionState(), reflection.calmMood(),
                                        reflection.reflectionPersistence());
                                    log.debug("[Reflection] reflectionState={} calmMood={} reflectionPersistence={} traceId={}",
                                        reflection.reflectionState(), reflection.calmMood(),
                                        reflection.reflectionPersistence(), event.traceId());

                                    // Phase-43: fetch daily risk state before AI call (non-blocking, best-effort)
                                    final GovernorDecision[] governorDecision = {GovernorDecision.ALLOW};
                                    final int divergenceStreak = replayMode ? 0
                                        : computeDivergenceStreak(List.of());

                                    // AI Strategist Layer — skipped in replay mode (consensus-only).
                                    // Phase-34: replay uses consensus signal directly to avoid AI latency.
                                    Mono<AIStrategyDecision> aiMono;
                                    // Phase-44: capture peak-mode context for post-AI enrichment
                                    final DecisionContext[] peakCtxRef = {reflectedCtx};
                                    final boolean[] peakModeRef = {false};
                                    if (replayMode) {
                                        ConsensusResult quickConsensus = ConsensusIntegrationGuard.resolve(
                                            results, consensusEngine, adaptiveWeights);
                                        aiMono = Mono.just(new AIStrategyDecision(
                                            quickConsensus.finalSignal(),
                                            quickConsensus.normalizedConfidence(),
                                            "REPLAY_CONSENSUS_ONLY",
                                            null, null, null, null,
                                            com.agentplatform.common.model.TradeDirection
                                                .fromSignal(quickConsensus.finalSignal()).name()
                                        ));
                                    } else {
                                        // Phase-27: fetch last 3 decisions as strategy memory (non-blocking, best-effort).
                                        // Phase-44: detect peak mode from memory confidence + session + regime.
                                        aiMono = fetchStrategyMemory(event.symbol())
                                            .flatMap(memory -> {
                                                double prevConfidence = 0.65;
                                                if (!memory.isEmpty()) {
                                                    Object c = memory.get(0).getOrDefault("confidenceScore", 0.65);
                                                    if (c instanceof Number n) prevConfidence = n.doubleValue();
                                                }
                                                boolean isPeak = (capturedSession[0] == TradingSession.OPENING_PHASE_1
                                                    || capturedSession[0] == TradingSession.OPENING_PHASE_2)
                                                    && regime[0] == MarketRegime.VOLATILE
                                                    && divergenceStreak == 0
                                                    && prevConfidence >= 0.65;
                                                peakModeRef[0] = isPeak;
                                                DecisionContext activeCtx = isPeak
                                                    ? reflectedCtx.withPeakMode(true) : reflectedCtx;
                                                peakCtxRef[0] = activeCtx;
                                                if (isPeak) {
                                                    log.info("[Phase44-PeakMode] ACTIVE. session={} regime={} prevConf={} traceId={}",
                                                        capturedSession[0], regime[0],
                                                        String.format("%.3f", prevConfidence), event.traceId());
                                                }
                                                return aiStrategistService.evaluate(activeCtx, capturedCtx[0], memory);
                                            });
                                    }
                                    // Phase-43: resolve governor decision reactively, then build decision
                                    Mono<GovernorDecision> governorMono = replayMode
                                        ? Mono.just(GovernorDecision.ALLOW)
                                        : dailyRiskGovernor.evaluate(event.symbol())
                                            .doOnNext(gd -> governorDecision[0] = gd);
                                    return Mono.zip(aiMono, governorMono)
                                        .map(tuple -> {
                                            AIStrategyDecision aiDecision = tuple.getT1();
                                            GovernorDecision govDecision  = tuple.getT2();
                                            decisionFlowLogger.logWithTraceId(
                                                DecisionFlowLogger.AI_STRATEGY_EVALUATED, event.traceId());

                                            long latencyMs = System.currentTimeMillis() - startTime;
                                            // Phase-39: delegate all gate logic to DecisionPipelineEngine
                                            FinalDecision decision = decisionPipelineEngine.buildDecision(
                                                event, results, latencyMs, adaptiveWeights,
                                                regime[0], aiDecision, capturedSession[0],
                                                divergenceStreak, directionalBias, momentumState,
                                                govDecision, replayMode);

                                            // ── Enrich DecisionContext (post-AI snapshot) ──
                                            String modelLabel = ModelSelector.resolveLabel(regime[0], peakModeRef[0]);
                                            DecisionContext enriched = peakCtxRef[0].withAIDecision(
                                                aiDecision, decision.consensusScore(),
                                                decision.divergenceFlag(), modelLabel);
                                            decisionFlowLogger.logDecisionContext(enriched, event.traceId());

                                            log.info("Decision built. finalSignal={} aiSignal={} "
                                                     + "consensusScore={} aiConfidence={} divergenceFlag={} "
                                                     + "latencyMs={} regime={} adaptiveAgents={} "
                                                     + "feedbackAgents={} traceId={}",
                                                     decision.finalSignal(), aiDecision.finalSignal(),
                                                     decision.consensusScore(), aiDecision.confidence(),
                                                     decision.divergenceFlag(),
                                                     decision.decisionLatencyMs(), regime[0],
                                                     adaptiveWeights.size(), feedback.size(),
                                                     decision.traceId());

                                            decisionFlowLogger.logWithTraceId(
                                                DecisionFlowLogger.FINAL_DECISION_CREATED, decision.traceId());
                                            decisionEventPublisher.publish(decision);  // fire-and-forget → notification-service
                                            saveToHistory(decision);                    // fire-and-forget → history-service
                                            decisionFlowLogger.logWithTraceId(
                                                DecisionFlowLogger.EVENTS_DISPATCHED, decision.traceId());
                                            return decision.agents();
                                        });  // closes .map(tuple -> { [Phase-43: zip(aiMono, governorMono)]
                                }));
                })
                .doOnError(e -> log.error("Orchestration failed for symbol={}", event.symbol(), e))
                .onErrorMap(e -> new RuntimeException("Orchestration failed: " + e.getMessage(), e))
                .doFinally(signal -> MDC.clear());

            return TraceContextUtil.<List<AnalysisResult>>withTraceId(pipeline, event.traceId());
        });
    }

    private Mono<Context> fetchMarketDataAndBuildContext(MarketDataEvent event) {
        return marketDataClient.get()
            .uri("/api/v1/market-data/quote/" + event.symbol())
            .header("X-Trace-Id", event.traceId())
            .retrieve()
            .bodyToMono(String.class)
            .map(responseJson -> {
                try {
                    JsonNode node = objectMapper.readTree(responseJson);
                    double latestClose = node.path("latestClose").asDouble(0);
                    double open        = node.path("open").asDouble(0);
                    double high        = node.path("high").asDouble(0);
                    double low         = node.path("low").asDouble(0);
                    long   volume      = node.path("volume").asLong(0);

                    List<Double> prices = new ArrayList<>();
                    node.path("recentClosingPrices").forEach(p -> prices.add(p.asDouble()));

                    Map<String, Object> marketData = new HashMap<>();
                    marketData.put("latestClose", latestClose);
                    marketData.put("open", open);
                    marketData.put("high", high);
                    marketData.put("low", low);
                    marketData.put("volume", volume);

                    return Context.of(event.symbol(), event.triggeredAt(), marketData, prices, event.traceId());
                } catch (Exception e) {
                    throw new RuntimeException("Failed to parse market data response", e);
                }
            });
    }

    private Mono<List<AnalysisResult>> callAnalysisEngine(Context context, boolean replayMode) {
        return analysisEngineClient.post()
            .uri("/api/v1/analyze")
            .header("X-Trace-Id", context.traceId())
            .header("X-Replay-Mode", String.valueOf(replayMode))
            .bodyValue(context)
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<List<AnalysisResult>>() {});
    }

    /**
     * Counts consecutive divergence flags from the front of the memory list
     * (most-recent first). Used by {@link DivergenceGuard} to detect persistent streaks.
     */
    private int computeDivergenceStreak(List<Map<String, Object>> priorDecisions) {
        int streak = 0;
        for (Map<String, Object> d : priorDecisions) {
            if (Boolean.TRUE.equals(d.get("divergenceFlag"))) streak++;
            else break;
        }
        return streak;
    }

    /**
     * Phase-27: fetches the last 3 decisions for the symbol from history-service.
     * Returns an empty list on any error so the pipeline never stalls.
     */
    private Mono<List<Map<String, Object>>> fetchStrategyMemory(String symbol) {
        return historyClient.get()
            .uri("/api/v1/history/recent/{symbol}?limit=3", symbol)
            .retrieve()
            .bodyToFlux(new ParameterizedTypeReference<Map<String, Object>>() {})
            .collectList()
            .onErrorResume(e -> {
                log.warn("[StrategyMemory] Fetch failed (non-critical). symbol={} reason={}", symbol, e.getMessage());
                return Mono.just(List.of());
            });
    }

    /**
     * Phase-24: fire-and-forget outcome resolution.
     *
     * <p>On each new market data fetch, calls history-service with the current price.
     * History-service finds open BUY/SELL decisions with stored {@code entryPrice},
     * computes P&L, and marks them resolved — all in one atomic server-side call.
     *
     * <p>Zero latency impact on the main pipeline — pure side-effect.
     */
    private void resolveOpenOutcomes(String symbol, com.agentplatform.common.model.Context ctx) {
        double currentPrice = ctx.prices() != null && !ctx.prices().isEmpty()
            ? ctx.prices().get(0) : 0.0;
        if (currentPrice <= 0.0) return;

        historyClient.post()
            .uri("/api/v1/history/resolve-outcomes/{symbol}?currentPrice={price}", symbol, currentPrice)
            .retrieve()
            .toBodilessEntity()
            .subscribe(
                r   -> log.debug("[Outcome] Resolve call completed. symbol={} status={}", symbol, r.getStatusCode()),
                err -> log.warn("[Outcome] Resolve call failed (non-critical). symbol={}", symbol, err)
            );
    }

    private void saveToHistory(FinalDecision decision) {
        historyClient.post()
            .uri("/api/v1/history/save")
            .header("X-Trace-Id", decision.traceId())
            .bodyValue(decision)
            .retrieve()
            .toBodilessEntity()
            .subscribe(
                r   -> log.info("History saved. traceId={} status={}", decision.traceId(), r.getStatusCode()),
                err -> log.warn("History save failed (non-critical). traceId={}", decision.traceId(), err)
            );
    }
}
