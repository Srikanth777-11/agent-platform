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

/**
 * Evaluates market risk using RSI overbought/oversold levels,
 * volatility (stddev), and drawdown from recent high.
 */
@Component
public class RiskAgent implements AnalysisAgent {

    private static final Logger log = LoggerFactory.getLogger(RiskAgent.class);

    private static final double RSI_PERIOD = 14;
    private static final double OVERBOUGHT = 70;
    private static final double OVERSOLD = 30;
    private static final double MAX_DRAWDOWN_THRESHOLD = 0.05;  // 5% drawdown = elevated risk

    @Override
    public String agentName() { return "RiskAgent"; }

    @Override
    public AnalysisResult analyze(Context context) {
        log.info("[RiskAgent] Analyzing symbol={}", context.symbol());

        List<Double> prices = context.prices();
        if (prices == null || prices.isEmpty()) {
            throw new AgentException(agentName(), "No price data for symbol=" + context.symbol());
        }

        double rsi       = TechnicalIndicators.rsi(prices, 14);
        double stdDev    = TechnicalIndicators.stdDev(prices, 20);
        double sma20     = TechnicalIndicators.sma(prices, 20);
        double current   = prices.get(0);

        // Drawdown from 20-period high
        double high20 = prices.subList(0, Math.min(20, prices.size())).stream()
            .mapToDouble(Double::doubleValue).max().orElse(current);
        double drawdown = (high20 - current) / high20;

        // Risk level
        String riskLevel = evaluateRisk(rsi, drawdown, stdDev, sma20);
        String signal    = riskToSignal(riskLevel, rsi);
        double confidence = riskConfidence(rsi, drawdown);

        String rsiStr    = Double.isNaN(rsi)     ? "N/A" : String.format("%.2f", rsi);
        String stdDevStr = Double.isNaN(stdDev)  ? "N/A" : String.format("%.4f", stdDev);

        String summary = String.format(
            "Risk Level: %s | RSI=%-6s | Drawdown=%.2f%% | StdDev=%s → Signal: %s",
            riskLevel, rsiStr, drawdown * 100, stdDevStr, signal
        );

        return AnalysisResult.of(agentName(), summary, signal, confidence, Map.of(
            "riskLevel",  riskLevel,
            "rsi",        Double.isNaN(rsi) ? "N/A" : rsi,
            "rsiSignal",  TechnicalIndicators.rsiSignal(rsi),
            "drawdown",   String.format("%.2f%%", drawdown * 100),
            "stdDev",     Double.isNaN(stdDev) ? "N/A" : stdDev,
            "high20",     high20,
            "currentPrice", current
        ));
    }

    private String evaluateRisk(double rsi, double drawdown, double stdDev, double sma20) {
        int riskPoints = 0;

        if (!Double.isNaN(rsi)) {
            if (rsi > OVERBOUGHT) riskPoints += 2;
            else if (rsi < OVERSOLD) riskPoints += 1;  // oversold = potential reversal opportunity
        }
        if (drawdown > MAX_DRAWDOWN_THRESHOLD) riskPoints += 2;
        if (!Double.isNaN(stdDev) && !Double.isNaN(sma20) && sma20 > 0) {
            double cv = stdDev / sma20;
            if (cv > 0.03) riskPoints += 1;
        }

        if (riskPoints >= 4) return "HIGH";
        if (riskPoints >= 2) return "MEDIUM";
        return "LOW";
    }

    private String riskToSignal(String riskLevel, double rsi) {
        return switch (riskLevel) {
            case "HIGH"   -> !Double.isNaN(rsi) && rsi > OVERBOUGHT ? "SELL" : "WATCH";
            case "MEDIUM" -> "HOLD";
            default       -> !Double.isNaN(rsi) && rsi < OVERSOLD ? "BUY" : "HOLD";
        };
    }

    private double riskConfidence(double rsi, double drawdown) {
        double base = 0.5;
        if (!Double.isNaN(rsi)) {
            if (rsi > 75 || rsi < 25) base += 0.25;
            else if (rsi > 65 || rsi < 35) base += 0.1;
        }
        if (drawdown > 0.08) base += 0.15;
        return Math.min(base, 1.0);
    }
}
