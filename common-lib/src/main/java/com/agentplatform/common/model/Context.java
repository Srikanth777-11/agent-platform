package com.agentplatform.common.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record Context(
    @JsonProperty("symbol") String symbol,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("marketData") Map<String, Object> marketData,
    @JsonProperty("prices") List<Double> prices,
    @JsonProperty("traceId") String traceId
) {
    public static Context of(String symbol, Instant timestamp,
                              Map<String, Object> marketData,
                              List<Double> prices, String traceId) {
        return new Context(symbol, timestamp, marketData, prices, traceId);
    }
}
