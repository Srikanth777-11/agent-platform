package com.agentplatform.trade.repository;

import com.agentplatform.trade.model.TradeSession;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface TradeSessionRepository extends ReactiveCrudRepository<TradeSession, Long> {

    Flux<TradeSession> findBySymbolOrderByEntryTimeDesc(String symbol);

    Flux<TradeSession> findByExitTimeIsNullOrderByEntryTimeDesc();

    Flux<TradeSession> findBySymbolAndExitTimeIsNull(String symbol);

    Flux<TradeSession> findByExitTimeIsNotNullOrderByExitTimeDesc();
}
