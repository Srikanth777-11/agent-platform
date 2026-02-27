package com.agentplatform.history.repository;

import com.agentplatform.history.model.TradeSession;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface TradeSessionRepository extends ReactiveCrudRepository<TradeSession, Long> {

    Flux<TradeSession> findBySymbolOrderByEntryTimeDesc(String symbol);
}
