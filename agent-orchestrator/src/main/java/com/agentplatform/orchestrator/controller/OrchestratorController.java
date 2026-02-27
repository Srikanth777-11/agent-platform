package com.agentplatform.orchestrator.controller;

import com.agentplatform.common.event.MarketDataEvent;
import com.agentplatform.common.model.AnalysisResult;
import com.agentplatform.orchestrator.service.OrchestratorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/orchestrate")
public class OrchestratorController {

    private final OrchestratorService orchestratorService;

    public OrchestratorController(OrchestratorService orchestratorService) {
        this.orchestratorService = orchestratorService;
    }

    @PostMapping("/trigger")
    public Mono<ResponseEntity<List<AnalysisResult>>> trigger(@RequestBody MarketDataEvent event) {
        return orchestratorService.orchestrate(event).map(ResponseEntity::ok);
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
