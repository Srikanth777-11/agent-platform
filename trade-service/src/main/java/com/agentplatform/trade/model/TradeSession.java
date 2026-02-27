package com.agentplatform.trade.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Operator trade awareness record — tracks entry/exit lifecycle of
 * individual trades for observability. No orchestrator linkage.
 */
@Data
@NoArgsConstructor
@Table("trade_sessions")
public class TradeSession {

    @Id
    private Long id;

    private String symbol;

    private LocalDateTime entryTime;

    private Double entryPrice;

    private Double entryConfidence;

    private String entryRegime;

    private String entryMomentum;

    private LocalDateTime exitTime;

    private Double exitPrice;

    private Double pnl;

    private Long durationMs;
}
