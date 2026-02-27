package com.agentplatform.analysis.agent;

import com.agentplatform.common.model.AnalysisResult;
import com.agentplatform.common.model.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * DisciplineCoach calls the Anthropic Claude API to perform
 * a higher-level sanity / discipline check on the aggregated context.
 * It validates whether the data warrants action or signals noise.
 */
@Component
public class DisciplineCoach implements AnalysisAgent {

    private static final Logger log = LoggerFactory.getLogger(DisciplineCoach.class);

    private final WebClient anthropicClient;
    private final ObjectMapper objectMapper;

    @Value("${anthropic.api-key:}")
    private String anthropicApiKey;

    @Value("${anthropic.model:claude-haiku-4-5-20251001}")
    private String model;

    public DisciplineCoach(WebClient.Builder builder, ObjectMapper objectMapper) {
        this.anthropicClient = builder
            .baseUrl("https://api.anthropic.com")
            .defaultHeader("anthropic-version", "2023-06-01")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String agentName() { return "DisciplineCoach"; }

    @Override
    public AnalysisResult analyze(Context context) {
        log.info("[DisciplineCoach] Calling Claude AI for discipline check. symbol={}", context.symbol());

        if (anthropicApiKey == null || anthropicApiKey.isBlank()) {
            log.warn("[DisciplineCoach] No Anthropic API key configured. Returning rule-based fallback.");
            return fallbackAnalysis(context);
        }

        try {
            String prompt = buildPrompt(context);
            String responseText = callClaude(prompt);
            return parseClaudeResponse(responseText, context);
        } catch (Exception e) {
            log.error("[DisciplineCoach] Claude API call failed. Falling back to rules.", e);
            return fallbackAnalysis(context);
        }
    }

    private String buildPrompt(Context context) {
        List<Double> prices = context.prices();
        double latestPrice = prices != null && !prices.isEmpty() ? prices.get(0) : 0;
        double prev5Price  = prices != null && prices.size() >= 5 ? prices.get(4) : latestPrice;
        double change5d    = latestPrice > 0 ? ((latestPrice - prev5Price) / prev5Price) * 100 : 0;

        Map<String, Object> md = context.marketData();

        return """
            You are a disciplined trading coach performing a risk discipline check on a backend analysis system.
            Do NOT provide financial advice. Instead, evaluate whether the data signals warrant action or are noise.

            Symbol: %s
            Latest Close: %.4f
            5-Day Change: %.2f%%
            Available price points: %d
            Market metadata: %s

            Respond ONLY with a JSON object in this exact format:
            {
              "assessment": "PROCEED|CAUTION|PAUSE",
              "confidence": 0.0-1.0,
              "reasoning": "brief one sentence explanation",
              "signal": "BUY|SELL|HOLD|WATCH"
            }

            Rules for assessment:
            - PROCEED: Data is consistent, signal is clear and low noise
            - CAUTION: Mixed signals or moderate uncertainty
            - PAUSE: High noise, conflicting data, or insufficient data quality
            """.formatted(
                context.symbol(),
                latestPrice,
                change5d,
                prices != null ? prices.size() : 0,
                md != null ? md.toString() : "none"
        );
    }

    private String callClaude(String prompt) throws Exception {
        Map<String, Object> requestBody = Map.of(
            "model", model,
            "max_tokens", 300,
            "messages", List.of(
                Map.of("role", "user", "content", prompt)
            )
        );

        String bodyJson = objectMapper.writeValueAsString(requestBody);

        String response = anthropicClient.post()
            .uri("/v1/messages")
            .header("x-api-key", anthropicApiKey)
            .bodyValue(bodyJson)
            .retrieve()
            .bodyToMono(String.class)
            .subscribeOn(Schedulers.boundedElastic())
            .block();

        // Extract text from response
        JsonNode root = objectMapper.readTree(response);
        return root.path("content").get(0).path("text").asText();
    }

    private AnalysisResult parseClaudeResponse(String responseText, Context context) {
        try {
            // Strip any markdown fences
            String cleaned = responseText
                .replaceAll("```json", "")
                .replaceAll("```", "")
                .trim();
            JsonNode json = objectMapper.readTree(cleaned);

            String assessment = json.path("assessment").asText("CAUTION");
            double confidence  = json.path("confidence").asDouble(0.5);
            String reasoning   = json.path("reasoning").asText("No reasoning provided");
            String signal      = json.path("signal").asText("HOLD");

            String summary = String.format(
                "DisciplineCoach [Claude AI]: Assessment=%s | %s", assessment, reasoning
            );

            return AnalysisResult.of(agentName(), summary, signal, confidence, Map.of(
                "assessment",  assessment,
                "reasoning",   reasoning,
                "aiModel",     model,
                "source",      "anthropic-claude"
            ));
        } catch (Exception e) {
            log.error("[DisciplineCoach] Failed to parse Claude response: {}", responseText, e);
            return fallbackAnalysis(context);
        }
    }

    /**
     * Rule-based fallback used when Claude API is unavailable.
     */
    private AnalysisResult fallbackAnalysis(Context context) {
        List<Double> prices = context.prices();
        if (prices == null || prices.size() < 5) {
            return AnalysisResult.of(agentName(),
                "DisciplineCoach [Fallback]: Insufficient data — PAUSE recommended",
                "HOLD", 0.2, Map.of("assessment", "PAUSE", "source", "rule-based-fallback"));
        }

        double latest = prices.get(0);
        double prev5  = prices.get(4);
        double change = (latest - prev5) / prev5;

        String assessment, signal;
        double confidence;

        if (Math.abs(change) > 0.07) {
            assessment = "CAUTION"; signal = "WATCH"; confidence = 0.55;
        } else if (Math.abs(change) < 0.01) {
            assessment = "PAUSE";   signal = "HOLD";  confidence = 0.30;
        } else {
            assessment = "PROCEED"; signal = "HOLD";  confidence = 0.60;
        }

        String summary = String.format(
            "DisciplineCoach [Fallback]: Assessment=%s | 5d change=%.2f%%", assessment, change * 100
        );

        return AnalysisResult.of(agentName(), summary, signal, confidence, Map.of(
            "assessment",  assessment,
            "5dChange",    String.format("%.2f%%", change * 100),
            "source",      "rule-based-fallback"
        ));
    }
}
