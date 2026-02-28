package com.agentplatform.orchestrator.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Phase-43: DailyRiskGovernor — gate-0 in DecisionPipelineEngine.
 *
 * <p>Polls trade-service for intraday risk state before any other gate runs.
 * Protects against revenge trading (loss spiral) and overconfidence (profit lock).
 *
 * <h3>Kill switch rules</h3>
 * <ul>
 *   <li>dailyLoss ≥ 1.5R  → {@link GovernorDecision#HALT}</li>
 *   <li>dailyProfit ≥ 3R  → {@link GovernorDecision#HALT} (lock gains)</li>
 *   <li>consecutiveLosses ≥ 3 → {@link GovernorDecision#HALT}</li>
 *   <li>consecutiveLosses = 2 → {@link GovernorDecision#REDUCE_SIZE}</li>
 *   <li>otherwise          → {@link GovernorDecision#ALLOW}</li>
 * </ul>
 *
 * <p>On any trade-service failure → defaults to {@link GovernorDecision#ALLOW}
 * (non-critical path — governor never blocks due to unavailability).
 */
@Component
public class DailyRiskGovernor {

    private static final Logger log = LoggerFactory.getLogger(DailyRiskGovernor.class);

    private final WebClient tradeClient;

    public DailyRiskGovernor(WebClient tradeClient) {
        this.tradeClient = tradeClient;
    }

    /**
     * Evaluates daily risk state for the symbol.
     * Returns a Mono so OrchestratorService can compose it reactively.
     * On any error → ALLOW (non-fatal, never blocks pipeline).
     */
    @SuppressWarnings("unchecked")
    public Mono<GovernorDecision> evaluate(String symbol) {
        return tradeClient.get()
            .uri("/api/v1/trade/daily-risk-state?symbol={symbol}", symbol)
            .retrieve()
            .bodyToMono(Map.class)
            .map(body -> {
                boolean killSwitch       = Boolean.TRUE.equals(body.get("killSwitch"));
                int consecutiveLosses    = body.get("consecutiveLosses") instanceof Number n
                    ? n.intValue() : 0;
                String killReason        = (String) body.get("killReason");

                if (killSwitch) {
                    log.warn("[DailyRiskGovernor] HALT. symbol={} reason={}", symbol, killReason);
                    return GovernorDecision.HALT;
                }
                if (consecutiveLosses >= 2) {
                    log.info("[DailyRiskGovernor] REDUCE_SIZE. symbol={} consecutiveLosses={}", symbol, consecutiveLosses);
                    return GovernorDecision.REDUCE_SIZE;
                }
                return GovernorDecision.ALLOW;
            })
            .onErrorResume(e -> {
                log.warn("[DailyRiskGovernor] trade-service unreachable (non-critical). symbol={} → ALLOW. reason={}",
                    symbol, e.getMessage());
                return Mono.just(GovernorDecision.ALLOW);
            });
    }
}
