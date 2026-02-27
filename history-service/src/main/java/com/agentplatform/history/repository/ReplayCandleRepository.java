package com.agentplatform.history.repository;

import com.agentplatform.history.model.ReplayCandle;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Phase-32: R2DBC repository for {@link ReplayCandle}.
 */
@Repository
public interface ReplayCandleRepository extends ReactiveCrudRepository<ReplayCandle, Long> {

    Flux<ReplayCandle> findBySymbolOrderByCandleTimeAsc(String symbol);

    Mono<Void> deleteBySymbol(String symbol);

    Mono<Long> countBySymbol(String symbol);
}
