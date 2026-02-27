package com.agentplatform.marketdata.provider;

import com.agentplatform.marketdata.model.MarketDataQuote;
import reactor.core.publisher.Mono;

/**
 * Strategy interface — live (Alpha Vantage) or replay (history-service) implementations.
 */
public interface MarketDataProvider {
    Mono<MarketDataQuote> getQuote(String symbol);
}
