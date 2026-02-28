package com.agentplatform.analysis.agent;

import com.agentplatform.analysis.indicator.TechnicalIndicators;
import com.agentplatform.common.cognition.DirectionalBias;
import com.agentplatform.common.exception.AgentException;
import com.agentplatform.common.model.AnalysisResult;
import com.agentplatform.common.model.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
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

        // Phase-33: 5-vote directional bias score
        DirectionalBias bias = computeDirectionalBias(trend, macd, currentPrice, sma20, ema12, prices);

        String summary = String.format(
            "Trend: %s | Price=%.2f | SMA20=%.2f | SMA50=%.2f | MACD=%.4f | StdDev=%.2f | Bias: %s → Signal: %s",
            trend, currentPrice, isNaN(sma20), isNaN(sma50), isNaN(macd), isNaN(stdDev), bias.name(), signal
        );

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("trend",          trend);
        metadata.put("currentPrice",   currentPrice);
        metadata.put("sma20",          Double.isNaN(sma20) ? "N/A" : sma20);
        metadata.put("sma50",          Double.isNaN(sma50) ? "N/A" : sma50);
        metadata.put("ema12",          Double.isNaN(ema12) ? "N/A" : ema12);
        metadata.put("macd",           Double.isNaN(macd) ? "N/A" : macd);
        metadata.put("stdDev",         Double.isNaN(stdDev) ? "N/A" : stdDev);
        metadata.put("directionalBias", bias.name());

        return AnalysisResult.of(agentName(), summary, signal, confidence, metadata);
    }

    /**
     * Phase-33: 5-factor directional bias vote (score range −5 to +5).
     *
     * <ol>
     *   <li>Trend direction   : UPTREND=+1, DOWNTREND=−1</li>
     *   <li>MACD sign         : macd > 0 → +1, macd < 0 → −1</li>
     *   <li>Price vs SMA20    : price > sma20 → +1, price < sma20 → −1</li>
     *   <li>Price vs EMA12    : price > ema12 → +1, price < ema12 → −1</li>
     *   <li>5-candle momentum : prices[0] > prices[4] → +1, else → −1</li>
     * </ol>
     */
    private DirectionalBias computeDirectionalBias(String trend, double macd, double price,
                                                    double sma20, double ema12,
                                                    List<Double> prices) {
        int score = 0;

        // Vote 1: trend
        if ("UPTREND".equals(trend))        score++;
        else if ("DOWNTREND".equals(trend)) score--;

        // Vote 2: MACD sign
        if (!Double.isNaN(macd)) {
            if (macd > 0) score++;
            else if (macd < 0) score--;
        }

        // Vote 3: price vs SMA20
        if (!Double.isNaN(sma20) && sma20 > 0) {
            if (price > sma20) score++;
            else if (price < sma20) score--;
        }

        // Vote 4: price vs EMA12
        if (!Double.isNaN(ema12) && ema12 > 0) {
            if (price > ema12) score++;
            else if (price < ema12) score--;
        }

        // Vote 5: 5-candle momentum (prices[0] = most recent, prices[4] = 4 candles ago)
        if (prices.size() >= 5) {
            double priceNow  = prices.get(0);
            double pricePrev = prices.get(4);
            if (priceNow > pricePrev) score++;
            else if (priceNow < pricePrev) score--;
        }

        if      (score >= 3)  return DirectionalBias.STRONG_BULLISH;
        else if (score >= 1)  return DirectionalBias.BULLISH;
        else if (score == 0)  return DirectionalBias.NEUTRAL;
        else if (score >= -2) return DirectionalBias.BEARISH;
        else                  return DirectionalBias.STRONG_BEARISH;
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
