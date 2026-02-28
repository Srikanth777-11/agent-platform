package com.agentplatform.trade.dto;

/**
 * Phase-43: Daily intraday risk state snapshot for DailyRiskGovernor.
 * Computed from in-memory state in TradeService. Resets on service restart.
 */
public record DailyRiskStateDTO(
    String  symbol,
    double  dailyPnLPercent,      // cumulative today's P&L as % (positive = profit)
    int     consecutiveLosses,    // current loss streak (resets on win)
    int     dailyTradeCount,      // total trades executed today
    boolean killSwitch,           // true = no more trades today
    String  killReason            // why kill switch was triggered (null if not triggered)
) {}
