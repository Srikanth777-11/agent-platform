package com.agentplatform.common.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record MarketDataEvent(
    @JsonProperty("symbol") String symbol,
    @JsonProperty("triggeredAt") Instant triggeredAt,
    @JsonProperty("traceId") String traceId
) {}
