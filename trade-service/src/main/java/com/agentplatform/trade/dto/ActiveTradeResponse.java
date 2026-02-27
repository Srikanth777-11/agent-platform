package com.agentplatform.trade.dto;

import com.agentplatform.common.model.ActiveTradeContext;
import com.agentplatform.common.model.ExitAwareness;
import com.agentplatform.common.model.RiskEnvelope;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Composite response for GET /api/v1/trades/active/{symbol}.
 * Combines entry context, exit awareness, and risk envelope.
 */
public record ActiveTradeResponse(
    @JsonProperty("trade")         ActiveTradeContext trade,
    @JsonProperty("exitAwareness") ExitAwareness exitAwareness,
    @JsonProperty("riskEnvelope")  RiskEnvelope riskEnvelope
) {}
