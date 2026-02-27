package com.agentplatform.history.dto;

import java.time.LocalDateTime;

/**
 * Phase-32: Lightweight OHLCV transfer object for replay candle ingestion and retrieval.
 * Shared between history-service (storage) and market-data-service (download + replay).
 */
public record ReplayCandleDTO(
    String        symbol,
    LocalDateTime candleTime,
    double        open,
    double        high,
    double        low,
    double        close,
    long          volume
) {}
