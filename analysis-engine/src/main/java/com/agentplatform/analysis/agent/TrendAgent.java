package com.agentplatform.analysis.agent;

import com.agentplatform.analysis.indicator.TechnicalIndicators;
import com.agentplatform.common.exception.AgentException;
import com.agentplatform.common.model.AnalysisResult;
import com.agentplatform.common.model.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class TrendAgent implements AnalysisAgent {

    private static final Logger log = LoggerFactory.getLogger(TrendAgent.class);

    @Override
    public String agentName() { return "TrendAgent"; }

    @Override
    public AnalysisResult analyze(Context context) {
        log.info("[TrendAgent] Analyzing symbol={}", context.symbol());

        List<Double> prices = context.prices();
        if (prices == null || prices.isEmpty()) {
            throw new AgentException(agentName(), "No price data in context for symbol=" + context.symbol());
        }

        double currentPrice = prices.get(0);
        double sma20  = TechnicalIndicators.sma(prices, 20);
        double sma50  = TechnicalIndicators.sma(prices, 50);
        double ema12  = TechnicalIndicators.ema(prices, 12);
        double macd   = TechnicalIndicators.macd(prices);
        double stdDev = TechnicalIndicators.stdDev(prices, 20);

        String trend = TechnicalIndicators.trendSignal(sma20, sma50, currentPrice);

        String signal = switch (trend) {
            case "UPTREND"   -> macd > 0 ? "BUY"  : "HOLD";
            case "DOWNTREND" -> macd < 0 ? "SELL" : "HOLD";
            default          -> "HOLD";
        };

        double confidence = computeConfidence(trend, macd, stdDev, currentPrice, sma20);

        String summary = String.format(
            "Trend: %s | Price=%.2f | SMA20=%.2f | SMA50=%.2f | MACD=%.4f | StdDev=%.2f → Signal: %s",
            trend, currentPrice, isNaN(sma20), isNaN(sma50), isNaN(macd), isNaN(stdDev), signal
        );

        return AnalysisResult.of(agentName(), summary, signal, confidence, Map.of(
            "trend",        trend,
            "currentPrice", currentPrice,
            "sma20",        Double.isNaN(sma20) ? "N/A" : sma20,
            "sma50",        Double.isNaN(sma50) ? "N/A" : sma50,
            "ema12",        Double.isNaN(ema12) ? "N/A" : ema12,
            "macd",         Double.isNaN(macd) ? "N/A" : macd,
            "stdDev",       Double.isNaN(stdDev) ? "N/A" : stdDev
        ));
    }

    private double computeConfidence(String trend, double macd, double stdDev,
                                      double price, double sma20) {
        if ("SIDEWAYS".equals(trend) || "INSUFFICIENT_DATA".equals(trend)) return 0.3;
        double base = 0.5;
        // MACD alignment boosts confidence
        if (!Double.isNaN(macd) && ((trend.equals("UPTREND") && macd > 0)
            || (trend.equals("DOWNTREND") && macd < 0))) base += 0.2;
        // Low volatility boosts confidence
        if (!Double.isNaN(stdDev) && !Double.isNaN(sma20) && sma20 > 0) {
            double cv = stdDev / sma20; // coefficient of variation
            if (cv < 0.01) base += 0.15;
            else if (cv > 0.03) base -= 0.1;
        }
        return Math.min(Math.max(base, 0.0), 1.0);
    }

    private double isNaN(double v) { return Double.isNaN(v) ? 0 : v; }
}
