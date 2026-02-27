package com.agentplatform.trade.repository;

import com.agentplatform.trade.model.PortfolioSummary;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface PortfolioSummaryRepository extends ReactiveCrudRepository<PortfolioSummary, Long> {

    Mono<PortfolioSummary> findFirstByOrderByComputedAtDesc();
}
