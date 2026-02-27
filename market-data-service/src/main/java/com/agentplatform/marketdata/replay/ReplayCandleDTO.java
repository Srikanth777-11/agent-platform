package com.agentplatform.marketdata.replay;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * Phase-32: Local OHLCV candle DTO for market-data-service.
 * Mirrors history-service's ReplayCandleDTO — identical field layout,
 * serialised over HTTP so no compile-time coupling needed.
 */
public record ReplayCandleDTO(
    @JsonProperty("symbol")     String        symbol,
    @JsonProperty("candleTime") LocalDateTime candleTime,
    @JsonProperty("open")       double        open,
    @JsonProperty("high")       double        high,
    @JsonProperty("low")        double        low,
    @JsonProperty("close")      double        close,
    @JsonProperty("volume")     long          volume
) {}
