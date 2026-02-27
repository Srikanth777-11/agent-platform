package com.agentplatform.marketdata.model;

import java.time.Instant;
import java.util.List;

public record MarketDataQuote(
    String symbol,
    double latestClose,
    double open,
    double high,
    double low,
    long volume,
    List<Double> recentClosingPrices,   // last 50 closes, newest first
    Instant fetchedAt
) {}
