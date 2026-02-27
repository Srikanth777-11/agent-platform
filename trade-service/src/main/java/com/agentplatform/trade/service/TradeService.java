package com.agentplatform.trade.service;

import com.agentplatform.common.model.ActiveTradeContext;
import com.agentplatform.common.model.AdaptiveRiskState;
import com.agentplatform.common.model.TradeReflectionStats;
import com.agentplatform.common.posture.PostureStabilityState;
import com.agentplatform.common.posture.TradePosture;
import com.agentplatform.common.reflection.ReflectionInterpreter;
import com.agentplatform.common.risk.AdaptiveRiskEngine;
import com.agentplatform.trade.client.HistoryServiceClient;
import com.agentplatform.trade.client.dto.DecisionMetricsResponse;
import com.agentplatform.trade.client.dto.MarketStateResponse;
import com.agentplatform.trade.dto.ActiveTradeResponse;
import com.agentplatform.trade.dto.TradeExitRequest;
import com.agentplatform.trade.dto.TradeStartRequest;
import com.agentplatform.trade.model.TradeSession;
import com.agentplatform.trade.repository.PortfolioSummaryRepository;
import com.agentplatform.trade.repository.RiskSnapshotRepository;
import com.agentplatform.trade.repository.TradeSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Operator intelligence layer for trade lifecycle and risk awareness.
 * Reads projections from history-service (read-only).
 * No orchestrator linkage. No automation logic.
 */
@Service
public class TradeService {

    private static final Logger log = LoggerFactory.getLogger(TradeService.class);

    private final TradeSessionRepository tradeSessionRepository;
    private final RiskSnapshotRepository riskSnapshotRepository;
    private final PortfolioSummaryRepository portfolioSummaryRepository;
    private final HistoryServiceClient historyClient;
    private final AdaptiveRiskEngine riskEngine = new AdaptiveRiskEngine();
    private final Map<String, AdaptiveRiskState> riskStateMap = new ConcurrentHashMap<>();
    private final Map<String, PostureStabilityState> postureStabilityMap = new ConcurrentHashMap<>();
    private final Map<String, TradePosture> postureMap = new ConcurrentHashMap<>();
    private final Map<TradePosture, TradeReflectionStats> reflectionMap = new ConcurrentHashMap<>();

    public TradeService(TradeSessionRepository tradeSessionRepository,
                        RiskSnapshotRepository riskSnapshotRepository,
                        PortfolioSummaryRepository portfolioSummaryRepository,
                        HistoryServiceClient historyClient) {
        this.tradeSessionRepository    = tradeSessionRepository;
        this.riskSnapshotRepository    = riskSnapshotRepository;
        this.portfolioSummaryRepository = portfolioSummaryRepository;
        this.historyClient             = historyClient;
    }

    /**
     * Opens a new trade session for operator tracking.
     */
    public Mono<TradeSession> startTrade(TradeStartRequest request) {
        TradeSession session = new TradeSession();
        session.setSymbol(request.symbol());
        session.setEntryPrice(request.entryPrice());
        session.setEntryTime(LocalDateTime.now(ZoneOffset.UTC));
        session.setEntryConfidence(request.entryConfidence());
        session.setEntryRegime(request.entryRegime());
        session.setEntryMomentum(request.entryMomentum());

        return tradeSessionRepository.save(session)
            .doOnSuccess(s -> log.info("Trade started. id={} symbol={} price={}",
                s.getId(), s.getSymbol(), s.getEntryPrice()))
            .doOnError(e -> log.error("Failed to start trade. symbol={}", request.symbol(), e));
    }

