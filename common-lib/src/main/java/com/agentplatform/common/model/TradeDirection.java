package com.agentplatform.common.model;

/**
 * Unambiguous trade direction for every decision — eliminates the SELL ambiguity.
 *
 * <ul>
 *   <li>LONG  — BUY signal: enter long (buy Nifty futures or CE options)</li>
 *   <li>SHORT — SELL signal: enter short (sell Nifty futures or buy PE options)</li>
 *   <li>FLAT  — HOLD/WATCH signal: no new position</li>
 * </ul>
 *
 * <p>Phase-33 addition. Stored on {@link FinalDecision} and emitted in
 * {@link com.agentplatform.history.dto.SnapshotDecisionDTO} for operator clarity.
 */
public enum TradeDirection {

    LONG,
    SHORT,
    FLAT;

    /** Derives direction from the AI/final signal string. */
    public static TradeDirection fromSignal(String signal) {
        if ("BUY".equalsIgnoreCase(signal))  return LONG;
        if ("SELL".equalsIgnoreCase(signal)) return SHORT;
        return FLAT;
    }
}
