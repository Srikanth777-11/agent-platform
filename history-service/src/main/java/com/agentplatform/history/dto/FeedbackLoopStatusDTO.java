package com.agentplatform.history.dto;

/**
 * Per-agent feedback loop status showing whether adaptive weights are driven
 * by real market outcomes ("market-truth") or the fallback alignment heuristic.
 *
 * <p>Returned by {@code GET /api/v1/history/feedback-loop-status}.
 *
 * @param agentName      canonical agent identifier
 * @param winRate        market-truth win rate (resolved trades only), or 0.5 if fallback
 * @param sampleSize     number of resolved outcomes used to compute win rate
 * @param adaptiveWeight current historicalAccuracyScore from snapshot (proxy for weight)
 * @param source         "market-truth" if ≥5 resolved samples, "fallback" otherwise
 */
public record FeedbackLoopStatusDTO(
    String agentName,
    double winRate,
    int    sampleSize,
    double adaptiveWeight,
    String source
) {}
