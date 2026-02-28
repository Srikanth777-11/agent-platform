package com.agentplatform.orchestrator.ai;

import com.agentplatform.common.cognition.CalmMood;
import com.agentplatform.common.cognition.CalmTrajectory;
import com.agentplatform.common.cognition.DirectionalBias;
import com.agentplatform.common.cognition.ReflectionState;
import com.agentplatform.common.cognition.TradingSession;
import com.agentplatform.common.model.AIStrategyDecision;
import com.agentplatform.common.model.TradeDirection;
import com.agentplatform.common.model.AnalysisResult;
import com.agentplatform.common.model.Context;
import com.agentplatform.common.model.DecisionContext;
import com.agentplatform.common.model.MarketRegime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI Strategist Layer — the primary decision intelligence in the orchestration pipeline.
 *
 * <p>Synthesises all agent signals, adaptive weights, and the detected market regime
 * into a single {@link AIStrategyDecision} using the Anthropic Claude API.
 * The consensus engine continues to run downstream as a safety guardrail; this service
 * sets the authoritative {@code finalSignal} and {@code aiReasoning} on {@link
 * com.agentplatform.common.model.FinalDecision}.
 *
 * <p><strong>Reactive contract</strong>: this service is fully non-blocking.
 * No {@code .block()} call exists anywhere in this class. The Anthropic HTTP call
 * is composed as a {@code Mono} chain and integrated via {@code flatMap} in
 * {@link com.agentplatform.orchestrator.service.OrchestratorService}.
 *
 * <p><strong>Fallback behaviour</strong>: when the API key is absent or the Anthropic
 * call fails, the service returns a rule-based fallback — a majority-vote over the
 * existing agent signals — so that the orchestration pipeline never stalls.
 */
@Service
public class AIStrategistService {

    private static final Logger log = LoggerFactory.getLogger(AIStrategistService.class);

    private final WebClient anthropicClient;
    private final ObjectMapper objectMapper;

    @Value("${anthropic.api-key:}")
    private String anthropicApiKey;

    public AIStrategistService(WebClient.Builder builder, ObjectMapper objectMapper) {
        this.anthropicClient = builder
            .baseUrl("https://api.anthropic.com")
            .defaultHeader("anthropic-version", "2023-06-01")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
        this.objectMapper = objectMapper;
    }

    /**
     * Evaluates all available intelligence and returns the AI's strategic recommendation.
     *
     * @param context         market context (symbol, prices, market data)
     * @param results         per-agent analysis results from analysis-engine
     * @param regime          market regime classified from the current price data
     * @param adaptiveWeights history-adjusted per-agent weights from AgentScoreCalculator
     * @return {@link AIStrategyDecision} — never empty; fallback fires on any failure
     */
    public Mono<AIStrategyDecision> evaluate(Context context,
                                             List<AnalysisResult> results,
                                             MarketRegime regime,
                                             Map<String, Double> adaptiveWeights) {
        if (anthropicApiKey == null || anthropicApiKey.isBlank()) {
            log.warn("[AIStrategist] No Anthropic API key configured — returning rule-based fallback. symbol={}",
                     context.symbol());
            return Mono.just(fallback(results));
        }

        String selectedModel = ModelSelector.selectModel(regime);

        return Mono.fromCallable(() -> buildPrompt(context, results, regime, adaptiveWeights))
            .flatMap(prompt -> callAnthropicApi(prompt, selectedModel))
            .map(this::parseResponse)
            .doOnSuccess(d -> log.info("[AIStrategist] Strategy evaluated. signal={} confidence={} symbol={}",
                                       d.finalSignal(), d.confidence(), context.symbol()))
            .onErrorResume(e -> {
                log.error("[AIStrategist] API call failed — returning rule-based fallback. symbol={} reason={}",
                          context.symbol(), e.getMessage());
                return Mono.just(fallback(results));
            });
    }

