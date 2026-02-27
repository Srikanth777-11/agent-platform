package com.agentplatform.common.reflection;

import com.agentplatform.common.model.TradeReflectionStats;
import com.agentplatform.common.posture.TradePosture;

/**
 * Pure logic — updates posture-level trade outcome statistics.
 * No side effects. No dependencies.
 */
public final class ReflectionInterpreter {

    public static TradeReflectionStats updateStats(TradeReflectionStats current,
                                                   TradePosture posture,
                                                   double pnl) {
        long totalTrades = (current != null ? current.totalTrades() : 0) + 1;
        long wins        = (current != null ? current.wins() : 0) + (pnl > 0 ? 1 : 0);
        long losses      = (current != null ? current.losses() : 0) + (pnl <= 0 ? 1 : 0);
        double prevAvg   = current != null ? current.avgPnl() : 0.0;
        double avgPnl    = prevAvg + (pnl - prevAvg) / totalTrades;

        return new TradeReflectionStats(posture, totalTrades, wins, losses, avgPnl);
    }
}
