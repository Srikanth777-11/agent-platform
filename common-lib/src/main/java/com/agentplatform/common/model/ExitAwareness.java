package com.agentplatform.common.model;

import com.agentplatform.common.posture.TradePosture;
import com.agentplatform.common.structure.StructureSignal;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Cognitive exit-condition awareness derived from recent projections.
 * Operator intelligence only — no automation linkage.
 */
public record ExitAwareness(
    @JsonProperty("momentumShift")     boolean         momentumShift,
    @JsonProperty("confidenceDrift")   double          confidenceDrift,
    @JsonProperty("divergenceGrowing") boolean         divergenceGrowing,
    @JsonProperty("durationSignal")    String          durationSignal,
    @JsonProperty("structureSignal")   StructureSignal structureSignal,
    @JsonProperty("tradePosture")      TradePosture    tradePosture
) {}