    /**
     * Phase-27 Strategy Memory entry point.
     *
     * <p>Identical to {@link #evaluate(DecisionContext, Context)} but injects
     * the last N prior decisions into the prompt so the AI can detect
     * signal flip-flop and regime transitions across cycles.
     *
     * @param priorDecisions raw decision maps from {@code GET /recent/{symbol}};
     *                       empty list is safe — memory section is silently omitted
     */
    public Mono<AIStrategyDecision> evaluate(DecisionContext decisionCtx, Context marketCtx,
                                             List<Map<String, Object>> priorDecisions) {
        if (anthropicApiKey == null || anthropicApiKey.isBlank()) {
            log.warn("[AIStrategist] No Anthropic API key configured — returning rule-based fallback. symbol={}",
                     marketCtx.symbol());
            return Mono.just(fallback(decisionCtx.agentResults()));
        }

        // Phase-44: peak mode — force Haiku, short prompt, 1200ms timeout
        boolean isPeakMode = Boolean.TRUE.equals(decisionCtx.peakMode());
        String selectedModel = ModelSelector.selectModel(decisionCtx.regime(), isPeakMode);
        int maxTokens  = isPeakMode ? 150 : 300;
        long timeoutMs = isPeakMode ? 1200L : 4000L;

        if (isPeakMode) {
            log.info("[AIStrategist] PEAK MODE active. model={} maxTokens={} timeoutMs={} symbol={}",
                selectedModel, maxTokens, timeoutMs, marketCtx.symbol());
        }

        return Mono.fromCallable(() -> isPeakMode
                ? buildPeakModePrompt(marketCtx, decisionCtx)
                : buildPrompt(marketCtx, decisionCtx.agentResults(),
                              decisionCtx.regime(), decisionCtx.adaptiveWeights(),
                              decisionCtx, priorDecisions))
            .flatMap(prompt -> callAnthropicApi(prompt, selectedModel, maxTokens, timeoutMs))
            .map(this::parseResponse)
            .doOnSuccess(d -> log.info("[AIStrategist] Strategy evaluated (with memory). signal={} confidence={} symbol={} memorySize={} peakMode={}",
                                       d.finalSignal(), d.confidence(), marketCtx.symbol(),
                                       priorDecisions != null ? priorDecisions.size() : 0, isPeakMode))
            .onErrorResume(e -> {
                log.error("[AIStrategist] API call failed — returning rule-based fallback. symbol={} reason={}",
                          marketCtx.symbol(), e.getMessage());
                return Mono.just(fallback(decisionCtx.agentResults()));
            });
    }

    /**
     * Omega-aware entry point — primary path from Phase-18 onward.
     *
     * <p>Accepts the omega-enriched {@link DecisionContext} (stabilityPressure,
     * calmTrajectory, divergenceTrajectory populated) and routes through
     * {@link #buildPrompt} with trajectory bias injection.
     * VOLATILE regime suppresses trajectory influence per Calm Omega rules.
     *
     * @param decisionCtx omega-enriched context (Phase-18 fields populated)
     * @param marketCtx   market data context (needed for price details in prompt)
     */
    public Mono<AIStrategyDecision> evaluate(DecisionContext decisionCtx, Context marketCtx) {
        if (anthropicApiKey == null || anthropicApiKey.isBlank()) {
            log.warn("[AIStrategist] No Anthropic API key configured — returning rule-based fallback. symbol={}",
                     marketCtx.symbol());
            return Mono.just(fallback(decisionCtx.agentResults()));
        }

        String selectedModel = ModelSelector.selectModel(decisionCtx.regime());

        return Mono.fromCallable(() -> buildPrompt(marketCtx, decisionCtx.agentResults(),
                                                   decisionCtx.regime(), decisionCtx.adaptiveWeights(),
                                                   decisionCtx))
            .flatMap(prompt -> callAnthropicApi(prompt, selectedModel))
            .map(this::parseResponse)
            .doOnSuccess(d -> log.info("[AIStrategist] Omega strategy evaluated. signal={} confidence={} symbol={} calmTrajectory={} reflectionState={} calmMood={}",
                                       d.finalSignal(), d.confidence(), marketCtx.symbol(),
                                       decisionCtx.calmTrajectory(), decisionCtx.reflectionState(),
                                       decisionCtx.calmMood()))
            .onErrorResume(e -> {
                log.error("[AIStrategist] API call failed — returning rule-based fallback. symbol={} reason={}",
                          marketCtx.symbol(), e.getMessage());
                return Mono.just(fallback(decisionCtx.agentResults()));
            });
    }

