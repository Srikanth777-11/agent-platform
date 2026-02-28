package com.agentplatform.history.service;

import com.agentplatform.common.model.AgentFeedback;
import com.agentplatform.common.model.AgentPerformanceModel;
import com.agentplatform.common.model.AnalysisResult;
import com.agentplatform.common.model.DecisionContext;
import com.agentplatform.common.model.FinalDecision;
import com.agentplatform.common.model.MarketState;
import com.agentplatform.common.momentum.MomentumStateCalculator;
import com.agentplatform.history.dto.DecisionMetricsDTO;
import com.agentplatform.history.dto.EdgeReportDTO;
import com.agentplatform.history.dto.FeedbackLoopStatusDTO;
import com.agentplatform.history.dto.MarketStateDTO;
import com.agentplatform.history.dto.RecentDecisionMemoryDTO;
import com.agentplatform.history.dto.SnapshotDecisionDTO;
import com.agentplatform.history.model.AgentPerformanceSnapshot;
import com.agentplatform.history.model.DecisionHistory;
import com.agentplatform.history.repository.AgentPerformanceSnapshotRepository;
import com.agentplatform.history.repository.DecisionHistoryRepository;
import com.agentplatform.history.repository.DecisionMetricsProjectionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);

    private final DecisionHistoryRepository repository;
    private final AgentPerformanceSnapshotRepository snapshotRepository;
    private final DecisionMetricsProjectionRepository metricsRepository;
    private final WinConditionRegistryService winConditionRegistry;
    private final ObjectMapper objectMapper;
    private final Sinks.Many<SnapshotDecisionDTO> snapshotSink =
        Sinks.many().multicast().onBackpressureBuffer(64);

    public HistoryService(DecisionHistoryRepository repository,
                          AgentPerformanceSnapshotRepository snapshotRepository,
                          DecisionMetricsProjectionRepository metricsRepository,
                          WinConditionRegistryService winConditionRegistry,
                          ObjectMapper objectMapper) {
        this.repository             = repository;
        this.snapshotRepository     = snapshotRepository;
        this.metricsRepository      = metricsRepository;
        this.winConditionRegistry   = winConditionRegistry;
        this.objectMapper           = objectMapper;
    }

    public Mono<DecisionHistory> save(FinalDecision decision) {
        return Mono.fromCallable(() -> toEntity(decision))
            .flatMap(repository::save)
            .doOnSuccess(h -> {
                log.info("Decision persisted. id={} symbol={} signal={} traceId={}",
                         h.getId(), h.getSymbol(), h.getFinalSignal(), h.getTraceId());
                SnapshotDecisionDTO event = new SnapshotDecisionDTO(
                    h.getSymbol(), h.getFinalSignal(), h.getConfidenceScore(),
                    h.getMarketRegime(), h.getDivergenceFlag(), h.getAiReasoning(), h.getSavedAt(),
                    h.getTradingSession(), h.getEntryPrice(), h.getTargetPrice(),
                    h.getStopLoss(), h.getEstimatedHoldMinutes(),
                    h.getTradeDirection(), h.getDirectionalBias(), h.getOutcomeLabel());
                snapshotSink.tryEmitNext(event);
            })
            .flatMap(saved -> updateProjections(decision, saved)
                .then(Mono.just(saved)))
            .doOnError(e -> log.error("Failed to persist decision. traceId={}", decision.traceId(), e));
    }

    // ── Projection Pipeline (non-fatal) ─────────────────────────────────────

    private Mono<Void> updateProjections(FinalDecision decision, DecisionHistory saved) {
        Mono<Void> agentProjection = updateAgentSnapshots(decision, saved)
            .onErrorResume(e -> {
                log.warn("Agent snapshot projection failed (non-fatal). traceId={}", decision.traceId(), e);
                return Mono.empty();
            });

        Mono<Void> metricsProjection = updateDecisionMetrics(saved)
            .onErrorResume(e -> {
                log.warn("Decision metrics projection failed (non-fatal). traceId={}", decision.traceId(), e);
                return Mono.empty();
            });

        return Mono.when(agentProjection, metricsProjection);
    }

    private Mono<Void> updateAgentSnapshots(FinalDecision decision, DecisionHistory saved) {
        if (decision.agents() == null || decision.agents().isEmpty()) {
            return Mono.empty();
        }

        String finalSignal = decision.finalSignal();
        long latencyMs = decision.decisionLatencyMs() > 0 ? decision.decisionLatencyMs() : 0L;
        String regimeBias = decision.marketRegime() != null ? decision.marketRegime().name() : null;

        return Flux.fromIterable(decision.agents())
            .flatMap(agent -> {
                double win = (finalSignal != null && finalSignal.equals(agent.signal())) ? 1.0 : 0.0;
                return snapshotRepository.upsertAgent(
                    agent.agentName(), agent.confidenceScore(), (double) latencyMs, win, regimeBias);
            })
            .then(normalizeLatencyWeights());
    }

    private Mono<Void> normalizeLatencyWeights() {
        return snapshotRepository.findAll()
            .map(AgentPerformanceSnapshot::getAvgLatencyMs)
            .reduce(Math::max)
            .defaultIfEmpty(1.0)
            .flatMap(maxLatency -> snapshotRepository.normalizeLatencyWeights(
                maxLatency > 0.0 ? maxLatency : 1.0));
    }

    private Mono<Void> updateDecisionMetrics(DecisionHistory saved) {
        String symbol = saved.getSymbol();
        return repository.findBySymbolOrderBySavedAtDesc(symbol)
            .take(5)
            .collectList()
            .flatMap(recent -> {
                if (recent.isEmpty()) return Mono.empty();

                double lastConfidence = recent.get(0).getConfidenceScore();
                double slope5 = computeConfidenceSlope(recent);
                int divStreak = computeDivergenceStreak(recent);
                int momStreak = computeMomentumStreak(recent);

                return metricsRepository.upsertMetrics(symbol, lastConfidence, slope5, divStreak, momStreak);
            });
    }

    private double computeConfidenceSlope(List<DecisionHistory> recent) {
        int n = recent.size();
        if (n < 2) return 0.0;
        // Simple linear regression slope over the window (most-recent = index 0)
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            double x = i;
            double y = recent.get(i).getConfidenceScore();
            sumX += x; sumY += y; sumXY += x * y; sumX2 += x * x;
        }
        double denom = n * sumX2 - sumX * sumX;
        return denom != 0.0 ? (n * sumXY - sumX * sumY) / denom : 0.0;
    }

    private int computeDivergenceStreak(List<DecisionHistory> recent) {
        int streak = 0;
        for (DecisionHistory d : recent) {
            if (Boolean.TRUE.equals(d.getDivergenceFlag())) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }

    private int computeMomentumStreak(List<DecisionHistory> recent) {
        if (recent.isEmpty()) return 0;
        String firstSignal = recent.get(0).getFinalSignal();
        int streak = 0;
        for (DecisionHistory d : recent) {
            if (firstSignal != null && firstSignal.equals(d.getFinalSignal())) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }

    // ── Entity Mapping ──────────────────────────────────────────────────────

    private DecisionHistory toEntity(FinalDecision decision) {
        try {
            DecisionHistory entity = new DecisionHistory();
            entity.setSymbol(decision.symbol());
            entity.setTimestamp(LocalDateTime.ofInstant(decision.timestamp(), ZoneOffset.UTC));
            entity.setAgents(objectMapper.writeValueAsString(decision.agents()));
            entity.setFinalSignal(decision.finalSignal());
            entity.setConfidenceScore(decision.confidenceScore());
            entity.setMetadata(objectMapper.writeValueAsString(decision.metadata()));
            entity.setTraceId(decision.traceId());
            entity.setSavedAt(LocalDateTime.now(ZoneOffset.UTC));
            // v2 observability fields — null-safe for legacy FinalDecision instances
            entity.setDecisionVersion(decision.decisionVersion());
            entity.setOrchestratorVersion(decision.orchestratorVersion());
            entity.setAgentCount(decision.agentCount() > 0 ? decision.agentCount() : null);
            entity.setDecisionLatencyMs(decision.decisionLatencyMs() > 0 ? decision.decisionLatencyMs() : null);
            // v3 consensus fields — null-safe for legacy FinalDecision instances
            entity.setConsensusScore(decision.consensusScore() > 0.0 ? decision.consensusScore() : null);
            entity.setAgentWeightSnapshot(decision.agentWeightSnapshot() != null
                ? objectMapper.writeValueAsString(decision.agentWeightSnapshot()) : null);
            // v4 adaptive performance fields — null-safe for legacy FinalDecision instances
            entity.setAdaptiveAgentWeights(decision.adaptiveAgentWeights() != null
                ? objectMapper.writeValueAsString(decision.adaptiveAgentWeights()) : null);
            // v5 market regime field — null-safe for legacy FinalDecision instances
            entity.setMarketRegime(decision.marketRegime() != null
                ? decision.marketRegime().name() : null);
            // v6 AI strategist reasoning — null-safe for legacy FinalDecision instances
            entity.setAiReasoning(decision.aiReasoning());
            // v7 divergence awareness — null-safe for legacy FinalDecision instances
            entity.setDivergenceFlag(decision.divergenceFlag());
            // v8 scalping intelligence — null-safe for legacy FinalDecision instances
            entity.setTradingSession(decision.tradingSession());
            entity.setEntryPrice(decision.entryPrice());
            entity.setTargetPrice(decision.targetPrice());
            entity.setStopLoss(decision.stopLoss());
            entity.setEstimatedHoldMinutes(decision.estimatedHoldMinutes());
            // v9 directional bias — null-safe for legacy FinalDecision instances
            entity.setTradeDirection(decision.tradeDirection());
            entity.setDirectionalBias(decision.directionalBias());
            // v10 decision mode — extracted from metadata (Phase-34)
            if (decision.metadata() != null) {
                Object mode = decision.metadata().get("decision_mode");
                entity.setDecisionMode(mode != null ? String.valueOf(mode) : "LIVE_AI");
            } else {
                entity.setDecisionMode("LIVE_AI");
            }
            return entity;
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize FinalDecision for persistence", e);
        }
    }

    /**
     * Alternative mapping path from a unified {@link DecisionContext} snapshot.
     *
     * <p>This method populates the same {@link DecisionHistory} entity as
     * {@link #toEntity(FinalDecision)} but reads fields from the enriched
     * {@code DecisionContext} instead. The database schema is unchanged — both
     * paths produce identical row shapes.
     *
     * <p><strong>Not yet wired into the save pipeline.</strong> The existing
     * {@link #save(FinalDecision)} path remains the active persistence flow.
     * This method is provided so that future evolutions can switch the persistence
     * source without a schema migration.
     */
    DecisionHistory toEntity(DecisionContext ctx) {
        try {
            DecisionHistory entity = new DecisionHistory();
            entity.setSymbol(ctx.symbol());
            entity.setTimestamp(LocalDateTime.ofInstant(ctx.timestamp(), ZoneOffset.UTC));
            entity.setAgents(objectMapper.writeValueAsString(ctx.agentResults()));
            entity.setFinalSignal(ctx.aiDecision() != null ? ctx.aiDecision().finalSignal() : null);
            entity.setConfidenceScore(ctx.aiDecision() != null ? ctx.aiDecision().confidence() : 0.0);
            entity.setTraceId(ctx.traceId());
            entity.setSavedAt(LocalDateTime.now(ZoneOffset.UTC));
            entity.setAgentCount(ctx.agentResults() != null ? ctx.agentResults().size() : null);
            entity.setConsensusScore(ctx.consensusScore());
            entity.setAdaptiveAgentWeights(ctx.adaptiveWeights() != null
                ? objectMapper.writeValueAsString(ctx.adaptiveWeights()) : null);
            entity.setMarketRegime(ctx.regime() != null ? ctx.regime().name() : null);
            entity.setAiReasoning(ctx.aiDecision() != null ? ctx.aiDecision().reasoning() : null);
            entity.setDivergenceFlag(ctx.divergenceFlag());
            return entity;
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize DecisionContext for persistence", e);
        }
    }

    // ── Agent Performance (rewired to snapshot table) ───────────────────────

    /**
     * Reads pre-aggregated agent performance from the snapshot table.
     * Eliminates full-table scans of {@code decision_history}.
     *
     * @return map of agentName → {@link AgentPerformanceModel}; empty when no snapshots exist
     */
    public Mono<Map<String, AgentPerformanceModel>> getAgentPerformance() {
        return snapshotRepository.findAll()
            .collectList()
            .map(snapshots -> {
                Map<String, AgentPerformanceModel> result = new HashMap<>();
                for (AgentPerformanceSnapshot s : snapshots) {
                    result.put(s.getAgentName(), new AgentPerformanceModel(
                        s.getAgentName(),
                        s.getHistoricalAccuracyScore(),
                        s.getLatencyWeight(),
                        s.getTotalDecisions()
                    ));
                }
                return result;
            })
            .doOnSuccess(m -> log.info("Agent performance read from snapshot. agents={}", m.size()))
            .doOnError(e -> log.error("Failed to read agent performance snapshot", e));
    }

    /**
     * Returns per-agent feedback using market-truth win rates derived from resolved
     * P&amp;L outcomes instead of AI-alignment signal matching.
     *
     * <p>Agents with ≥5 resolved outcomes use their real market win rate.
     * Agents below that threshold receive a neutral fallback of 0.5 so they
     * are not penalised before sufficient data exists.
     *
     * @return map of agentName → {@link AgentFeedback} with real win rates
     */
    public Mono<Map<String, AgentFeedback>> getAgentFeedback() {
        return Mono.zip(
            snapshotRepository.findAll().collectList(),
            computeMarketTruthWinRates()
        ).map(tuple -> {
            List<AgentPerformanceSnapshot> snapshots = tuple.getT1();
            Map<String, double[]> marketWinRates = tuple.getT2();

            Map<String, AgentFeedback> result = new HashMap<>();
            for (AgentPerformanceSnapshot s : snapshots) {
                double[] wr = marketWinRates.get(s.getAgentName());
                double winRate = (wr != null && wr[1] >= 5) ? wr[0] / wr[1] : 0.5;
                result.put(s.getAgentName(), new AgentFeedback(
                    s.getAgentName(),
                    winRate,
                    s.getAvgConfidence(),
                    s.getAvgLatencyMs(),
                    s.getTotalDecisions()
                ));
            }
            return result;
        })
        .doOnSuccess(m -> log.info("Agent feedback (market-truth) computed. agents={}", m.size()))
        .doOnError(e -> log.error("Failed to compute market-truth agent feedback", e));
    }

    /**
     * Returns per-agent feedback loop status for operator visibility.
     * Shows whether each agent's adaptive weight is driven by real market
     * outcomes or the neutral fallback.
     */
    public Flux<FeedbackLoopStatusDTO> getFeedbackLoopStatus() {
        return Mono.zip(
            snapshotRepository.findAll().collectList(),
            computeMarketTruthWinRates()
        ).flatMapMany(tuple -> {
            List<AgentPerformanceSnapshot> snapshots = tuple.getT1();
            Map<String, double[]> marketWinRates = tuple.getT2();

            return Flux.fromIterable(snapshots).map(s -> {
                double[] wr = marketWinRates.get(s.getAgentName());
                boolean hasMarketTruth = wr != null && wr[1] >= 5;
                double winRate  = hasMarketTruth ? wr[0] / wr[1] : 0.5;
                int sampleSize  = wr != null ? (int) wr[1] : 0;
                String source   = hasMarketTruth ? "market-truth" : "fallback";
                return new FeedbackLoopStatusDTO(
                    s.getAgentName(),
                    round(winRate),
                    sampleSize,
                    round(s.getHistoricalAccuracyScore()),
                    source
                );
            });
        })
        .doOnComplete(() -> log.debug("Feedback loop status computed"))
        .doOnError(e -> log.error("Failed to compute feedback loop status", e));
    }

    /**
     * Scans the last 200 resolved P&amp;L outcomes and computes per-agent
     * market-truth win rates.
     *
     * <p>An agent "wins" when its signal direction aligned with the actual
     * profitable outcome (same logic as {@link #rescoreAgentsByOutcome}).
     *
     * @return map of agentName → double[]{wins, total}
     */
    private Mono<Map<String, double[]>> computeMarketTruthWinRates() {
        return repository.findResolvedDecisions(200)
            .collectList()
            .map(decisions -> {
                Map<String, double[]> acc = new HashMap<>();
                for (DecisionHistory d : decisions) {
                    try {
                        List<AnalysisResult> agents = objectMapper.readValue(
                            d.getAgents(), new TypeReference<List<AnalysisResult>>() {});
                        boolean profitable  = d.getOutcomePercent() > 0.10;
                        String  finalSignal = d.getFinalSignal();

                        for (AnalysisResult agent : agents) {
                            acc.computeIfAbsent(agent.agentName(), k -> new double[2]);
                            double[] a = acc.get(agent.agentName());
                            boolean agentCorrect =
                                ("BUY".equals(finalSignal)  &&  profitable && "BUY".equals(agent.signal()))
                             || ("BUY".equals(finalSignal)  && !profitable && !"BUY".equals(agent.signal()))
                             || ("SELL".equals(finalSignal) &&  profitable && "SELL".equals(agent.signal()))
                             || ("SELL".equals(finalSignal) && !profitable && !"SELL".equals(agent.signal()));
                            a[0] += agentCorrect ? 1.0 : 0.0;
                            a[1] += 1.0;
                        }
                    } catch (Exception e) {
                        log.warn("Skipping resolved decision in market-truth win rate computation. id={}",
                                 d.getId(), e);
                    }
                }
                log.debug("Market-truth win rates computed from {} resolved decisions. agents={}",
                          decisions.size(), acc.size());
                return acc;
            });
    }

    // ── Legacy aggregation (retained as private fallbacks) ──────────────────

    private Map<String, AgentPerformanceModel> aggregatePerformanceLegacy(List<DecisionHistory> history) {
        // acc[0] = sumConfidence, acc[1] = sumLatencyMs, acc[2] = count
        Map<String, double[]> acc = new HashMap<>();

        for (DecisionHistory record : history) {
            if (record.getAgents() == null) continue;
            try {
                List<AnalysisResult> agents = objectMapper.readValue(
                    record.getAgents(), new TypeReference<List<AnalysisResult>>() {});
                long latencyMs = record.getDecisionLatencyMs() != null
                    ? record.getDecisionLatencyMs() : 0L;

                for (AnalysisResult agent : agents) {
                    acc.computeIfAbsent(agent.agentName(), k -> new double[3]);
                    double[] a = acc.get(agent.agentName());
                    a[0] += agent.confidenceScore();
                    a[1] += latencyMs;
                    a[2] += 1;
                }
            } catch (Exception e) {
                log.warn("Skipping unparseable agents JSON for history id={}", record.getId(), e);
            }
        }

        // Normalize latency: divide each agent's average by the highest average across all agents
        double maxAvgLatency = acc.values().stream()
            .mapToDouble(a -> a[2] > 0 ? a[1] / a[2] : 0.0)
            .max()
            .orElse(1.0);
        if (maxAvgLatency == 0.0) maxAvgLatency = 1.0;

        Map<String, AgentPerformanceModel> result = new HashMap<>();
        for (Map.Entry<String, double[]> entry : acc.entrySet()) {
            double[] a = entry.getValue();
            long count = (long) a[2];
            double avgConfidence  = count > 0 ? a[0] / count : 0.5;
            double normalizedLatency = count > 0 ? (a[1] / count) / maxAvgLatency : 0.0;
            result.put(entry.getKey(),
                new AgentPerformanceModel(entry.getKey(), avgConfidence, normalizedLatency, count));
        }
        return result;
    }

    private Map<String, AgentFeedback> aggregateFeedbackLegacy(List<DecisionHistory> history) {
        // acc[0] = sumWins, acc[1] = sumConfidence, acc[2] = sumLatencyMs, acc[3] = count
        Map<String, double[]> acc = new HashMap<>();

        for (DecisionHistory record : history) {
            if (record.getAgents() == null) continue;
            try {
                List<AnalysisResult> agents = objectMapper.readValue(
                    record.getAgents(), new TypeReference<List<AnalysisResult>>() {});
                String finalSignal = record.getFinalSignal();
                long latencyMs = record.getDecisionLatencyMs() != null
                    ? record.getDecisionLatencyMs() : 0L;

                for (AnalysisResult agent : agents) {
                    acc.computeIfAbsent(agent.agentName(), k -> new double[4]);
                    double[] a = acc.get(agent.agentName());
                    a[0] += (finalSignal != null && finalSignal.equals(agent.signal())) ? 1.0 : 0.0;
                    a[1] += agent.confidenceScore();
                    a[2] += latencyMs;
                    a[3] += 1;
                }
            } catch (Exception e) {
                log.warn("Skipping unparseable agents JSON in feedback aggregation. id={}",
                         record.getId(), e);
            }
        }

        Map<String, AgentFeedback> result = new HashMap<>();
        for (Map.Entry<String, double[]> entry : acc.entrySet()) {
            double[] a = entry.getValue();
            long count = (long) a[3];
            double winRate       = count > 0 ? a[0] / count : 0.0;
            double avgConfidence = count > 0 ? a[1] / count : 0.0;
            double avgLatencyMs  = count > 0 ? a[2] / count : 0.0;
            result.put(entry.getKey(),
                new AgentFeedback(entry.getKey(), winRate, avgConfidence, avgLatencyMs, count));
        }
        return result;
    }

    // ── New Service Methods ─────────────────────────────────────────────────

    /**
     * Returns raw snapshot rows for the new API endpoint.
     */
    public Flux<AgentPerformanceSnapshot> getAgentPerformanceSnapshots() {
        return snapshotRepository.findAll()
            .doOnComplete(() -> log.debug("Agent performance snapshot query completed"))
            .doOnError(e -> log.error("Failed to fetch agent performance snapshots", e));
    }

    /**
     * Returns per-symbol decision metrics as a DTO.
     */
    public Mono<DecisionMetricsDTO> getDecisionMetrics(String symbol) {
        return metricsRepository.findById(symbol)
            .map(m -> new DecisionMetricsDTO(
                m.getSymbol(),
                m.getLastConfidence(),
                m.getConfidenceSlope5(),
                m.getDivergenceStreak(),
                m.getMomentumStreak(),
                m.getLastUpdated()
            ))
            .doOnSuccess(d -> {
                if (d != null) log.debug("Decision metrics fetched. symbol={}", symbol);
            })
            .doOnError(e -> log.error("Failed to fetch decision metrics. symbol={}", symbol, e));
    }

    // ── Existing Read Methods (unchanged) ───────────────────────────────────

    /**
     * Return the market regime from the most recent persisted decision for the given symbol.
     *
     * @param symbol the stock ticker (e.g. "AAPL")
     * @return the regime name string (e.g. "VOLATILE"); defaults to "UNKNOWN" when no history exists
     */
    public Mono<String> getLatestRegime(String symbol) {
        return repository.findBySymbolOrderBySavedAtDesc(symbol)
            .next()
            .map(h -> h.getMarketRegime() != null ? h.getMarketRegime() : "UNKNOWN")
            .defaultIfEmpty("UNKNOWN")
            .doOnSuccess(r -> log.debug("Latest regime fetched. symbol={} regime={}", symbol, r))
            .doOnError(e -> log.error("Failed to fetch latest regime. symbol={}", symbol, e));
    }

    /**
     * Returns the most recent decision for each distinct symbol as a lightweight
     * {@link SnapshotDecisionDTO} — designed for the card-style UI.
     */
    public Flux<SnapshotDecisionDTO> getLatestSnapshot() {
        return repository.findLatestPerSymbol()
            .map(h -> new SnapshotDecisionDTO(
                h.getSymbol(), h.getFinalSignal(), h.getConfidenceScore(),
                h.getMarketRegime(), h.getDivergenceFlag(), h.getAiReasoning(), h.getSavedAt(),
                h.getTradingSession(), h.getEntryPrice(), h.getTargetPrice(),
                h.getStopLoss(), h.getEstimatedHoldMinutes(),
                h.getTradeDirection(), h.getDirectionalBias(), h.getOutcomeLabel()
            ))
            .doOnComplete(() -> log.debug("Snapshot query completed"))
            .doOnError(e -> log.error("Failed to fetch latest snapshot", e));
    }

    /**
     * Returns a live stream of snapshot events emitted after each persisted decision.
     */
    public Flux<SnapshotDecisionDTO> streamSnapshots() {
        return snapshotSink.asFlux();
    }

    // ── Momentum State Engine ────────────────────────────────────────────────

    /** Number of recent decisions per symbol to analyse for momentum state. */
    private static final int MOMENTUM_WINDOW = 8;

    /**
     * Computes the {@link MarketState} for every distinct symbol in the history.
     */
    public Flux<MarketStateDTO> getMarketState() {
        return repository.findLatestPerSymbol()
            .map(DecisionHistory::getSymbol)
            .flatMap(symbol ->
                repository.findBySymbolOrderBySavedAtDesc(symbol)
                    .take(MOMENTUM_WINDOW)
                    .collectList()
                    .map(decisions -> computeMarketState(symbol, decisions))
            )
            .doOnComplete(() -> log.debug("Market state computation completed"))
            .doOnError(e -> log.error("Failed to compute market state", e));
    }

    private MarketStateDTO computeMarketState(String symbol, List<DecisionHistory> decisions) {
        List<String>  signals          = new ArrayList<>(decisions.size());
        List<Double>  confidences      = new ArrayList<>(decisions.size());
        List<Boolean> divergenceFlags  = new ArrayList<>(decisions.size());
        List<String>  regimes          = new ArrayList<>(decisions.size());

        for (DecisionHistory d : decisions) {
            signals.add(d.getFinalSignal());
            confidences.add(d.getConfidenceScore());
            divergenceFlags.add(d.getDivergenceFlag());
            regimes.add(d.getMarketRegime());
        }

        MarketState state = MomentumStateCalculator.classify(
            signals, confidences, divergenceFlags, regimes);

        double alignment = MomentumStateCalculator.computeSignalAlignment(signals);
        double trendSlope = MomentumStateCalculator.computeConfidenceTrend(confidences);
        double divergenceRatio = MomentumStateCalculator.computeDivergenceRatio(divergenceFlags);

        String confidenceTrend = trendSlope > 0.02 ? "RISING"
            : trendSlope < -0.03 ? "DECLINING" : "STABLE";

        String dominantSignal = signals.stream()
            .filter(s -> s != null)
            .reduce((a, b) -> countOccurrences(signals, a) >= countOccurrences(signals, b) ? a : b)
            .orElse("UNKNOWN");

        String regime = regimes.stream()
            .filter(r -> r != null)
            .findFirst()
            .orElse("UNKNOWN");

        return new MarketStateDTO(
            symbol,
            state.name(),
            dominantSignal,
            Math.round(alignment * 100.0) / 100.0,
            confidenceTrend,
            Math.round(divergenceRatio * 100.0) / 100.0,
            regime,
            decisions.size()
        );
    }

    private static long countOccurrences(List<String> list, String value) {
        return list.stream().filter(s -> value.equals(s)).count();
    }

    // ── Phase-Replay: sequential decision feed for market replay mode ─────────

    public Flux<SnapshotDecisionDTO> getReplayDecisions(String symbol, int limit) {
        return repository.findReplayCandles(symbol, limit)
            .map(d -> new SnapshotDecisionDTO(
                d.getSymbol(), d.getFinalSignal(), d.getConfidenceScore(),
                d.getMarketRegime(), d.getDivergenceFlag(), d.getAiReasoning(), d.getSavedAt(),
                d.getTradingSession(), d.getEntryPrice(), d.getTargetPrice(),
                d.getStopLoss(), d.getEstimatedHoldMinutes(),
                d.getTradeDirection(), d.getDirectionalBias(), d.getOutcomeLabel()));
    }

    // ── Phase-26: Observation Analytics ────────────────────────────────────

    /**
     * Computes the full edge validation report for a symbol.
     * Only resolved trades (outcomeResolved = true) are included.
     */
    public Mono<EdgeReportDTO> getEdgeReport(String symbol) {
        return repository.findBySymbolOrderBySavedAtDesc(symbol)
            .filter(d -> Boolean.TRUE.equals(d.getOutcomeResolved())
                      && d.getOutcomePercent() != null)
            .collectList()
            .map(trades -> buildEdgeReport(symbol, trades));
    }

    private EdgeReportDTO buildEdgeReport(String symbol, java.util.List<DecisionHistory> trades) {
        int n = trades.size();
        if (n == 0) {
            return new EdgeReportDTO(symbol, 0, 0,0,0,0,0,0, 0,0,0, 0,0,0, 0,0, 0, false,
                "Insufficient data — need at least 30 resolved trades");
        }

        // Core metrics
        long wins    = trades.stream().filter(d -> d.getOutcomePercent() > 0).count();
        double wr    = (double) wins / n;
        double lr    = 1.0 - wr;

        double avgGain = trades.stream().filter(d -> d.getOutcomePercent() > 0)
            .mapToDouble(DecisionHistory::getOutcomePercent).average().orElse(0.0);
        double avgLoss = trades.stream().filter(d -> d.getOutcomePercent() <= 0)
            .mapToDouble(d -> Math.abs(d.getOutcomePercent())).average().orElse(0.0);
        double rr      = avgLoss > 0 ? avgGain / avgLoss : 0.0;
        double exp     = (wr * avgGain) - (lr * avgLoss);
        double maxDD   = trades.stream().mapToDouble(DecisionHistory::getOutcomePercent)
            .filter(v -> v < 0).map(Math::abs).max().orElse(0.0);

        // Session win rates
        double obWr  = sessionWinRate(trades, "OPENING_BURST");
        double phWr  = sessionWinRate(trades, "POWER_HOUR");
        double mdWr  = sessionWinRate(trades, "MIDDAY_CONSOLIDATION");

        // Stability correlation
        double stWr  = avgOutcomeForReflection(trades, "ALIGNED");
        double drWr  = avgOutcomeForReflection(trades, "DRIFTING");
        double unWr  = avgOutcomeForReflection(trades, "UNSTABLE");

        // Confidence calibration
        double hcWr  = winRateForConfidence(trades, true);
        double lcWr  = winRateForConfidence(trades, false);

        // Trade frequency (distinct session-days)
        double freq  = n > 0 ? (double) n / Math.max(1,
            trades.stream().map(d -> d.getSavedAt().toLocalDate()).distinct().count() * 2) : 0;

        boolean hasEdge = exp > 0 && rr >= 1.0;
        String verdict = hasEdge
            ? (exp > 0.1 ? "STRONG EDGE — deploy with controlled sizing"
                         : "MARGINAL EDGE — continue observing")
            : exp > 0 ? "WEAK EDGE — improve stop discipline"
                      : "NO EDGE — tune system before trading";

        return new EdgeReportDTO(symbol, n, round(wr), round(avgGain), round(avgLoss),
            round(rr), round(exp), round(maxDD),
            round(obWr), round(phWr), round(mdWr),
            round(stWr), round(drWr), round(unWr),
            round(hcWr), round(lcWr), round(freq), hasEdge, verdict);
    }

    private double sessionWinRate(java.util.List<DecisionHistory> trades, String session) {
        var s = trades.stream().filter(d -> session.equals(d.getTradingSession())).collect(java.util.stream.Collectors.toList());
        if (s.isEmpty()) return 0.0;
        return (double) s.stream().filter(d -> d.getOutcomePercent() > 0).count() / s.size();
    }

    private double avgOutcomeForReflection(java.util.List<DecisionHistory> trades, String state) {
        return trades.stream()
            .mapToDouble(DecisionHistory::getOutcomePercent).average().orElse(0.0);
    }

    private double winRateForConfidence(java.util.List<DecisionHistory> trades, boolean high) {
        var s = trades.stream()
            .filter(d -> high ? d.getConfidenceScore() > 0.75 : d.getConfidenceScore() <= 0.75)
            .collect(java.util.stream.Collectors.toList());
        if (s.isEmpty()) return 0.0;
        return (double) s.stream().filter(d -> d.getOutcomePercent() > 0).count() / s.size();
    }

    /**
     * Phase-41: Classifies the trade outcome into a quality label.
     *
     * <ul>
     *   <li>TARGET_HIT  — price reached or exceeded target before stop</li>
     *   <li>STOP_OUT    — price crossed stop loss</li>
     *   <li>FAST_WIN    — profitable exit within 1 candle (≤ 5 min)</li>
     *   <li>SLOW_WIN    — profitable but took 3+ candles (≥ 15 min)</li>
     *   <li>NO_EDGE     — not profitable (neither target nor stop — expired)</li>
     * </ul>
     */
    private String computeOutcomeLabel(double outcomePercent, long holdMin,
                                       Double stopLoss, Double targetPrice,
                                       Double entryPrice, double currentPrice) {
        if (targetPrice != null && entryPrice != null && entryPrice > 0) {
            double targetPct = (targetPrice - entryPrice) / entryPrice * 100.0;
            if (outcomePercent >= targetPct) return "TARGET_HIT";
        }
        if (stopLoss != null && entryPrice != null && entryPrice > 0) {
            double stopPct = (stopLoss - entryPrice) / entryPrice * 100.0;
            if (outcomePercent <= stopPct) return "STOP_OUT";
        }
        if (outcomePercent > 0 && holdMin <= 5)  return "FAST_WIN";
        if (outcomePercent > 0 && holdMin >= 15) return "SLOW_WIN";
        if (outcomePercent > 0)                  return "FAST_WIN"; // within 5-15 min window
        return "NO_EDGE";
    }

    private double round(double v) { return Math.round(v * 1000.0) / 1000.0; }

    // ── Phase-24: P&L Outcome Learning Loop ────────────────────────────────

    /**
     * Returns recent BUY/SELL decisions for the symbol that do not yet have
     * a resolved P&L outcome. Used by the orchestrator on each cycle to detect
     * open positions that are now ready for outcome resolution.
     *
     * @param symbol   ticker symbol
     * @param sinceMins look-back window in minutes (e.g. 10 = last 10 min)
     */
    /**
     * Phase-27 Strategy Memory: returns the last {@code limit} decisions for a symbol
     * as lightweight {@link RecentDecisionMemoryDTO} projections (4 fields only).
     * Ordered most-recent first. Used by the AI strategist to detect signal flip-flop
     * and regime transitions across cycles.
     */
    public Flux<RecentDecisionMemoryDTO> getRecentDecisions(String symbol, int limit) {
        return repository.findRecentBySymbol(symbol, Math.min(limit, 10))
            .map(d -> new RecentDecisionMemoryDTO(
                d.getFinalSignal(),
                d.getConfidenceScore(),
                d.getDivergenceFlag(),
                d.getMarketRegime()
            ));
    }

    public Flux<SnapshotDecisionDTO> getUnresolvedSignals(String symbol, int sinceMins) {
        LocalDateTime since = LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(sinceMins);
        return repository.findUnresolvedSignals(symbol, since, 10)
            .map(d -> new SnapshotDecisionDTO(
                d.getSymbol(), d.getFinalSignal(), d.getConfidenceScore(),
                d.getMarketRegime(), d.getDivergenceFlag(), d.getAiReasoning(), d.getSavedAt(),
                d.getTradingSession(), d.getEntryPrice(), d.getTargetPrice(),
                d.getStopLoss(), d.getEstimatedHoldMinutes(),
                d.getTradeDirection(), d.getDirectionalBias(), d.getOutcomeLabel()));
    }

    /**
     * Records the P&L outcome for a resolved decision identified by {@code traceId}.
     */
    public Mono<Void> recordOutcome(String traceId, double outcomePercent, int holdMinutes) {
        return repository.findByTraceId(traceId)
            .next()
            .flatMap(entity -> {
                entity.setOutcomePercent(outcomePercent);
                entity.setOutcomeHoldMinutes(holdMinutes);
                entity.setOutcomeResolved(true);
                return repository.save(entity);
            })
            .doOnSuccess(e -> {
                if (e != null) log.info("[Outcome] Recorded. traceId={} outcome={}% holdMin={}",
                    traceId, String.format("%.2f", outcomePercent), holdMinutes);
            })
            .doOnError(e -> log.warn("[Outcome] Failed to record outcome. traceId={}", traceId, e))
            .then();
    }

    /**
     * Phase-24 server-side batch resolver.
     *
     * <p>Finds all open BUY/SELL decisions for the symbol from the last 10 minutes
     * that have a stored {@code entryPrice} and no resolved outcome. Computes
     * P&L from {@code currentPrice}, marks each resolved, and updates the snapshot
     * winRate via the agent projection — closing the actual learning loop.
     *
     * <p>Called by OrchestratorService as a fire-and-forget side-effect on each cycle.
     *
     * @param symbol       ticker symbol
     * @param currentPrice current market price (from latest market data fetch)
     */
    public Mono<Void> resolveOutcomes(String symbol, double currentPrice) {
        if (currentPrice <= 0.0) return Mono.empty();
        LocalDateTime since = LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(10);

        return repository.findUnresolvedSignals(symbol, since, 20)
            .filter(d -> d.getEntryPrice() != null && d.getEntryPrice() > 0.0)
            .flatMap(d -> {
                double outcomePercent = (currentPrice - d.getEntryPrice()) / d.getEntryPrice() * 100.0;
                if ("SELL".equals(d.getFinalSignal())) outcomePercent = -outcomePercent;
                long holdMin = d.getSavedAt() != null
                    ? java.time.Duration.between(d.getSavedAt(),
                        LocalDateTime.now(java.time.ZoneOffset.UTC)).toMinutes()
                    : 0L;
                d.setOutcomePercent(outcomePercent);
                d.setOutcomeHoldMinutes((int) holdMin);
                d.setOutcomeResolved(true);

                // Phase-41: multi-horizon outcome labels
                d.setOutcome1c(outcomePercent); // 1-candle = first resolution price
                if (holdMin >= 15) {
                    d.setOutcome3c(outcomePercent); // 3-candle proxy: held 3+ candles (≥15 min)
                }
                d.setOutcomeLabel(computeOutcomeLabel(outcomePercent, holdMin,
                    d.getStopLoss(), d.getTargetPrice(), d.getEntryPrice(), currentPrice));

                final double finalOutcome   = outcomePercent;
                final String finalSignal    = d.getFinalSignal();
                final String agentsJson     = d.getAgents();
                final long   decisionLatency = d.getDecisionLatencyMs() != null
                                               ? d.getDecisionLatencyMs() : 0L;

                return repository.save(d)
                    .doOnSuccess(saved -> log.info(
                        "[Outcome] Resolved. symbol={} signal={} entry={} current={} outcome={}% holdMin={}",
                        symbol, finalSignal, d.getEntryPrice(), currentPrice,
                        String.format("%.2f", finalOutcome), holdMin))
                    // ── Real P&L learning loop: re-score agents by actual outcome ──────
                    .flatMap(saved -> rescoreAgentsByOutcome(
                        agentsJson, finalSignal, finalOutcome, decisionLatency)
                        // ── Phase-38a: WinConditionRegistry — passive data collection ──
                        .then(winConditionRegistry.record(saved)));
            })
            .then();
    }

    /**
     * Re-scores each agent in the decision based on actual market P&L outcome.
     *
     * <p>This replaces the signal-alignment winRate with a market-truth signal:
     * <ul>
     *   <li>Agent said BUY and trade was profitable → win = 1.0</li>
     *   <li>Agent said SELL/WATCH and BUY trade was unprofitable → win = 1.0 (they were right)</li>
     *   <li>Otherwise → win = 0.0</li>
     * </ul>
     *
     * <p>The threshold for "profitable" is +0.1% (above spread/commission noise).
     * After this call, {@code AgentScoreCalculator.feedbackBoost()} reads a winRate
     * that reflects actual trading performance — not just consensus alignment.
     */
    private Mono<Void> rescoreAgentsByOutcome(String agentsJson, String finalSignal,
                                              double outcomePercent, long latencyMs) {
        if (agentsJson == null || agentsJson.isBlank()) return Mono.empty();
        try {
            List<AnalysisResult> agents = objectMapper.readValue(
                agentsJson, new TypeReference<List<AnalysisResult>>() {});
            boolean profitable = outcomePercent > 0.10;

            return Flux.fromIterable(agents)
                .flatMap(agent -> {
                    // Was this agent's call aligned with the actual market outcome?
                    boolean agentCorrect = ("BUY".equals(finalSignal)  &&  profitable
                                            && "BUY".equals(agent.signal()))
                        || ("BUY".equals(finalSignal)  && !profitable
                                            && !"BUY".equals(agent.signal()))
                        || ("SELL".equals(finalSignal) &&  profitable
                                            && "SELL".equals(agent.signal()))
                        || ("SELL".equals(finalSignal) && !profitable
                                            && !"SELL".equals(agent.signal()));

                    double win = agentCorrect ? 1.0 : 0.0;
                    log.debug("[Outcome] Agent re-score. agent={} signal={} profitable={} win={}",
                        agent.agentName(), agent.signal(), profitable, win);
                    return snapshotRepository.upsertAgent(
                        agent.agentName(), agent.confidenceScore(), (double) latencyMs,
                        win, null /* regimeBias not needed for P&L rescore */);
                })
                .then(normalizeLatencyWeights());
        } catch (Exception e) {
            log.warn("[Outcome] Failed to re-score agents from JSON", e);
            return Mono.empty();
        }
    }
}
