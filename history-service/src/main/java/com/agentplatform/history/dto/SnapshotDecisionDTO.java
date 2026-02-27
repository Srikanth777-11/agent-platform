package com.agentplatform.history.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * Lightweight projection of a {@link com.agentplatform.history.model.DecisionHistory}
 * record, carrying only the fields required by the card-style UI snapshot view.
 *
 * <p>One instance per symbol — always represents the most recent decision.
 */
public record SnapshotDecisionDTO(
    @JsonProperty("symbol")         String symbol,
    @JsonProperty("finalSignal")    String finalSignal,
    @JsonProperty("confidence")     double confidence,
    @JsonProperty("marketRegime")   String marketRegime,
    @JsonProperty("divergenceFlag") Boolean divergenceFlag,
    @JsonProperty("aiReasoning")    String aiReasoning,
    @JsonProperty("savedAt")        LocalDateTime savedAt
) {}
