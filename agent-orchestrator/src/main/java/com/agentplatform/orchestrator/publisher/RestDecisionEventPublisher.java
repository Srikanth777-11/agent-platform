package com.agentplatform.orchestrator.publisher;

import com.agentplatform.common.decision.DecisionEventPublisher;
import com.agentplatform.common.model.FinalDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * REST-based implementation of {@link DecisionEventPublisher}.
 *
 * <p>Sends {@link FinalDecision} to notification-service via HTTP POST (fire-and-forget).
 * No reactor thread is ever blocked.
 *
 * <p>TODO: KafkaDecisionPublisher — when Kafka is introduced, create a second implementation:
 * <pre>
 * {@literal @}Component
 * {@literal @}ConditionalOnProperty(name = "events.publisher", havingValue = "kafka")
 * public class KafkaDecisionPublisher implements DecisionEventPublisher {
 *     private final KafkaTemplate<String, FinalDecision> kafkaTemplate;
 *     {@literal @}Override
 *     public void publish(FinalDecision decision) {
 *         kafkaTemplate.send("agent.decisions", decision.symbol(), decision);
 *     }
 * }
 * </pre>
 * Register it as {@literal @}Primary to replace this bean with zero orchestrator changes.
 */
@Component
public class RestDecisionEventPublisher implements DecisionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RestDecisionEventPublisher.class);

    private final WebClient notificationClient;

    public RestDecisionEventPublisher(WebClient notificationClient) {
        this.notificationClient = notificationClient;
    }

    @Override
    public void publish(FinalDecision decision) {
        notificationClient.post()
            .uri("/api/v1/notify/decision")
            .header("X-Trace-Id", decision.traceId())
            .bodyValue(decision)
            .retrieve()
            .toBodilessEntity()
            .subscribe(
                r   -> log.info("Decision event published. traceId={} signal={} status={}",
                                decision.traceId(), decision.finalSignal(), r.getStatusCode()),
                err -> log.warn("Decision event publish failed (non-critical). traceId={}",
                                decision.traceId(), err)
            );
    }
}
