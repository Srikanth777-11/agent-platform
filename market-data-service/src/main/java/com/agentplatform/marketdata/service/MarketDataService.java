package com.agentplatform.marketdata.service;

import com.agentplatform.marketdata.cache.CachedMarketData;
import com.agentplatform.marketdata.cache.MarketDataCache;
import com.agentplatform.marketdata.client.MarketDataWebClient;
import com.agentplatform.marketdata.model.MarketDataQuote;
import com.agentplatform.marketdata.provider.MarketDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Service layer for market data access — now backed by a regime-aware in-memory cache.
 *
 * <p><strong>Flow:</strong>
 * <ol>
 *   <li>Check {@link MarketDataCache} for a valid (non-expired) entry.</li>
 *   <li>On hit → return immediately as {@code Mono.just(cached)} (no API call).</li>
 *   <li>On miss → call Alpha Vantage via {@link MarketDataWebClient},
 *       store the result in cache, and return the fresh quote.</li>
 * </ol>
 *
 * <p>No blocking calls. The cache lookup is a synchronous ConcurrentHashMap read
 * wrapped in {@code Mono.defer} to stay lazy within the reactive chain.
 *
 * <p>Implements {@link MarketDataProvider} — swapped for {@code ReplayMarketProvider}
 * when profile {@code replay} is active.
 */
@Service
public class MarketDataService implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private final MarketDataWebClient client;
    private final MarketDataCache cache;

    public MarketDataService(MarketDataWebClient client, MarketDataCache cache) {
        this.client = client;
        this.cache  = cache;
    }

    public Mono<MarketDataQuote> getQuote(String symbol) {
        String key = symbol.toUpperCase();

        return Mono.defer(() -> {
            CachedMarketData cached = cache.get(key);

            if (cached != null) {
                log.info("CACHE_HIT symbol={} regime={} fetchedAt={}",
                         key, cached.regime(), cached.fetchedAt());
                return Mono.just(cached.data());
            }

            log.info("CACHE_MISS symbol={}", key);
            return client.fetchQuote(key)
                .doOnSuccess(quote -> cache.put(key, quote));
        });
    }
}
