package com.agentplatform.analysis.service;

import com.agentplatform.analysis.agent.AnalysisAgent;
import com.agentplatform.common.model.AnalysisResult;
import com.agentplatform.common.model.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

@Service
public class AgentDispatchService {

    private static final Logger log = LoggerFactory.getLogger(AgentDispatchService.class);
    private final List<AnalysisAgent> agents;

    public AgentDispatchService(List<AnalysisAgent> agents) {
        this.agents = agents;
    }

    public Mono<List<AnalysisResult>> dispatchAll(Context context) {
        log.info("Dispatching {} agents in parallel for symbol={}", agents.size(), context.symbol());
        return Flux.fromIterable(agents)
            .flatMap(agent -> Mono.fromCallable(() -> agent.analyze(context))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(result -> log.info("Agent={} complete. signal={} confidence={}",
                    agent.agentName(), result.signal(), result.confidenceScore()))
                .onErrorResume(e -> {
                    log.error("Agent={} failed for symbol={}", agent.agentName(), context.symbol(), e);
                    return Mono.just(AnalysisResult.of(agent.agentName(),
                        "Agent failed: " + e.getMessage(), "HOLD", 0.0,
                        Map.of("error", e.getMessage())));
                }))
            .collectList();
    }
}