    // ── prompt construction ───────────────────────────────────────────────────

    private String buildPrompt(Context context, List<AnalysisResult> results,
                                MarketRegime regime, Map<String, Double> adaptiveWeights) {
        return buildPrompt(context, results, regime, adaptiveWeights, null, null);
    }

    /**
     * Phase-18 Omega-aware prompt builder.
     *
     * <p>When {@code omegaCtx} is non-null and regime is NOT VOLATILE, injects a
     * Calm Omega trajectory section capped at 25% reasoning influence.
     * VOLATILE regime suppresses trajectory entirely — raw agent signals dominate.
     */
    private String buildPrompt(Context context, List<AnalysisResult> results,
                                MarketRegime regime, Map<String, Double> adaptiveWeights,
                                DecisionContext omegaCtx) {
        return buildPrompt(context, results, regime, adaptiveWeights, omegaCtx, null);
    }

    private String buildPrompt(Context context, List<AnalysisResult> results,
                                MarketRegime regime, Map<String, Double> adaptiveWeights,
                                DecisionContext omegaCtx, List<Map<String, Object>> priorDecisions) {
        List<Double> prices = context.prices();
        double latestClose = (prices != null && !prices.isEmpty()) ? prices.get(0) : 0.0;

        StringBuilder agentSummary = new StringBuilder();
        for (AnalysisResult r : results) {
            double weight = adaptiveWeights.getOrDefault(r.agentName(), 1.0);
            agentSummary.append(String.format(
                "  - %-20s signal=%-5s  confidence=%.2f  adaptiveWeight=%.2f%n",
                r.agentName(), r.signal(), r.confidenceScore(), weight));
        }

        String reasoningInstruction = (regime == MarketRegime.VOLATILE)
            ? "Respond with ONLY one short sentence explaining reasoning."
            : "Provide a concise strategic rationale.";

        // Calm Omega trajectory bias — suppressed for VOLATILE regime
        String omegaSection = "";
        if (regime != MarketRegime.VOLATILE
                && omegaCtx != null
                && omegaCtx.calmTrajectory() != null) {
            String biasRule = switch (omegaCtx.calmTrajectory()) {
                case DESTABILIZING -> "Soften any BUY signal toward HOLD bias. Do not initiate aggressive entries.";
                case STABILIZING   -> "Cautious early BUY is permitted if agent signals support it.";
                case NEUTRAL       -> "No trajectory bias. Let agent signals dominate.";
            };
            omegaSection = String.format("""

            Calm Omega Intelligence (apply at most 25%% weight in reasoning):
              stabilityPressure    : %.2f
              calmTrajectory       : %s
              divergenceTrajectory : %s
              Trajectory rule      : %s
              Constraint           : Never flip a signal aggressively based on trajectory alone.
            """,
                omegaCtx.stabilityPressure() != null ? omegaCtx.stabilityPressure() : 0.5,
                omegaCtx.calmTrajectory(),
                omegaCtx.divergenceTrajectory() != null ? omegaCtx.divergenceTrajectory() : CalmTrajectory.NEUTRAL,
                biasRule);
        }

        // Architect Reflection bias — suppressed for VOLATILE regime
        String reflectionSection = "";
        if (regime != MarketRegime.VOLATILE
                && omegaCtx != null
                && omegaCtx.reflectionState() != null) {
            String reflectionRule = switch (omegaCtx.reflectionState()) {
                case ALIGNED   -> "Normal calm reasoning applies. No additional bias.";
                case DRIFTING  -> "Soften confidence language. Early HOLD bias is permitted.";
                case UNSTABLE  -> "Suppress aggressive BUY/SELL flips. Prefer HOLD to stabilize.";
            };
            reflectionSection = String.format("""

            Architect Reflection (apply at most 20%% weight in reasoning):
              reflectionState : %s
              Reflection rule : %s
              Constraint      : Do not override agent signal consensus based on reflection alone.
            """,
                omegaCtx.reflectionState(),
                reflectionRule);
        }

        // CalmMood operator tone — suppressed for VOLATILE regime
        String moodSection = "";
        if (regime != MarketRegime.VOLATILE
                && omegaCtx != null
                && omegaCtx.calmMood() != null) {
            String moodInstruction = switch (omegaCtx.calmMood()) {
                case CALM      -> "Operator mood: CALM. Confidence language may be assertive.";
                case BALANCED  -> "Operator mood: BALANCED. Maintain measured, neutral tone.";
                case PRESSURED -> "Operator mood: PRESSURED. Use restrained language. Avoid amplifying uncertainty.";
            };
            moodSection = "\nCalm Operator Mood: " + omegaCtx.calmMood()
                + " — " + moodInstruction + "\n";
        }

        // Phase-22 Trading Session — informational only. Gate enforcement is handled by
        // Phase-35 SessionGate and Phase-36 EligibilityGuard in OrchestratorService.
        boolean activeScalpingWindow = omegaCtx != null
            && omegaCtx.tradingSession() != null
            && omegaCtx.tradingSession().isActiveScalpingWindow();

        String sessionSection = "";
        if (omegaCtx != null && omegaCtx.tradingSession() != null) {
            sessionSection = switch (omegaCtx.tradingSession()) {
                case OPENING_BURST        -> "\nTrading Session: OPENING BURST — Active scalping window. BUY and SELL signals are both valid when directional bias supports them.\n";
                case POWER_HOUR           -> "\nTrading Session: POWER HOUR — Active scalping window. BUY and SELL signals are both valid when directional bias supports them.\n";
                case MIDDAY_CONSOLIDATION -> "\nTrading Session: MIDDAY CONSOLIDATION — Lower momentum period. Prefer WATCH unless agent signals are strongly aligned.\n";
                case OFF_HOURS            -> "\nTrading Session: OFF HOURS — Outside active market hours. Prefer WATCH or HOLD.\n";
            };
        }

        // Phase-33/37: Directional Bias section — from TrendAgent 5-vote score.
        // Phase-37: SELL in BEARISH markets is equally valid as BUY in BULLISH markets.
        String biasSection = "";
        if (omegaCtx != null && omegaCtx.directionalBias() != null) {
            DirectionalBias bias = omegaCtx.directionalBias();
            String biasRule = switch (bias) {
                case STRONG_BULLISH -> "Signal BUY. The market is strongly bullish. SELL is incorrect in this direction.";
                case BULLISH        -> "Signal BUY when agent signals support it. The market is trending up. SELL is incorrect in this direction.";
                case NEUTRAL        -> "Prefer WATCH (choppy market — avoid new entry). BUY or SELL only with strong agent consensus.";
                case BEARISH        -> "Signal SELL when agent signals support it. The market is trending down. SELL = ENTER SHORT (buy PE options). BUY is incorrect in this direction.";
                case STRONG_BEARISH -> "Signal SELL. The market is strongly bearish. SELL = ENTER SHORT (sell Nifty futures or buy PE options). BUY is incorrect in this direction.";
            };
            biasSection = String.format("""

            Directional Bias (TrendAgent 5-factor vote):
              Bias   : %s
              Rule   : %s
              Important: BUY (ENTER LONG) and SELL (ENTER SHORT) are equally valid signals.
                         Use SELL in BEARISH/STRONG_BEARISH markets just as you would use BUY in BULLISH markets.
                         NEUTRAL bias → prefer WATCH to avoid choppy-market losses.
            """, bias.name(), biasRule);
        }

        String memorySection = buildMemorySection(priorDecisions);

        return """
            You are a quantitative trading strategist synthesising multiple agent signals \
            into a single final recommendation.

            Symbol:        %s
            Market Regime: %s
            Latest Close:  %.4f

            Agent Signals (with history-adjusted adaptive weights):
            %s%s%s%s%s%s%s
            Based on the market regime, the weighted agent signals, and directional bias, \
            determine the correct trade direction and provide your recommendation.

            %s

            Trade Direction Clarification:
              BUY  = ENTER LONG  (buy Nifty futures or CE options)   — use when bias is BULLISH or STRONG_BULLISH
              SELL = ENTER SHORT (sell Nifty futures or buy PE options) — use when bias is BEARISH or STRONG_BEARISH
              HOLD/WATCH = no new position — use when bias is NEUTRAL or conditions are unclear
              Both BUY and SELL are valid signals. Match your signal to the directional bias.

            Respond ONLY with a JSON object in this exact format:
            {
              "finalSignal": "BUY|SELL|HOLD|WATCH",
              "confidence": 0.0-1.0,
              "reasoning": "your rationale here",
              "tradeDirection": "LONG|SHORT|FLAT"%s
            }
            """.formatted(context.symbol(), regime.name(), latestClose, agentSummary,
                          omegaSection, reflectionSection, moodSection, sessionSection,
                          biasSection, memorySection,
                          reasoningInstruction,
                          activeScalpingWindow
                              ? ",\n              \"entryPrice\": <recommended entry price for BUY/SELL, null for HOLD/WATCH>,\n              \"targetPrice\": <take-profit price — typically 0.3–0.5% move, null for HOLD/WATCH>,\n              \"stopLoss\": <hard stop price — typically 0.15–0.25% against entry, null for HOLD/WATCH>,\n              \"estimatedHoldMinutes\": <expected scalp duration 1–15 min, null for HOLD/WATCH>"
                              : "");
    }

