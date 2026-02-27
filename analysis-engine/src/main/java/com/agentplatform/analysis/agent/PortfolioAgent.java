package com.agentplatform.analysis.agent;

import com.agentplatform.analysis.indicator.TechnicalIndicators;
import com.agentplatform.common.model.AnalysisResult;
import com.agentplatform.common.model.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Evaluates portfolio-level signals: trend alignment across timeframes,
 * SMA crossovers, and momentum divergence using rule-based checks.
 */
@Component
public class PortfolioAgent implements AnalysisAgent {

    private static final Logger log = LoggerFactory.getLogger(PortfolioAgent.class);

    @Override
    public String agentName() { return "PortfolioAgent"; }

    @Override
    public AnalysisResult analyze(Context context) {
        log.info("[PortfolioAgent] Analyzing symbol={}", context.symbol());

        List<Double> prices = context.prices();
        if (prices == null || prices.size() < 10) {
            return AnalysisResult.of(agentName(), "Insufficient data", "HOLD", 0.1, Map.of());
        }

        double current = prices.get(0);
        double sma10   = TechnicalIndicators.sma(prices, 10);
        double sma20   = TechnicalIndicators.sma(prices, 20);
        double sma50   = TechnicalIndicators.sma(prices, 50);

        List<String> signals = new ArrayList<>();
        int bullishCount = 0;
        int bearishCount = 0;

        // Rule 1: Golden / Death cross (SMA10 vs SMA20)
        if (!Double.isNaN(sma10) && !Double.isNaN(sma20)) {
            if (sma10 > sma20) { signals.add("GOLDEN_CROSS_10_20"); bullishCount++; }
            else               { signals.add("DEATH_CROSS_10_20");  bearishCount++; }
        }

        // Rule 2: Price above/below SMA50
        if (!Double.isNaN(sma50)) {
            if (current > sma50) { signals.add("PRICE_ABOVE_SMA50"); bullishCount++; }
            else                 { signals.add("PRICE_BELOW_SMA50"); bearishCount++; }
        }

        // Rule 3: Short-term momentum (last 5 closes trending up/down)
        if (prices.size() >= 5) {
            double momentum = prices.get(0) - prices.get(4);
            if (momentum > 0) { signals.add("SHORT_MOMENTUM_UP");   bullishCount++; }
            else              { signals.add("SHORT_MOMENTUM_DOWN");  bearishCount++; }
        }

        // Rule 4: Volume proxy — high volatility can suggest institutional activity
        double stdDev = TechnicalIndicators.stdDev(prices, 10);
        if (!Double.isNaN(stdDev) && !Double.isNaN(sma10) && sma10 > 0) {
            double vol = stdDev / sma10;
            signals.add(String.format("VOLATILITY=%.3f%%", vol * 100));
        }

        String finalSignal;
        double confidence;
        if (bullishCount > bearishCount) {
            finalSignal = "BUY";
            confidence  = 0.4 + (0.15 * bullishCount);
        } else if (bearishCount > bullishCount) {
            finalSignal = "SELL";
            confidence  = 0.4 + (0.15 * bearishCount);
        } else {
            finalSignal = "HOLD";
            confidence  = 0.35;
        }
        confidence = Math.min(confidence, 0.95);

        String summary = String.format(
            "Portfolio check: bullish=%d bearish=%d | signals=%s → %s",
            bullishCount, bearishCount, signals, finalSignal
        );

        return AnalysisResult.of(agentName(), summary, finalSignal, confidence, Map.of(
            "bullishSignals",  bullishCount,
            "bearishSignals",  bearishCount,
            "triggeredRules",  signals,
            "sma10",  Double.isNaN(sma10) ? "N/A" : sma10,
            "sma20",  Double.isNaN(sma20) ? "N/A" : sma20,
            "sma50",  Double.isNaN(sma50) ? "N/A" : sma50
        ));
    }
}
