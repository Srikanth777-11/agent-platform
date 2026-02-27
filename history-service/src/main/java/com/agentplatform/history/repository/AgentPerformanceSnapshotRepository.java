package com.agentplatform.history.repository;

import com.agentplatform.history.model.AgentPerformanceSnapshot;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface AgentPerformanceSnapshotRepository
        extends ReactiveCrudRepository<AgentPerformanceSnapshot, String> {

    /**
     * Atomic UPSERT: inserts a new agent row or updates running sums and
     * recomputes derived averages inline.
     *
     * @param agentName  natural PK
     * @param confidence agent confidence from this decision
     * @param latencyMs  decision-level latency in milliseconds
     * @param win        1.0 if agent signal matched consensus, else 0.0
     * @param regimeBias current market regime name (nullable)
     */
    @Modifying
    @Query("""
        INSERT INTO agent_performance_snapshot
            (agent_name, sum_confidence, sum_latency_ms, sum_wins, total_decisions,
             avg_confidence, avg_latency_ms, win_rate, historical_accuracy_score,
             regime_bias, last_updated)
        VALUES
            (:agentName, :confidence, :latencyMs, :win, 1,
             :confidence, :latencyMs, :win, :confidence,
             :regimeBias, NOW())
        ON CONFLICT (agent_name) DO UPDATE SET
            sum_confidence            = agent_performance_snapshot.sum_confidence + :confidence,
            sum_latency_ms            = agent_performance_snapshot.sum_latency_ms + :latencyMs,
            sum_wins                  = agent_performance_snapshot.sum_wins + :win,
            total_decisions           = agent_performance_snapshot.total_decisions + 1,
            avg_confidence            = (agent_performance_snapshot.sum_confidence + :confidence)
                                        / (agent_performance_snapshot.total_decisions + 1),
            avg_latency_ms            = (agent_performance_snapshot.sum_latency_ms + :latencyMs)
                                        / (agent_performance_snapshot.total_decisions + 1),
            win_rate                  = (agent_performance_snapshot.sum_wins + :win)
                                        / (agent_performance_snapshot.total_decisions + 1),
            historical_accuracy_score = (agent_performance_snapshot.sum_confidence + :confidence)
                                        / (agent_performance_snapshot.total_decisions + 1),
            regime_bias               = :regimeBias,
            last_updated              = NOW()
        """)
    Mono<Void> upsertAgent(String agentName, double confidence, double latencyMs,
                           double win, String regimeBias);

    /**
     * Second-pass normalization: sets each agent's {@code latency_weight} to
     * {@code avg_latency_ms / maxAvgLatency}, producing a [0.0, 1.0] range
     * where 1.0 = slowest agent.
     *
     * @param maxAvgLatency the highest avg_latency_ms across all agents
     */
    @Modifying
    @Query("""
        UPDATE agent_performance_snapshot
        SET latency_weight = CASE WHEN :maxAvgLatency > 0
                                  THEN avg_latency_ms / :maxAvgLatency
                                  ELSE 0.0 END
        """)
    Mono<Void> normalizeLatencyWeights(double maxAvgLatency);
}
