package com.agentplatform.history.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Lightweight projection of a {@link com.agentplatform.history.model.DecisionHistory}
 * carrying only the four fields required by the AI strategy memory prompt.
 *
 * <p>Replaces the full entity on the {@code GET /recent/{symbol}} endpoint,
 * reducing payload size and decoupling the API contract from the table schema.
 */
public record RecentDecisionMemoryDTO(
    @JsonProperty("finalSignal")    String  finalSignal,
    @JsonProperty("confidenceScore") double  confidenceScore,
    @JsonProperty("divergenceFlag") Boolean divergenceFlag,
    @JsonProperty("marketRegime")   String  marketRegime
) {}
