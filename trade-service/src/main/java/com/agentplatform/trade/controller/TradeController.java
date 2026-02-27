package com.agentplatform.trade.controller;

import com.agentplatform.common.model.TradeReflectionStats;
import com.agentplatform.trade.dto.ActiveTradeResponse;
import com.agentplatform.trade.dto.TradeExitRequest;
import com.agentplatform.trade.dto.TradeStartRequest;
import com.agentplatform.trade.model.TradeSession;
import com.agentplatform.trade.service.TradeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Operator-facing REST API for trade lifecycle and risk awareness.
 * No orchestrator linkage.
 */
@RestController
@RequestMapping("/api/v1/trade")
public class TradeController {

    private static final Logger log = LoggerFactory.getLogger(TradeController.class);

    private final TradeService tradeService;

    public TradeController(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    @PostMapping("/start")
    public Mono<ResponseEntity<TradeSession>> startTrade(@RequestBody TradeStartRequest request) {
        log.info("Trade start requested. symbol={} price={}", request.symbol(), request.entryPrice());
        return tradeService.startTrade(request)
            .map(ResponseEntity::ok)
            .doOnError(e -> log.error("Start trade endpoint error. symbol={}", request.symbol(), e));
    }

    @PostMapping("/exit")
    public Mono<ResponseEntity<TradeSession>> exitTrade(@RequestBody TradeExitRequest request) {
        log.info("Trade exit requested. symbol={} exitPrice={}", request.symbol(), request.exitPrice());
        return tradeService.exitTrade(request)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build())
            .doOnError(e -> log.error("Exit trade endpoint error. symbol={}", request.symbol(), e));
    }

    @GetMapping("/active/{symbol}")
    public Mono<ResponseEntity<ActiveTradeResponse>> activeTrade(@PathVariable String symbol) {
        log.info("Active trade query. symbol={}", symbol);
        return tradeService.getActiveTrade(symbol)
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build())
            .doOnError(e -> log.error("Active trade endpoint error. symbol={}", symbol, e));
    }

    @GetMapping("/history")
    public Flux<TradeSession> tradeHistory() {
        log.info("Trade history query received");
        return tradeService.getTradeHistory();
    }

    @GetMapping("/reflection-stats")
    public Mono<ResponseEntity<Map<String, TradeReflectionStats>>> reflectionStats() {
        log.info("Reflection stats query received");
        return Mono.just(ResponseEntity.ok(tradeService.getReflectionStats()));
    }

    @GetMapping("/health")
    public Mono<ResponseEntity<String>> health() {
        return Mono.just(ResponseEntity.ok("OK"));
    }
}
