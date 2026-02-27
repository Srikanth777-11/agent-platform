package com.agentplatform.history.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Per-symbol trend metrics projection — pre-computed from the most recent
 * decisions to avoid repeated window scans.
 */
@Data
@NoArgsConstructor
@Table("decision_metrics_projection")
public class DecisionMetricsProjection {

    @Id
    private String symbol;

    private double lastConfidence;

    private double confidenceSlope5;

    private int divergenceStreak;

    private int momentumStreak;

    private LocalDateTime lastUpdated;
}
