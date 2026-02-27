package com.agentplatform.history.controller;

import com.agentplatform.common.model.AgentFeedback;
import com.agentplatform.common.model.AgentPerformanceModel;
import com.agentplatform.common.model.FinalDecision;
import com.agentplatform.history.dto.DecisionMetricsDTO;
import com.agentplatform.history.dto.EdgeReportDTO;
import com.agentplatform.history.dto.MarketStateDTO;
import com.agentplatform.history.dto.SnapshotDecisionDTO;
import com.agentplatform.history.model.AgentPerformanceSnapshot;
import com.agentplatform.history.service.HistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/history")
public class HistoryController {

    private static final Logger log = LoggerFactory.getLogger(HistoryController.class);

    private final HistoryService historyService;

    public HistoryController(HistoryService historyService) {
        this.historyService = historyService;
    }

    @PostMapping("/save")
    public Mono<ResponseEntity<Void>> save(@RequestBody FinalDecision decision) {
        log.info("Received decision for persistence. symbol={} signal={} traceId={}",
                 decision.symbol(), decision.finalSignal(), decision.traceId());
        return historyService.save(decision)
            .then(Mono.just(ResponseEntity.ok().<Void>build()))
            .doOnError(e -> log.error("Save endpoint error. traceId={}", decision.traceId(), e));
    }

    @GetMapping("/agent-feedback")
    public Mono<ResponseEntity<Map<String, AgentFeedback>>> agentFeedback() {
        log.info("Agent feedback query received");
        return historyService.getAgentFeedback()
            .map(ResponseEntity::ok)
            .doOnError(e -> log.error("Agent feedback endpoint error", e));
    }

    @GetMapping("/agent-performance")
    public Mono<ResponseEntity<Map<String, AgentPerformanceModel>>> agentPerformance() {
        log.info("Agent performance query received");
        return historyService.getAgentPerformance()
            .map(ResponseEntity::ok)
            .doOnError(e -> log.error("Agent performance endpoint error", e));
    }

    @GetMapping("/snapshot")
    public Flux<SnapshotDecisionDTO> snapshot() {
        log.info("Snapshot query received");
        return historyService.getLatestSnapshot();
    }

    @GetMapping("/market-state")
    public Flux<MarketStateDTO> marketState() {
        log.info("Market state query received");
        return historyService.getMarketState();
    }

    @GetMapping("/latest-regime")
    public Mono<ResponseEntity<String>> latestRegime(@RequestParam String symbol) {
        log.info("Latest regime query received. symbol={}", symbol);
        return historyService.getLatestRegime(symbol)
            .map(ResponseEntity::ok)
            .doOnError(e -> log.error("Latest regime endpoint error. symbol={}", symbol, e));
    }

    @GetMapping("/agent-performance-snapshot")
    public Flux<AgentPerformanceSnapshot> agentPerformanceSnapshot() {
        log.info("Agent performance snapshot query received");
        return historyService.getAgentPerformanceSnapshots();
    }

    @GetMapping("/decision-metrics/{symbol}")
    public Mono<ResponseEntity<DecisionMetricsDTO>> decisionMetrics(@PathVariable String symbol) {
        log.info("Decision metrics query received. symbol={}", symbol);
        return historyService.getDecisionMetrics(symbol)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build())
            .doOnError(e -> log.error("Decision metrics endpoint error. symbol={}", symbol, e));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<SnapshotDecisionDTO>> stream() {
        log.info("SSE stream client connected");
        return historyService.streamSnapshots()
            .map(dto -> ServerSentEvent.<SnapshotDecisionDTO>builder()
                .event("snapshot")
                .data(dto)
                .build());
    }

    @GetMapping("/replay/{symbol}")
    public Flux<SnapshotDecisionDTO> replay(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "50") int limit) {
        log.info("Replay candles requested. symbol={} limit={}", symbol, limit);
        return historyService.getReplayDecisions(symbol, limit);
    }

    // ── Phase-26: Observation Analytics ────────────────────────────────────

    @GetMapping("/analytics/edge-report")
    public Mono<ResponseEntity<EdgeReportDTO>> edgeReport(@RequestParam String symbol) {
        log.info("Edge report requested. symbol={}", symbol);
        return historyService.getEdgeReport(symbol)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    // ── Phase-24: P&L Outcome Learning Loop ────────────────────────────────

    @GetMapping("/unresolved/{symbol}")
    public Flux<SnapshotDecisionDTO> unresolvedSignals(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "10") int sinceMins) {
        log.info("Unresolved signals requested. symbol={} sinceMins={}", symbol, sinceMins);
        return historyService.getUnresolvedSignals(symbol, sinceMins);
    }

    @PostMapping("/outcome/{traceId}")
    public Mono<ResponseEntity<Void>> recordOutcome(
            @PathVariable String traceId,
            @RequestBody OutcomeRequest body) {
        log.info("Outcome record requested. traceId={} outcome={}% holdMin={}",
                 traceId, body.outcomePercent(), body.holdMinutes());
        return historyService.recordOutcome(traceId, body.outcomePercent(), body.holdMinutes())
            .then(Mono.just(ResponseEntity.ok().<Void>build()))
            .doOnError(e -> log.error("Outcome record error. traceId={}", traceId, e));
    }

    public record OutcomeRequest(double outcomePercent, int holdMinutes) {}

    @PostMapping("/resolve-outcomes/{symbol}")
    public Mono<ResponseEntity<Void>> resolveOutcomes(
            @PathVariable String symbol,
            @RequestParam double currentPrice) {
        log.info("Batch outcome resolution requested. symbol={} currentPrice={}", symbol, currentPrice);
        return historyService.resolveOutcomes(symbol, currentPrice)
            .then(Mono.just(ResponseEntity.ok().<Void>build()))
            .doOnError(e -> log.error("Outcome resolution error. symbol={}", symbol, e));
    }

    @GetMapping("/health")
    public Mono<ResponseEntity<String>> health() {
        return Mono.just(ResponseEntity.ok("OK"));
    }
}
