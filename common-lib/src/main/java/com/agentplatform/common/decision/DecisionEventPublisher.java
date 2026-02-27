package com.agentplatform.common.decision;

import com.agentplatform.common.model.FinalDecision;

/**
 * Abstraction for publishing {@link FinalDecision} events after orchestration completes.
 *
 * <p>Current implementation: {@code RestDecisionEventPublisher} — sends the decision
 * to notification-service via HTTP (fire-and-forget WebClient call).
 *
 * <p>Future implementation:
 * <pre>
 * // TODO: KafkaDecisionPublisher
 * //   Implement this interface using KafkaTemplate<String, FinalDecision>.
 * //   Publish to topic "agent.decisions" keyed by symbol.
 * //   Register as @Primary @ConditionalOnProperty(name="events.publisher", havingValue="kafka")
 * //   No other code change required — the orchestrator depends only on this interface.
 * </pre>
 */
public interface DecisionEventPublisher {

    /**
     * Publish a final trading decision event.
     * Implementations MUST be non-blocking (fire-and-forget or fully reactive).
     * No {@code .block()} is permitted inside any implementation.
     *
     * @param decision the completed decision to publish
     */
    void publish(FinalDecision decision);
}
