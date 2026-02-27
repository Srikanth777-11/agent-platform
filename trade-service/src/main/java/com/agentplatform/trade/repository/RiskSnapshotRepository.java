package com.agentplatform.trade.repository;

import com.agentplatform.trade.model.RiskSnapshot;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface RiskSnapshotRepository extends ReactiveCrudRepository<RiskSnapshot, Long> {

    Mono<RiskSnapshot> findFirstBySymbolOrderByComputedAtDesc(String symbol);

    Flux<RiskSnapshot> findBySymbolOrderByComputedAtDesc(String symbol);
}
