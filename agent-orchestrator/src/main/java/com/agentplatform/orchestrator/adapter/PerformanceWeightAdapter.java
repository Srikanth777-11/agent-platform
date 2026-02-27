package com.agentplatform.orchestrator.adapter;

import com.agentplatform.common.model.AgentPerformanceModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Fetches per-agent historical performance from the history-service and
 * maps the response into {@code Map<agentName, AgentPerformanceModel>}.
 *
 * <p>All errors are swallowed with an empty-map fallback so that a transient
 * history-service outage never blocks the main orchestration pipeline.
 * When the fallback fires, {@link com.agentplatform.common.consensus.AgentScoreCalculator}
 * will assign every agent the default weight of {@code 1.0}.
 */
@Component
public class PerformanceWeightAdapter {

    private static final Logger log = LoggerFactory.getLogger(PerformanceWeightAdapter.class);

    private final WebClient historyClient;

    public PerformanceWeightAdapter(WebClient historyClient) {
        this.historyClient = historyClient;
    }

    /**
     * @param traceId propagated to history-service via {@code X-Trace-Id} header
     * @return per-agent performance keyed by agentName;
     *         falls back to {@link Map#of()} on any error
     */
    public Mono<Map<String, AgentPerformanceModel>> fetchPerformanceWeights(String traceId) {
        return historyClient.get()
            .uri("/api/v1/history/agent-performance")
            .header("X-Trace-Id", traceId)
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<Map<String, AgentPerformanceModel>>() {})
            .doOnNext(map -> log.debug(
                "Agent performance fetched. agents={} traceId={}", map.size(), traceId))
            .onErrorResume(e -> {
                log.warn("Agent performance fetch failed — using fallback weights. traceId={} reason={}",
                         traceId, e.getMessage());
                return Mono.just(Map.of());
            });
    }
}
