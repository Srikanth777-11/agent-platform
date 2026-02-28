package com.agentplatform.history.repository;

import com.agentplatform.history.model.EdgeCondition;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface EdgeConditionRepository extends ReactiveCrudRepository<EdgeCondition, Long> {

    /**
     * Phase-45: Finds a single condition row by its 4-part composite key.
     * Returns the full entity (including winCount + lossCount) so the
     * BayesianEdgeEstimator can compute the posterior from raw counts.
     * Returns empty Mono if no data has been recorded for this condition yet.
     */
    @Query("""
        SELECT * FROM edge_conditions
        WHERE trading_session = :tradingSession
          AND market_regime   = :marketRegime
          AND directional_bias = :directionalBias
          AND signal           = :signal
        """)
    Mono<EdgeCondition> findByCondition(String tradingSession, String marketRegime,
                                        String directionalBias, String signal);

    /**
     * Returns all conditions sorted by win_rate desc — used for analytics / UI.
     */
    @Query("SELECT * FROM edge_conditions ORDER BY win_rate DESC")
    Flux<EdgeCondition> findAllOrderedByWinRate();

    /**
     * Atomic UPSERT: inserts a new condition row or increments win/loss counts
     * and recomputes win_rate inline.
     *
     * @param tradingSession  e.g. OPENING_PHASE_2, POWER_HOUR
     * @param marketRegime    e.g. VOLATILE, TRENDING
     * @param directionalBias e.g. BULLISH, STRONG_BEARISH
     * @param signal          BUY or SELL
     * @param win             1 if profitable outcome, else 0
     * @param loss            1 if losing outcome, else 0
     */
    @Modifying
    @Query("""
        INSERT INTO edge_conditions
            (trading_session, market_regime, directional_bias, signal,
             win_count, loss_count, total_count, win_rate, last_updated)
        VALUES
            (:tradingSession, :marketRegime, :directionalBias, :signal,
             :win, :loss, 1,
             CAST(:win AS DOUBLE PRECISION),
             NOW())
        ON CONFLICT (trading_session, market_regime, directional_bias, signal) DO UPDATE SET
            win_count    = edge_conditions.win_count  + :win,
            loss_count   = edge_conditions.loss_count + :loss,
            total_count  = edge_conditions.total_count + 1,
            win_rate     = CAST(edge_conditions.win_count + :win AS DOUBLE PRECISION)
                           / (edge_conditions.total_count + 1),
            last_updated = NOW()
        """)
    Mono<Void> upsertCondition(String tradingSession, String marketRegime,
                               String directionalBias, String signal,
                               int win, int loss);
}
