package com.agentplatform.history.service;

import com.agentplatform.history.dto.ReplayCandleDTO;
import com.agentplatform.history.model.ReplayCandle;
import com.agentplatform.history.repository.ReplayCandleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Phase-32: Manages storage and retrieval of historical OHLCV candles
 * used by the Historical Replay Tuning Engine.
 */
@Service
public class ReplayCandleService {

    private static final Logger log = LoggerFactory.getLogger(ReplayCandleService.class);

    private final ReplayCandleRepository repository;

    public ReplayCandleService(ReplayCandleRepository repository) {
        this.repository = repository;
    }

    /**
     * Ingests a batch of candles. Uses INSERT ON CONFLICT DO NOTHING semantics
     * via the UNIQUE(symbol, candle_time) constraint — safe to re-ingest.
     */
    public Mono<Long> ingest(List<ReplayCandleDTO> candles) {
        if (candles == null || candles.isEmpty()) return Mono.just(0L);
        log.info("[ReplayCandles] Ingesting {} candles. symbol={}", candles.size(),
                 candles.get(0).symbol());
        return Flux.fromIterable(candles)
            .map(this::toEntity)
            .flatMap(entity -> repository.save(entity)
                .onErrorResume(e -> {
                    // Duplicate key on (symbol, candle_time) — skip silently
                    if (e.getMessage() != null && e.getMessage().contains("duplicate")) {
                        log.debug("[ReplayCandles] Skipping duplicate candle. symbol={} time={}",
                                  entity.getSymbol(), entity.getCandleTime());
                    } else {
                        log.warn("[ReplayCandles] Failed to save candle. symbol={} time={} err={}",
                                 entity.getSymbol(), entity.getCandleTime(), e.getMessage());
                    }
                    return Mono.empty();
                }))
            .count()
            .doOnSuccess(n -> log.info("[ReplayCandles] Ingested {} new candles. symbol={}",
                                       n, candles.get(0).symbol()));
    }

    /** Returns all candles for a symbol in chronological order. */
    public Flux<ReplayCandleDTO> getCandles(String symbol) {
        return repository.findBySymbolOrderByCandleTimeAsc(symbol)
            .map(this::toDTO);
    }

    /** Returns candle count for a symbol (quick status check). */
    public Mono<Long> getCount(String symbol) {
        return repository.countBySymbol(symbol);
    }

    /** Deletes all candles for a symbol (reset before re-ingestion). */
    public Mono<Void> deleteBySymbol(String symbol) {
        log.info("[ReplayCandles] Deleting all candles for symbol={}", symbol);
        return repository.deleteBySymbol(symbol);
    }

    private ReplayCandle toEntity(ReplayCandleDTO dto) {
        ReplayCandle e = new ReplayCandle();
        e.setSymbol(dto.symbol());
        e.setCandleTime(dto.candleTime());
        e.setOpen(dto.open());
        e.setHigh(dto.high());
        e.setLow(dto.low());
        e.setClose(dto.close());
        e.setVolume(dto.volume());
        return e;
    }

    private ReplayCandleDTO toDTO(ReplayCandle e) {
        return new ReplayCandleDTO(
            e.getSymbol(), e.getCandleTime(),
            e.getOpen(), e.getHigh(), e.getLow(), e.getClose(), e.getVolume()
        );
    }
}
