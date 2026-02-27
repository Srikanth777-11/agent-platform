package com.agentplatform.marketdata.replay;

import com.agentplatform.marketdata.model.MarketDataQuote;
import com.agentplatform.marketdata.provider.MarketDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Phase-32: Historical OHLCV market-data provider for the replay tuning engine.
 *
 * <p>Active only when Spring profile {@code historical-replay} is set.
 * {@code @Primary} ensures it takes precedence over live {@link com.agentplatform.marketdata.service.MarketDataService}.
 *
 * <p>Cursor is managed externally by {@link ReplayRunnerService} — call
 * {@link #setCursor(String, int)} before each orchestrator trigger to advance
 * to the correct candle. {@link #getQuote(String)} reads the current cursor
 * and returns real OHLCV data plus a true historical sliding window.
 */
@Service
@Primary
@Profile("historical-replay")
public class HistoricalReplayProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(HistoricalReplayProvider.class);

    private final ConcurrentHashMap<String, List<ReplayCandleDTO>> candleMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger>         cursors   = new ConcurrentHashMap<>();

    /**
     * Loads candles into memory and resets the cursor to zero.
     * Called by {@link ReplayRunnerService} once after fetching from history-service.
     */
    public void loadCandles(String symbol, List<ReplayCandleDTO> candles) {
        candleMap.put(symbol, Collections.unmodifiableList(candles));
        cursors.put(symbol, new AtomicInteger(0));
        log.info("[HistoricalReplay] Candles loaded. symbol={} count={}", symbol, candles.size());
    }

    /**
     * Advances the cursor to position {@code idx}.
     * Called by {@link ReplayRunnerService} before each orchestrator trigger.
     */
    public void setCursor(String symbol, int idx) {
        cursors.computeIfAbsent(symbol, k -> new AtomicInteger(0)).set(idx);
    }

    /** Returns true if candles have been loaded for the symbol. */
    public boolean hasCandles(String symbol) {
        List<ReplayCandleDTO> c = candleMap.get(symbol);
        return c != null && !c.isEmpty();
    }

    /** Returns total candle count for a symbol, or 0 if not loaded. */
    public int candleCount(String symbol) {
        List<ReplayCandleDTO> c = candleMap.get(symbol);
        return c == null ? 0 : c.size();
    }

    // ── MarketDataProvider ─────────────────────────────────────────────────

    @Override
    public Mono<MarketDataQuote> getQuote(String symbol) {
        List<ReplayCandleDTO> candles = candleMap.get(symbol);
        if (candles == null || candles.isEmpty()) {
            log.warn("[HistoricalReplay] No candles loaded for symbol={}. Returning error.", symbol);
            return Mono.error(new IllegalStateException(
                "No replay candles loaded for symbol: " + symbol));
        }

        int idx = cursors.computeIfAbsent(symbol, k -> new AtomicInteger(0)).get();
        idx = Math.max(0, Math.min(idx, candles.size() - 1));

        ReplayCandleDTO candle = candles.get(idx);

        // Sliding closing-price window: current candle back up to 50 positions, newest first.
        // Real historical prices — no synthetic values.
        final int finalIdx = idx;
        List<Double> window = IntStream.range(0, Math.min(50, finalIdx + 1))
            .mapToObj(i -> candles.get(finalIdx - i).close())
            .collect(Collectors.toList());

        log.debug("[HistoricalReplay] getQuote. symbol={} idx={}/{} time={} close={}",
            symbol, idx, candles.size(), candle.candleTime(), candle.close());

        return Mono.just(new MarketDataQuote(
            symbol,
            candle.close(),
            candle.open(),
            candle.high(),
            candle.low(),
            candle.volume(),
            window,
            candle.candleTime().toInstant(ZoneOffset.UTC)
        ));
    }
}
