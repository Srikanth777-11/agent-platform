package com.agentplatform.notification.controller;

import com.agentplatform.common.model.AnalysisResult;
import com.agentplatform.common.model.FinalDecision;
import com.agentplatform.notification.sender.SlackWebhookSender;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notify")
public class NotificationController {

    private final SlackWebhookSender slackSender;

    public NotificationController(SlackWebhookSender slackSender) {
        this.slackSender = slackSender;
    }

    @PostMapping
    @SuppressWarnings("unchecked")
    public ResponseEntity<Void> notify(@RequestBody Map<String, Object> payload) {
        String traceId = (String) payload.getOrDefault("traceId", "unknown");
        String symbol  = (String) payload.getOrDefault("symbol", "UNKNOWN");
        List<AnalysisResult> results = (List<AnalysisResult>) payload.getOrDefault("results", List.of());

        slackSender.send(traceId, symbol, results);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/decision")
    public ResponseEntity<Void> notifyDecision(@RequestBody FinalDecision decision) {
        slackSender.sendDecision(decision);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