    /**
     * Phase-44: Compact prompt for peak-mode fast path.
     * Skips omega/reflection/mood/memory sections. Max tokens: 150.
     * Active scalping window — always requests entry/exit fields.
     */
    private String buildPeakModePrompt(Context context, DecisionContext ctx) {
        List<Double> prices = context.prices();
        double latestClose = (prices != null && !prices.isEmpty()) ? prices.get(0) : 0.0;

        StringBuilder agentSummary = new StringBuilder();
        for (AnalysisResult r : ctx.agentResults()) {
            double weight = ctx.adaptiveWeights().getOrDefault(r.agentName(), 1.0);
            agentSummary.append(String.format("  %-20s signal=%-5s conf=%.2f wt=%.2f%n",
                r.agentName(), r.signal(), r.confidenceScore(), weight));
        }

        String biasRule = "";
        if (ctx.directionalBias() != null) {
            biasRule = switch (ctx.directionalBias()) {
                case STRONG_BULLISH -> "STRONG BULLISH — signal BUY only";
                case BULLISH        -> "BULLISH — BUY if agents support";
                case NEUTRAL        -> "NEUTRAL — prefer WATCH";
                case BEARISH        -> "BEARISH — SELL if agents support";
                case STRONG_BEARISH -> "STRONG BEARISH — signal SELL only";
            };
        }

        String sessionName = ctx.tradingSession() != null ? ctx.tradingSession().name() : "OPENING";

        return """
            PEAK-MODE SCALP [%s | VOLATILE] Symbol=%s Close=%.4f
            Agents:
            %s
            Bias: %s
            BUY=LONG(CE/futures) SELL=SHORT(PE/futures) WATCH=no entry
            Reply ONLY with JSON:
            {"finalSignal":"BUY|SELL|WATCH","confidence":0.0-1.0,"reasoning":"<1 sentence>","tradeDirection":"LONG|SHORT|FLAT","entryPrice":<price or null>,"targetPrice":<price or null>,"stopLoss":<price or null>,"estimatedHoldMinutes":<1-15 or null>}
            """.formatted(sessionName, context.symbol(), latestClose, agentSummary, biasRule);
    }

