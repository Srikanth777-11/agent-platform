package com.agentplatform.common.classifier;

import com.agentplatform.common.model.Context;
import com.agentplatform.common.model.MarketRegime;

import java.util.List;

/**
 * Pure stateless classifier that maps a {@link Context} snapshot to a
 * {@link MarketRegime}.
 *
 * <p>Classification rules (evaluated in priority order):
 * <ol>
 *   <li>stdDev &gt; 7            → {@link MarketRegime#VOLATILE}</li>
 *   <li>price &gt; SMA50 AND SMA20 → {@link MarketRegime#TRENDING}</li>
 *   <li>stdDev &lt; 3            → {@link MarketRegime#CALM}</li>
 *   <li>otherwise                → {@link MarketRegime#RANGING}</li>
 * </ol>
 *
 * <p>No reactive types. No logging. No side-effects.
 */
public final class MarketRegimeClassifier {

    private MarketRegimeClassifier() {}

    /**
     * Classify the current market regime from the given context.
     *
     * @param context populated by the orchestrator after market-data fetch
     * @return detected {@link MarketRegime}; {@link MarketRegime#UNKNOWN} when
     *         prices list is null or empty
     */
    public static MarketRegime classify(Context context) {
        List<Double> prices = context.prices();
        if (prices == null || prices.isEmpty()) {
            return MarketRegime.UNKNOWN;
        }

        // ── latest price ───────────────────────────────────────────────────
        double latestPrice = latestClose(context, prices);

        // ── standard deviation of price history ────────────────────────────
        double stdDev = stdDev(prices);

        // ── classification in priority order ───────────────────────────────
        if (stdDev > 7.0) {
            return MarketRegime.VOLATILE;
        }

        double sma20 = sma(prices, 20);
        double sma50 = sma(prices, 50);
        if (latestPrice > sma50 && latestPrice > sma20) {
            return MarketRegime.TRENDING;
        }

        if (stdDev < 3.0) {
            return MarketRegime.CALM;
        }

        return MarketRegime.RANGING;
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static double latestClose(Context context, List<Double> prices) {
        if (context.marketData() != null) {
            Object raw = context.marketData().get("latestClose");
            if (raw instanceof Number) {
                return ((Number) raw).doubleValue();
            }
        }
        return prices.get(prices.size() - 1);
    }

    private static double stdDev(List<Double> prices) {
        double mean = prices.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = prices.stream()
            .mapToDouble(p -> (p - mean) * (p - mean))
            .average()
            .orElse(0.0);
        return Math.sqrt(variance);
    }

    private static double sma(List<Double> prices, int period) {
        int n    = prices.size();
        int from = Math.max(0, n - period);
        double sum   = 0.0;
        int    count = 0;
        for (int i = from; i < n; i++) {
            sum += prices.get(i);
            count++;
        }
        return count > 0 ? sum / count : 0.0;
    }
}
