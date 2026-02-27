package com.agentplatform.analysis.controller;

import com.agentplatform.analysis.service.AgentDispatchService;
import com.agentplatform.common.model.AnalysisResult;
import com.agentplatform.common.model.Context;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/analyze")
public class AnalysisController {

    private final AgentDispatchService dispatchService;

    public AnalysisController(AgentDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @PostMapping
    public Mono<ResponseEntity<List<AnalysisResult>>> analyze(@RequestBody Context context) {
        return dispatchService.dispatchAll(context)
            .map(ResponseEntity::ok);
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