    /**
     * Closes the most recent open trade for the symbol.
     */
    public Mono<TradeSession> exitTrade(TradeExitRequest request) {
        return tradeSessionRepository.findBySymbolAndExitTimeIsNull(request.symbol())
            .next()
            .flatMap(session -> {
                session.setExitTime(LocalDateTime.now(ZoneOffset.UTC));
                session.setExitPrice(request.exitPrice());
                session.setPnl(request.exitPrice() - session.getEntryPrice());
                session.setDurationMs(Duration.between(session.getEntryTime(), session.getExitTime()).toMillis());
                return tradeSessionRepository.save(session);
            })
            .doOnSuccess(s -> {
                if (s != null) {
                    TradePosture posture = postureMap.remove(s.getSymbol());
                    riskStateMap.remove(s.getSymbol());
                    postureStabilityMap.remove(s.getSymbol());
                    if (posture != null && s.getPnl() != null) {
                        reflectionMap.compute(posture, (k, current) ->
                            ReflectionInterpreter.updateStats(current, posture, s.getPnl()));
                        log.info("Reflection updated. posture={} pnl={} stats={}",
                            posture, s.getPnl(), reflectionMap.get(posture));
                    }
                    log.info("Trade exited. id={} symbol={} pnl={}",
                        s.getId(), s.getSymbol(), s.getPnl());
                }
            })
            .doOnError(e -> log.error("Failed to exit trade. symbol={}", request.symbol(), e));
    }

    /**
     * Builds the active trade context with exit awareness and risk envelope
     * by reading projections from history-service.
     */
    public Mono<ActiveTradeResponse> getActiveTrade(String symbol) {
        Mono<TradeSession> sessionMono = tradeSessionRepository
            .findBySymbolAndExitTimeIsNull(symbol)
            .next();

        Mono<DecisionMetricsResponse> metricsMono = historyClient.getDecisionMetrics(symbol);
        Mono<MarketStateResponse> marketStateMono = historyClient.getMarketState(symbol);

        return sessionMono.flatMap(session ->
            Mono.zip(metricsMono.defaultIfEmpty(defaultMetrics(symbol)),
                     marketStateMono.defaultIfEmpty(defaultMarketState(symbol)))
                .map(tuple -> {
                    DecisionMetricsResponse metrics = tuple.getT1();
                    MarketStateResponse state = tuple.getT2();

                    ActiveTradeContext context = new ActiveTradeContext(
                        session.getSymbol(),
                        session.getEntryPrice(),
                        session.getEntryTime(),
                        session.getEntryConfidence(),
                        session.getEntryRegime(),
                        session.getEntryMomentum()
                    );

                    AdaptiveRiskEngine.MetricsInput metricsInput = new AdaptiveRiskEngine.MetricsInput(
                        metrics.lastConfidence(), metrics.confidenceSlope5(),
                        metrics.divergenceStreak(), metrics.momentumStreak());
                    AdaptiveRiskEngine.MomentumInput momentumInput = new AdaptiveRiskEngine.MomentumInput(
                        state.marketState(), state.confidenceTrend(), state.divergenceRatio());

                    AdaptiveRiskState previousState = riskStateMap.get(symbol);
                    PostureStabilityState previousPosture = postureStabilityMap.get(symbol);
                    AdaptiveRiskEngine.AdaptiveEvaluationResult result =
                        riskEngine.evaluateAdaptive(context, metricsInput, momentumInput,
                            previousState, previousPosture);
                    riskStateMap.put(symbol, result.newState());
                    if (result.postureState() != null) {
                        postureStabilityMap.put(symbol, result.postureState());
                    }
                    if (result.exitAwareness().tradePosture() != null) {
                        postureMap.put(symbol, result.exitAwareness().tradePosture());
                    }

                    return new ActiveTradeResponse(context, result.exitAwareness(), result.riskEnvelope());
                })
        );
    }

    /**
     * Returns closed trade history, most recent first.
     */
    public Flux<TradeSession> getTradeHistory() {
        return tradeSessionRepository.findByExitTimeIsNotNullOrderByExitTimeDesc();
    }

    /**
     * Returns posture-level trade reflection statistics.
     */
    public Map<String, TradeReflectionStats> getReflectionStats() {
        Map<String, TradeReflectionStats> result = new HashMap<>();
        reflectionMap.forEach((posture, stats) -> result.put(posture.name(), stats));
        return result;
    }

    // ── Defaults for missing projections ────────────────────────────────────

    private DecisionMetricsResponse defaultMetrics(String symbol) {
        return new DecisionMetricsResponse(symbol, 0.5, 0.0, 0, 0, null);
    }

    private MarketStateResponse defaultMarketState(String symbol) {
        return new MarketStateResponse(symbol, "CALM", "HOLD", 0.5, "STABLE", 0.0, "UNKNOWN", 0);
    }
}
