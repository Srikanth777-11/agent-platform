package com.agentplatform.history.repository;

import com.agentplatform.history.model.DecisionMetricsProjection;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface DecisionMetricsProjectionRepository
        extends ReactiveCrudRepository<DecisionMetricsProjection, String> {

    /**
     * Atomic UPSERT: inserts or replaces per-symbol trend metrics.
     */
    @Modifying
    @Query("""
        INSERT INTO decision_metrics_projection
            (symbol, last_confidence, confidence_slope_5, divergence_streak, momentum_streak, last_updated)
        VALUES
            (:symbol, :lastConfidence, :slope5, :divStreak, :momStreak, NOW())
        ON CONFLICT (symbol) DO UPDATE SET
            last_confidence    = :lastConfidence,
            confidence_slope_5 = :slope5,
            divergence_streak  = :divStreak,
            momentum_streak    = :momStreak,
            last_updated       = NOW()
        """)
    Mono<Void> upsertMetrics(String symbol, double lastConfidence, double slope5,
                             int divStreak, int momStreak);
}
