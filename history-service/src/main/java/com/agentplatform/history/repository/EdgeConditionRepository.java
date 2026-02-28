package com.agentplatform.history.repository;

import com.agentplatform.history.model.EdgeCondition;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface EdgeConditionRepository extends ReactiveCrudRepository<EdgeCondition, Long> {

    /**
     * Atomic UPSERT: inserts a new condition row or increments win/loss counts
     * and recomputes win_rate inline.
     *
     * @param tradingSession  e.g. OPENING_BURST, POWER_HOUR
     * @param marketRegime    e.g. VOLATILE, TRENDING
     * @param directionalBias e.g. BULLISH, STRONG_BEARISH
     * @param signal          BUY or SELL
     * @param win             1 if outcome_percent > 0, else 0
     * @param loss            1 if outcome_percent <= 0, else 0
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
