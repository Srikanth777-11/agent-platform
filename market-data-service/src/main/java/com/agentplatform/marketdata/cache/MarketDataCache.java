package com.agentplatform.marketdata.cache;

import com.agentplatform.common.classifier.MarketRegimeClassifier;
import com.agentplatform.common.model.Context;
import com.agentplatform.common.model.MarketRegime;
import com.agentplatform.marketdata.model.MarketDataQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reactive in-memory cache for {@link MarketDataQuote} entries — one per symbol.
 *
 * <p><strong>Fetch Once → Serve Many:</strong> avoids redundant Alpha Vantage API calls
 * by storing the latest quote alongside the detected {@link MarketRegime} at fetch time.
 * TTL is regime-aware: volatile markets expire fast (2 min), calm markets linger (10 min).
 *
 * <p>Thread-safe via {@link ConcurrentHashMap}. No blocking calls — all methods are
 * pure synchronous lookups suitable for composition inside reactive {@code Mono} chains.
 */
@Component
public class MarketDataCache {

    private static final Logger log = LoggerFactory.getLogger(MarketDataCache.class);

    private static final Map<MarketRegime, Duration> TTL_BY_REGIME = Map.of(
        MarketRegime.VOLATILE, Duration.ofMinutes(2),
        MarketRegime.TRENDING, Duration.ofMinutes(5),
        MarketRegime.RANGING,  Duration.ofMinutes(7),
        MarketRegime.CALM,     Duration.ofMinutes(10),
        MarketRegime.UNKNOWN,  Duration.ofMinutes(5)
    );

    private final ConcurrentHashMap<String, CachedMarketData> store = new ConcurrentHashMap<>();

    /**
     * Returns the cached entry for the given symbol, or {@code null} if absent or expired.
     *
     * <p>When an expired entry is found it is evicted immediately and {@code null} is returned
     * so the caller treats it as a cache miss.
     */
    public CachedMarketData get(String symbol) {
        CachedMarketData entry = store.get(symbol);
        if (entry == null) {
            return null;
        }
        if (isExpired(entry)) {
            store.remove(symbol);
            return null;
        }
        return entry;
    }

    /**
     * Stores a fresh quote in the cache, classifying its market regime to determine
     * future TTL.
     */
    public void put(String symbol, MarketDataQuote quote) {
        MarketRegime regime = classifyFromQuote(quote);
        CachedMarketData entry = new CachedMarketData(quote, Instant.now(), regime);
        store.put(symbol, entry);
        log.info("CACHE_REFRESH symbol={} regime={} ttlSeconds={}",
                 symbol, regime, TTL_BY_REGIME.getOrDefault(regime, Duration.ofMinutes(5)).toSeconds());
    }

    /**
     * Checks whether a cache entry has exceeded its regime-based TTL.
     */
    public boolean isExpired(CachedMarketData entry) {
        Duration ttl = TTL_BY_REGIME.getOrDefault(entry.regime(), Duration.ofMinutes(5));
        return Instant.now().isAfter(entry.fetchedAt().plus(ttl));
    }

    // ── regime classification from quote prices ─────────────────────────────

    /**
     * Builds a minimal {@link Context} from the quote so that the existing
     * {@link MarketRegimeClassifier} can be reused without duplication.
     */
    private MarketRegime classifyFromQuote(MarketDataQuote quote) {
        Map<String, Object> marketData = Map.of("latestClose", quote.latestClose());
        Context ctx = Context.of(
            quote.symbol(),
            quote.fetchedAt(),
            marketData,
            quote.recentClosingPrices(),
            "cache-classify"
        );
        return MarketRegimeClassifier.classify(ctx);
    }
}
