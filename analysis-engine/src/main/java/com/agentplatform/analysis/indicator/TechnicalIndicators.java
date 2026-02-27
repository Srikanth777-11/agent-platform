package com.agentplatform.analysis.indicator;

import java.util.List;

/**
 * Pure calculation utilities for technical indicators.
 * Input prices are expected newest-first (index 0 = most recent close).
 */
public final class TechnicalIndicators {

    private TechnicalIndicators() {}

    // ── RSI ─────────────────────────────────────────────────────────────────

    /**
     * Computes RSI using Wilder's Smoothed Moving Average.
     * @param prices  closing prices, newest-first
     * @param period  lookback period (typically 14)
     * @return RSI value 0–100, or NaN if insufficient data
     */
    public static double rsi(List<Double> prices, int period) {
        if (prices == null || prices.size() < period + 1) return Double.NaN;

        // Reverse to oldest-first for calculation
        List<Double> oldest = prices.reversed();
        int n = oldest.size();

        double avgGain = 0;
        double avgLoss = 0;

        // Initial average over first `period` changes
        for (int i = 1; i <= period; i++) {
            double change = oldest.get(i) - oldest.get(i - 1);
            if (change > 0) avgGain += change;
            else avgLoss += Math.abs(change);
        }
        avgGain /= period;
        avgLoss /= period;

        // Wilder's smoothing for remaining periods
        for (int i = period + 1; i < n; i++) {
            double change = oldest.get(i) - oldest.get(i - 1);
            double gain = Math.max(change, 0);
            double loss = Math.max(-change, 0);
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
        }

        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    // ── Simple Moving Average ────────────────────────────────────────────────

    /**
     * @param prices  closing prices, newest-first
     * @param period  number of periods
     * @return SMA value, or NaN if insufficient data
     */
    public static double sma(List<Double> prices, int period) {
        if (prices == null || prices.size() < period) return Double.NaN;
        double sum = 0;
        for (int i = 0; i < period; i++) sum += prices.get(i);
        return sum / period;
    }

    // ── Exponential Moving Average ───────────────────────────────────────────

    /**
     * @param prices  closing prices, newest-first
     * @param period  EMA period
     * @return most-recent EMA value, or NaN if insufficient data
     */
    public static double ema(List<Double> prices, int period) {
        if (prices == null || prices.size() < period) return Double.NaN;
        List<Double> oldest = prices.reversed();
        double k = 2.0 / (period + 1);
        double ema = oldest.get(0);
        for (int i = 1; i < oldest.size(); i++) {
            ema = oldest.get(i) * k + ema * (1 - k);
        }
        return ema;
    }

    // ── MACD ────────────────────────────────────────────────────────────────

    /**
     * MACD line = EMA(12) - EMA(26)
     */
    public static double macd(List<Double> prices) {
        double ema12 = ema(prices, 12);
        double ema26 = ema(prices, 26);
        if (Double.isNaN(ema12) || Double.isNaN(ema26)) return Double.NaN;
        return ema12 - ema26;
    }

    // ── Volatility (Standard Deviation) ─────────────────────────────────────

    public static double stdDev(List<Double> prices, int period) {
        if (prices == null || prices.size() < period) return Double.NaN;
        double mean = sma(prices, period);
        double variance = 0;
        for (int i = 0; i < period; i++) {
            double diff = prices.get(i) - mean;
            variance += diff * diff;
        }
        return Math.sqrt(variance / period);
    }

    // ── Signal helpers ───────────────────────────────────────────────────────

    public static String rsiSignal(double rsi) {
        if (Double.isNaN(rsi)) return "INSUFFICIENT_DATA";
        if (rsi < 30) return "OVERSOLD";
        if (rsi > 70) return "OVERBOUGHT";
        return "NEUTRAL";
    }

    public static String trendSignal(double sma20, double sma50, double currentPrice) {
        if (Double.isNaN(sma20) || Double.isNaN(sma50)) return "INSUFFICIENT_DATA";
        if (currentPrice > sma20 && sma20 > sma50) return "UPTREND";
        if (currentPrice < sma20 && sma20 < sma50) return "DOWNTREND";
        return "SIDEWAYS";
    }
}
