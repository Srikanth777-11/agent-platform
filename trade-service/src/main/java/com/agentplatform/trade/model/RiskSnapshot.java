package com.agentplatform.trade.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Point-in-time risk snapshot for a symbol — computed by the trade-service
 * risk engine. Operator-facing only, no automation linkage.
 */
@Data
@NoArgsConstructor
@Table("risk_snapshots")
public class RiskSnapshot {

    @Id
    private Long id;

    private String symbol;

    private Double positionSize;

    private Double unrealizedPnl;

    private Double drawdownPct;

    private Double exposurePct;

    private String riskLevel;

    private LocalDateTime computedAt;
}
