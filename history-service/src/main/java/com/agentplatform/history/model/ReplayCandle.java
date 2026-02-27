package com.agentplatform.history.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Phase-32: One 5-minute OHLCV candle downloaded from Angel One SmartAPI,
 * stored in {@code replay_candles} for historical replay tuning.
 */
@Data
@NoArgsConstructor
@Table("replay_candles")
public class ReplayCandle {

    @Id
    private Long id;

    private String        symbol;
    private LocalDateTime candleTime;
    private double        open;
    private double        high;
    private double        low;
    private double        close;
    private long          volume;
}