    private String buildMemorySection(List<Map<String, Object>> priorDecisions) {
        if (priorDecisions == null || priorDecisions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
            "\nStrategy Memory (last " + priorDecisions.size() + " decisions, most recent first — avoid flip-flop, do not blindly continue prior trend):\n");
        for (Map<String, Object> d : priorDecisions) {
            sb.append(String.format("  - signal=%-5s  confidence=%.2f  regime=%-12s  diverged=%s%n",
                d.getOrDefault("finalSignal", "?"),
                toDouble(d.getOrDefault("confidenceScore", 0.5)),
                d.getOrDefault("marketRegime", "?"),
                d.getOrDefault("divergenceFlag", "?")));
        }
        return sb.toString();
    }

    private double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(val)); } catch (Exception e) { return 0.5; }
    }

    // ── Anthropic API call (fully reactive — no .block()) ────────────────────

    /** Backward-compat overload — standard 300 tokens, 4000ms timeout. */
    private Mono<String> callAnthropicApi(String prompt, String modelName) {
        return callAnthropicApi(prompt, modelName, 300, 4000L);
    }

    /**
     * Phase-44: Parameterised Anthropic call with variable token budget and timeout.
     * Peak mode uses maxTokens=150, timeoutMs=1200 → on timeout falls back to consensus.
     */
    private Mono<String> callAnthropicApi(String prompt, String modelName, int maxTokens, long timeoutMs) {
        Map<String, Object> requestBody = Map.of(
            "model", modelName,
            "max_tokens", maxTokens,
            "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        return Mono.fromCallable(() -> objectMapper.writeValueAsString(requestBody))
            .flatMap(bodyJson ->
                anthropicClient.post()
                    .uri("/v1/messages")
                    .header("x-api-key", anthropicApiKey)
                    .bodyValue(bodyJson)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(timeoutMs))
            )
            .map(response -> {
                try {
                    JsonNode root = objectMapper.readTree(response);
                    return root.path("content").get(0).path("text").asText();
                } catch (Exception e) {
                    throw new RuntimeException("Failed to extract text from Anthropic response", e);
                }
            });
    }

    // ── response parsing ──────────────────────────────────────────────────────

    private AIStrategyDecision parseResponse(String responseText) {
        try {
            String cleaned = responseText
                .replaceAll("```json", "")
                .replaceAll("```", "")
                .trim();
            JsonNode json = objectMapper.readTree(cleaned);

            String  signal    = json.path("finalSignal").asText("HOLD");
            double  confidence = json.path("confidence").asDouble(0.5);
            String  reasoning = json.path("reasoning").asText("No reasoning provided");

            // Phase-23 entry/exit fields — null-safe; absent for HOLD/WATCH
            Double  entryPrice  = json.hasNonNull("entryPrice")  ? json.path("entryPrice").asDouble()  : null;
            Double  targetPrice = json.hasNonNull("targetPrice") ? json.path("targetPrice").asDouble() : null;
            Double  stopLoss    = json.hasNonNull("stopLoss")    ? json.path("stopLoss").asDouble()    : null;
            Integer holdMin     = json.hasNonNull("estimatedHoldMinutes")
                                  ? json.path("estimatedHoldMinutes").asInt() : null;

            // Phase-33: trade direction — fallback to signal-derived if absent
            String tradeDirection = json.hasNonNull("tradeDirection")
                ? json.path("tradeDirection").asText()
                : TradeDirection.fromSignal(signal).name();

            return new AIStrategyDecision(signal, confidence, reasoning,
                                          entryPrice, targetPrice, stopLoss, holdMin, tradeDirection);
        } catch (Exception e) {
            log.error("[AIStrategist] Failed to parse response: {}", responseText, e);
            return new AIStrategyDecision("HOLD", 0.5, "Parse error — defaulting to HOLD");
        }
    }

    // ── fallback ──────────────────────────────────────────────────────────────

    /**
     * Rule-based fallback: majority vote over existing agent signals with average confidence.
     * Used when the Anthropic API key is absent or any error occurs.
     */
    private AIStrategyDecision fallback(List<AnalysisResult> results) {
        if (results == null || results.isEmpty()) {
            return new AIStrategyDecision("HOLD", 0.5, "Fallback: no agent results available");
        }

        Map<String, Long> votes = results.stream()
            .collect(Collectors.groupingBy(AnalysisResult::signal, Collectors.counting()));

        String topSignal = votes.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("HOLD");

        double avgConfidence = results.stream()
            .mapToDouble(AnalysisResult::confidenceScore)
            .average()
            .orElse(0.5);

        // Phase-23: synthetic entry/exit for BUY/SELL fallback
        // (latestClose not available in fallback context — use null; orchestrator will skip)
        // Phase-33: derive tradeDirection from fallback signal
        String tradeDirection = TradeDirection.fromSignal(topSignal).name();
        return new AIStrategyDecision(topSignal, avgConfidence,
            "Fallback: majority vote from agent signals — API unavailable",
            null, null, null, null, tradeDirection);
    }
}
