package com.agentplatform.orchestrator.adapter;

import com.agentplatform.common.model.AgentFeedback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.Map;

/**
 * Fetches per-agent historical feedback from the history-service.
 *
 * <p>On any error the adapter returns an empty map so that a transient
 * history-service outage never blocks the orchestration pipeline.
 * When the fallback fires, {@link com.agentplatform.common.consensus.AgentScoreCalculator}
 * will skip the feedback boost and use base adaptive weights only.
 */
@Component
public class AgentFeedbackAdapter {

    private static final Logger log = LoggerFactory.getLogger(AgentFeedbackAdapter.class);

    private final WebClient historyClient;

    public AgentFeedbackAdapter(WebClient historyClient) {
        this.historyClient = historyClient;
    }

    /**
     * @param traceId propagated to history-service via {@code X-Trace-Id} header
     * @return per-agent feedback keyed by agentName;
     *         falls back to {@link Collections#emptyMap()} on any error
     */
    public Mono<Map<String, AgentFeedback>> fetchFeedback(String traceId) {
        return historyClient.get()
            .uri("/api/v1/history/agent-feedback")
            .header("X-Trace-Id", traceId)
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<Map<String, AgentFeedback>>() {})
            .doOnNext(map -> log.debug(
                "Agent feedback fetched. agents={} traceId={}", map.size(), traceId))
            .onErrorResume(e -> {
                log.warn("Agent feedback fetch failed — skipping feedback boost. traceId={} reason={}",
                         traceId, e.getMessage());
                return Mono.just(Collections.emptyMap());
            });
    }
}
