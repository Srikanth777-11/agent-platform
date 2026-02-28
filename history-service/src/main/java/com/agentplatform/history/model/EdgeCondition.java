package com.agentplatform.history.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Phase-38a: WinConditionRegistry entry.
 *
 * <p>Tracks win/loss outcomes per unique (tradingSession, marketRegime,
 * directionalBias, signal) combination. Populated only from LIVE_AI decisions —
 * REPLAY_CONSENSUS_ONLY rows are excluded.
 *
 * <p>Phase-38a = passive mode: data collection only, no trade blocking.
 * Phase-38b will activate the gate once conditions have ≥20 samples.
 */
@Data
@NoArgsConstructor
@Table("edge_conditions")
public class EdgeCondition {

    @Id
    private Long id;

    private String tradingSession;
    private String marketRegime;
    private String directionalBias;
    private String signal;

    private int winCount;
    private int lossCount;
    private int totalCount;
    private double winRate;

    private LocalDateTime lastUpdated;
}
