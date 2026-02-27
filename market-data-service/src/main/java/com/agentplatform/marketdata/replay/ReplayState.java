package com.agentplatform.marketdata.replay;

import java.time.Instant;

/**
 * Phase-32: Mutable replay progress state — shared between
 * {@link ReplayRunnerService} (writer) and {@link ReplayController} (reader).
 *
 * <p>All writes are performed from a single background thread so no
 * synchronisation is required beyond volatile visibility.
 */
public class ReplayState {

    public enum Status { IDLE, RUNNING, COMPLETE, ERROR }

    private volatile Status  status       = Status.IDLE;
    private volatile String  symbol;
    private volatile int     currentIdx;
    private volatile int     totalCandles;
    private volatile int     tradesSignaled;
    private volatile int     tradesResolved;
    private volatile int     wins;
    private volatile String  errorMessage;
    private volatile Instant startedAt;

    // ── mutators (called from background replay thread) ────────────────────

    public void start(String symbol, int totalCandles) {
        this.symbol        = symbol;
        this.totalCandles  = totalCandles;
        this.currentIdx    = 0;
        this.tradesSignaled = 0;
        this.tradesResolved = 0;
        this.wins          = 0;
        this.errorMessage  = null;
        this.startedAt     = Instant.now();
        this.status        = Status.RUNNING;
    }

    public void complete() { this.status = Status.COMPLETE; }

    public void error(String msg) { this.errorMessage = msg; this.status = Status.ERROR; }

    public void reset() {
        this.status        = Status.IDLE;
        this.symbol        = null;
        this.currentIdx    = 0;
        this.totalCandles  = 0;
        this.tradesSignaled = 0;
        this.tradesResolved = 0;
        this.wins          = 0;
        this.errorMessage  = null;
        this.startedAt     = null;
    }

    public void advanceCandle(int idx)     { this.currentIdx     = idx; }
    public void incrementSignaled()        { this.tradesSignaled++; }
    public void incrementResolved(boolean win) {
        this.tradesResolved++;
        if (win) this.wins++;
    }

    // ── accessors ──────────────────────────────────────────────────────────

    public Status  getStatus()         { return status; }
    public String  getSymbol()         { return symbol; }
    public int     getCurrentIdx()     { return currentIdx; }
    public int     getTotalCandles()   { return totalCandles; }
    public int     getTradesSignaled() { return tradesSignaled; }
    public int     getTradesResolved() { return tradesResolved; }
    public String  getErrorMessage()   { return errorMessage; }
    public Instant getStartedAt()      { return startedAt; }

    public double getWinRate() {
        return tradesResolved > 0 ? (double) wins / tradesResolved : 0.0;
    }

    public double getProgressPct() {
        return totalCandles > 0 ? (double) currentIdx / totalCandles * 100.0 : 0.0;
    }
}
