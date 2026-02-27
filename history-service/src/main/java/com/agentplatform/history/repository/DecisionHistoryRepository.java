package com.agentplatform.history.repository;

import com.agentplatform.history.model.DecisionHistory;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface DecisionHistoryRepository extends ReactiveCrudRepository<DecisionHistory, Long> {

    Flux<DecisionHistory> findBySymbol(String symbol);

    Flux<DecisionHistory> findBySymbolOrderBySavedAtDesc(String symbol);

    Flux<DecisionHistory> findByTraceId(String traceId);

    /**
     * Returns the most recent decision for each distinct symbol using a
     * correlated subquery. One row per symbol — the row whose {@code saved_at}
     * equals the maximum {@code saved_at} for that symbol.
     */
    @Query("""
        SELECT d.* FROM decision_history d
        INNER JOIN (
            SELECT symbol, MAX(saved_at) AS max_saved_at
            FROM decision_history
            GROUP BY symbol
        ) latest ON d.symbol = latest.symbol AND d.saved_at = latest.max_saved_at
        ORDER BY d.symbol
        """)
    Flux<DecisionHistory> findLatestPerSymbol();

    @Query("""
        SELECT * FROM decision_history
        WHERE symbol = :symbol
        ORDER BY saved_at ASC
        LIMIT :limit
        """)
    Flux<DecisionHistory> findReplayCandles(String symbol, int limit);

    /**
     * Phase-24: returns recent BUY/SELL decisions without a resolved P&L outcome.
     * Used by the orchestrator on each cycle to resolve open signal outcomes.
     */
    @Query("""
        SELECT * FROM decision_history
        WHERE symbol = :symbol
          AND final_signal IN ('BUY', 'SELL')
          AND (outcome_resolved IS NULL OR outcome_resolved = false)
          AND saved_at >= :since
        ORDER BY saved_at DESC
        LIMIT :limit
        """)
    Flux<DecisionHistory> findUnresolvedSignals(String symbol, java.time.LocalDateTime since, int limit);

    @Query("""
        SELECT * FROM decision_history
        WHERE symbol = :symbol
        ORDER BY saved_at DESC
        LIMIT :limit
        """)
    Flux<DecisionHistory> findRecentBySymbol(String symbol, int limit);

}
