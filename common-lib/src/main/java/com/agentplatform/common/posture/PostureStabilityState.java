package com.agentplatform.common.posture;

import java.time.Instant;

/**
 * Tracks the last emitted posture and when it was set,
 * enabling stability window suppression of low-severity changes.
 */
public record PostureStabilityState(
    TradePosture lastPosture,
    Instant      lastUpdated
) {}
