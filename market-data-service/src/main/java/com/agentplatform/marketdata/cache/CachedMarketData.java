package com.agentplatform.marketdata.cache;

import com.agentplatform.common.model.MarketRegime;
import com.agentplatform.marketdata.model.MarketDataQuote;

import java.time.Instant;

/**
 * Immutable cache entry wrapping a {@link MarketDataQuote} with its fetch timestamp
 * and the detected {@link MarketRegime} at fetch time.
 *
 * <p>The regime drives TTL expiration via {@link MarketDataCache#isExpired(CachedMarketData)}.
 */
public record CachedMarketData(
    MarketDataQuote data,
    Instant fetchedAt,
    MarketRegime regime
) {}
