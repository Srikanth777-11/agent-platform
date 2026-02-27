package com.agentplatform.common.cognition;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase-21 rolling-window calculator.
 *
 * <p>Maintains a per-symbol in-memory deque of the last {@value #WINDOW_SIZE}
 * {@link ReflectionState} values. Derives {@link ReflectionPersistence} from
 * the distribution of ALIGNED / DRIFTING / UNSTABLE counts.
 *
 * <p>No external calls. No Spring dependency. Thread-safe via per-symbol
 * synchronized blocks.
 */
public final class ReflectionPersistenceCalculator {

    static final int WINDOW_SIZE = 5;

    private static final ConcurrentHashMap<String, Deque<ReflectionState>> windows =
        new ConcurrentHashMap<>();

    private ReflectionPersistenceCalculator() {}

    /**
     * Appends {@code latest} to the per-symbol window, then derives persistence.
     *
     * @param symbol ticker symbol — window key
     * @param latest {@link ReflectionState} from the current cycle
     * @return {@link ReflectionPersistence} derived from the updated window
     */
    public static ReflectionPersistence compute(String symbol, ReflectionState latest) {
        Deque<ReflectionState> window =
            windows.computeIfAbsent(symbol, k -> new ArrayDeque<>(WINDOW_SIZE));
        synchronized (window) {
            window.addLast(latest);
            if (window.size() > WINDOW_SIZE) window.pollFirst();
            return derive(window);
        }
    }

    private static ReflectionPersistence derive(Deque<ReflectionState> window) {
        long unstable = window.stream().filter(s -> s == ReflectionState.UNSTABLE).count();
        long drifting = window.stream().filter(s -> s == ReflectionState.DRIFTING).count();

        if (unstable >= 3) return ReflectionPersistence.CHRONIC_INSTABILITY;
        if (unstable >= 1) return ReflectionPersistence.HARD_DRIFT;
        if (drifting >= 2) return ReflectionPersistence.SOFT_DRIFT;
        return ReflectionPersistence.STABLE;
    }
}
