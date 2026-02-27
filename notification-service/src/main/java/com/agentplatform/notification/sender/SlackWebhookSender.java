package com.agentplatform.notification.sender;

import com.agentplatform.common.model.AnalysisResult;
import com.agentplatform.common.model.FinalDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Component
public class SlackWebhookSender {

    private static final Logger log = LoggerFactory.getLogger(SlackWebhookSender.class);

    private final WebClient webClient;

    @Value("${notification.slack.webhook-url:}")
    private String slackWebhookUrl;

    @Value("${notification.slack.enabled:false}")
    private boolean slackEnabled;

    public SlackWebhookSender(WebClient.Builder builder) {
        this.webClient = builder.build();
    }

    public void send(String traceId, String symbol, List<AnalysisResult> results) {
        if (!slackEnabled || slackWebhookUrl.isBlank()) {
            log.info("Slack disabled or no webhook URL configured. Logging results instead.");
            logResults(traceId, symbol, results);
            return;
        }

        String message = buildSlackMessage(symbol, results, traceId);

        webClient.post()
            .uri(slackWebhookUrl)
            .bodyValue(Map.of("text", message))
            .retrieve()
            .toBodilessEntity()
            .subscribe(
                r  -> log.info("Slack notification sent. traceId={} status={}", traceId, r.getStatusCode()),
                e  -> log.error("Slack notification failed. traceId={}", traceId, e)
            );
    }

    private String buildSlackMessage(String symbol, List<AnalysisResult> results, String traceId) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("*📊 Agent Analysis: %s* | `traceId: %s`%n", symbol, traceId));
        sb.append("---\n");

        for (AnalysisResult r : results) {
            String emoji = signalEmoji(r.signal());
            sb.append(String.format("%s *%s* → `%s` (conf: %.0f%%)\n",
                emoji, r.agentName(), r.signal(), r.confidenceScore() * 100));
            sb.append(String.format("   _%s_%n", r.summary()));
        }

        // Consensus signal
        String consensus = computeConsensus(results);
        sb.append(String.format("%n*🧠 Consensus Signal: `%s`*", consensus));
        return sb.toString();
    }

    private String computeConsensus(List<AnalysisResult> results) {
        long buyCount  = results.stream().filter(r -> "BUY".equals(r.signal())).count();
        long sellCount = results.stream().filter(r -> "SELL".equals(r.signal())).count();
        long holdCount = results.stream().filter(r -> "HOLD".equals(r.signal())).count();

        if (buyCount > sellCount && buyCount > holdCount) return "BUY";
        if (sellCount > buyCount && sellCount > holdCount) return "SELL";
        return "HOLD";
    }

    private String signalEmoji(String signal) {
        return switch (signal) {
            case "BUY"   -> "🟢";
            case "SELL"  -> "🔴";
            case "WATCH" -> "🟡";
            default      -> "⚪";
        };
    }

    public void sendDecision(FinalDecision decision) {
        if (!slackEnabled || slackWebhookUrl.isBlank()) {
            log.info("Slack disabled. Logging FinalDecision. traceId={} symbol={} signal={} confidence={}",
                     decision.traceId(), decision.symbol(),
                     decision.finalSignal(), decision.confidenceScore());
            return;
        }

        String message = buildDecisionMessage(decision);

        webClient.post()
            .uri(slackWebhookUrl)
            .bodyValue(Map.of("text", message))
            .retrieve()
            .toBodilessEntity()
            .subscribe(
                r   -> log.info("Slack decision notification sent. traceId={} status={}",
                                decision.traceId(), r.getStatusCode()),
                err -> log.error("Slack decision notification failed. traceId={}", decision.traceId(), err)
            );
    }

    private String buildDecisionMessage(FinalDecision decision) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("*📊 Final Decision: %s* | `traceId: %s`%n",
                                decision.symbol(), decision.traceId()));
        sb.append(String.format("*%s Final Signal: `%s`* | Confidence: %.0f%%%n",
                                signalEmoji(decision.finalSignal()),
                                decision.finalSignal(),
                                decision.confidenceScore() * 100));
        sb.append("---\n");

        for (AnalysisResult r : decision.agents()) {
            String emoji = signalEmoji(r.signal());
            sb.append(String.format("%s *%s* → `%s` (conf: %.0f%%)\n",
                emoji, r.agentName(), r.signal(), r.confidenceScore() * 100));
            sb.append(String.format("   _%s_%n", r.summary()));
        }
        return sb.toString();
    }

    private void logResults(String traceId, String symbol, List<AnalysisResult> results) {
        log.info("=== Analysis Results: symbol={} traceId={} ===", symbol, traceId);
        results.forEach(r -> log.info("  [{}] signal={} confidence={:.2f} | {}",
            r.agentName(), r.signal(), r.confidenceScore(), r.summary()));
    }
}
