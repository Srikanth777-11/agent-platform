package com.agentplatform.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Output of the AI Strategist Layer — the primary intelligence recommendation
 * synthesised by {@code AIStrategistService} from all agent signals, adaptive
 * weights, and the detected market regime.
 *
 * <p>This record travels through the orchestration pipeline and is used to
 * set {@link FinalDecision#finalSignal()} and {@link FinalDecision#aiReasoning()}.
 * The consensus engine then runs independently as a safety guardrail.
 *
 * <p>Phase-23 scalping entry/exit fields — all nullable:
 * <ul>
 *   <li>{@code entryPrice}           — recommended entry price (BUY/SELL only)</li>
 *   <li>{@code targetPrice}          — take-profit target (BUY/SELL only)</li>
 *   <li>{@code stopLoss}             — hard stop price (BUY/SELL only)</li>
 *   <li>{@code estimatedHoldMinutes} — expected scalp duration in minutes (BUY/SELL only)</li>
 * </ul>
 * All four fields are null for HOLD and WATCH signals.
 */
public record AIStrategyDecision(
    @JsonProperty("finalSignal")           String  finalSignal,
    @JsonProperty("confidence")            double  confidence,
    @JsonProperty("reasoning")             String  reasoning,
    @JsonProperty("entryPrice")            Double  entryPrice,
    @JsonProperty("targetPrice")           Double  targetPrice,
    @JsonProperty("stopLoss")              Double  stopLoss,
    @JsonProperty("estimatedHoldMinutes")  Integer estimatedHoldMinutes,
    // Phase-33: explicit trade direction (LONG / SHORT / FLAT)
    @JsonProperty("tradeDirection")        String  tradeDirection
) {
    /** Backwards-compatible constructor for callers that pre-date Phase-23. */
    public AIStrategyDecision(String finalSignal, double confidence, String reasoning) {
        this(finalSignal, confidence, reasoning, null, null, null, null, null);
    }
}
