package com.agentplatform.analysis.controller;

import com.agentplatform.analysis.service.AgentDispatchService;
import com.agentplatform.common.model.AnalysisResult;
import com.agentplatform.common.model.Context;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestHeader;
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
    public Mono<ResponseEntity<List<AnalysisResult>>> analyze(
            @RequestBody Context context,
            @RequestHeader(value = "X-Replay-Mode", defaultValue = "false") String replayModeHeader) {
        boolean replayMode = "true".equalsIgnoreCase(replayModeHeader);
        return dispatchService.dispatchAll(context, replayMode)
            .map(ResponseEntity::ok);
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
