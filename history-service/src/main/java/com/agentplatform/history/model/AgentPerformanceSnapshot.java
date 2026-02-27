package com.agentplatform.history.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Pre-aggregated agent performance projection — eliminates full-table scans
 * of {@code decision_history} on every orchestration cycle.
 *
 * <p>Running-sum columns ({@code sumConfidence}, {@code sumLatencyMs},
 * {@code sumWins}, {@code totalDecisions}) enable atomic UPSERT with inline
 * recomputation of derived averages.
 */
@Data
@NoArgsConstructor
@Table("agent_performance_snapshot")
public class AgentPerformanceSnapshot {

    @Id
    private String agentName;

    private double historicalAccuracyScore;

    private double latencyWeight;

    private double winRate;

    private double avgConfidence;

    private double avgLatencyMs;

    private long totalDecisions;

    private double sumConfidence;

    private double sumLatencyMs;

    private double sumWins;

    private String regimeBias;

    private LocalDateTime lastUpdated;
}
