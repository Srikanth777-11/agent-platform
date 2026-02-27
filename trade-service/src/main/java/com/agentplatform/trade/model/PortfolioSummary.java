package com.agentplatform.trade.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Aggregated portfolio-level summary — single row per computation cycle.
 * Operator intelligence only, no automation linkage.
 */
@Data
@NoArgsConstructor
@Table("portfolio_summary")
public class PortfolioSummary {

    @Id
    private Long id;

    private int openPositions;

    private Double totalExposure;

    private Double totalUnrealizedPnl;

    private Double totalRealizedPnl;

    private Double maxDrawdownPct;

    private String overallRiskLevel;

    private LocalDateTime computedAt;
}
