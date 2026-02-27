import { useState, useEffect, useRef, useCallback } from 'react';
import { motion, AnimatePresence } from 'framer-motion';

const REPLAY_BASE = '/api/v1/market-data/replay';
const PERFORMANCE_URL = '/api/v1/history/agent-performance';

const DEFAULT_SYMBOL   = 'NIFTY.NSE';
const DEFAULT_FROM     = '2025-08-01';
const DEFAULT_TO       = new Date().toISOString().slice(0, 10);
const POLL_INTERVAL_MS = 2000;

/**
 * Phase-32: Historical Replay Tuning Panel
 *
 * Provides operator controls to:
 *   1. Fetch historical candles from Angel One (stored in history-service)
 *   2. Run the replay loop through the real Java pipeline
 *   3. Monitor live progress (candles, trades, win rate)
 *   4. Inspect post-replay agent weights
 */
export default function ReplayPanel() {
  const [open, setOpen] = useState(false);

  const [symbol,   setSymbol]   = useState(DEFAULT_SYMBOL);
  const [fromDate, setFromDate] = useState(DEFAULT_FROM);
  const [toDate,   setToDate]   = useState(DEFAULT_TO);

  const [status,       setStatus]       = useState(null);   // replay state from backend
  const [agentWeights, setAgentWeights] = useState(null);   // post-replay weights
  const [fetchLog,     setFetchLog]     = useState('');     // one-line fetch status
  const [busy,         setBusy]         = useState(false);  // button lock

  const pollRef = useRef(null);

  // ── Poll replay status while RUNNING ─────────────────────────────────────
  const pollStatus = useCallback(async () => {
    try {
      const res = await fetch(`${REPLAY_BASE}/status`);
      if (!res.ok) return;
      const data = await res.json();
      setStatus(data);
      if (data.status !== 'RUNNING') {
        clearInterval(pollRef.current);
        pollRef.current = null;
        // Load post-replay agent weights on completion
        if (data.status === 'COMPLETE') loadAgentWeights();
      }
    } catch { /* silently ignore */ }
  }, []);

  useEffect(() => () => clearInterval(pollRef.current), []);

  const startPolling = useCallback(() => {
    clearInterval(pollRef.current);
    pollRef.current = setInterval(pollStatus, POLL_INTERVAL_MS);
  }, [pollStatus]);

  const loadAgentWeights = async () => {
    try {
      const res = await fetch(PERFORMANCE_URL);
      if (res.ok) setAgentWeights(await res.json());
    } catch { /* non-critical */ }
  };

  // ── Actions ───────────────────────────────────────────────────────────────
  const handleFetchHistory = async () => {
    setBusy(true);
    setFetchLog('Fetching candles from Angel One…');
    try {
      const res = await fetch(
        `${REPLAY_BASE}/fetch-history?symbol=${encodeURIComponent(symbol)}&fromDate=${fromDate}&toDate=${toDate}`,
        { method: 'POST' }
      );
      const data = await res.json();
      if (res.ok) {
        setFetchLog(`✓ ${data.candlesIngested} candles ingested for ${data.symbol}`);
        await pollStatus();
      } else {
        setFetchLog(`✗ Error: ${data.error || res.status}`);
      }
    } catch (e) {
      setFetchLog(`✗ Network error: ${e.message}`);
    } finally {
      setBusy(false);
    }
  };

  const handleStart = async () => {
    setBusy(true);
    try {
      const res = await fetch(
        `${REPLAY_BASE}/start?symbol=${encodeURIComponent(symbol)}`,
        { method: 'POST' }
      );
      if (res.ok) {
        const data = await res.json();
        setStatus(data);
        startPolling();
      } else {
        const data = await res.json().catch(() => ({}));
        setFetchLog(`✗ Start error: ${data.error || res.status}`);
      }
    } catch (e) {
      setFetchLog(`✗ Network error: ${e.message}`);
    } finally {
      setBusy(false);
    }
  };

  const handleStop = async () => {
    await fetch(`${REPLAY_BASE}/stop`, { method: 'POST' });
    clearInterval(pollRef.current);
    pollRef.current = null;
    await pollStatus();
  };

  const handleReset = async () => {
    setBusy(true);
    await fetch(`${REPLAY_BASE}/reset?symbol=${encodeURIComponent(symbol)}`, { method: 'POST' });
    setStatus(null);
    setFetchLog('');
    setAgentWeights(null);
    setBusy(false);
  };

  const isRunning  = status?.status === 'RUNNING';
  const isComplete = status?.status === 'COMPLETE';

  return (
    <div className="mt-6 rounded-xl border border-slate-700/50 bg-slate-900/40 overflow-hidden">
      {/* ── Header ── */}
      <button
        onClick={() => setOpen(o => !o)}
        className="w-full flex items-center justify-between px-5 py-4
                   text-slate-300 hover:text-slate-100 transition-colors"
      >
        <div className="flex items-center gap-2.5">
          <span className="text-sm font-semibold tracking-tight">Historical Replay Tuning</span>
          <span className="text-xs px-2 py-0.5 rounded-full bg-violet-500/15 text-violet-400 border border-violet-500/20">
            Phase 32
          </span>
          {isRunning && (
            <span className="flex items-center gap-1 text-xs text-emerald-400">
              <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse" />
              Running
            </span>
          )}
          {isComplete && (
            <span className="text-xs text-sky-400">Complete</span>
          )}
        </div>
        <ChevronIcon open={open} />
      </button>

      <AnimatePresence initial={false}>
        {open && (
          <motion.div
            key="replay-body"
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2 }}
            className="overflow-hidden"
          >
            <div className="px-5 pb-5 space-y-5 border-t border-slate-700/50">

              {/* ── Config row ── */}
              <div className="pt-4 grid grid-cols-1 sm:grid-cols-3 gap-3">
                <LabeledInput label="Symbol" value={symbol}
                  onChange={e => setSymbol(e.target.value)} disabled={isRunning} />
                <LabeledInput label="From date" type="date" value={fromDate}
                  onChange={e => setFromDate(e.target.value)} disabled={isRunning} />
                <LabeledInput label="To date" type="date" value={toDate}
                  onChange={e => setToDate(e.target.value)} disabled={isRunning} />
              </div>

              {/* ── Action buttons ── */}
              <div className="flex flex-wrap gap-2">
                <ActionButton onClick={handleFetchHistory} disabled={busy || isRunning}
                  variant="primary">
                  Fetch History
                </ActionButton>
                <ActionButton onClick={handleStart}
                  disabled={busy || isRunning || !status?.totalCandles}
                  variant="success">
                  Start Replay
                </ActionButton>
                <ActionButton onClick={handleStop} disabled={!isRunning}
                  variant="warning">
                  Stop
                </ActionButton>
                <ActionButton onClick={handleReset} disabled={busy || isRunning}
                  variant="danger">
                  Reset
                </ActionButton>
              </div>

              {/* ── Fetch log ── */}
              {fetchLog && (
                <p className="text-xs font-mono text-slate-400">{fetchLog}</p>
              )}

              {/* ── Progress ── */}
              {status && status.totalCandles > 0 && (
                <div className="space-y-2">
                  <div className="flex items-center justify-between text-xs text-slate-400">
                    <span>
                      Candle {status.currentCandle} / {status.totalCandles}
                      {' '}({status.progressPct}%)
                    </span>
                    <span className={statusColor(status.status)}>{status.status}</span>
                  </div>
                  <div className="h-2 rounded-full bg-slate-700/60 overflow-hidden">
                    <motion.div
                      className="h-full rounded-full bg-violet-500"
                      animate={{ width: `${status.progressPct}%` }}
                      transition={{ ease: 'linear', duration: 0.5 }}
                    />
                  </div>
                  <div className="grid grid-cols-3 gap-3">
                    <Stat label="Trades Signaled" value={status.tradesSignaled} />
                    <Stat label="Trades Resolved" value={status.tradesResolved} />
                    <Stat label="Win Rate"
                      value={status.tradesResolved > 0
                        ? `${(status.winRate * 100).toFixed(1)}%`
                        : '—'} />
                  </div>
                  {status.errorMessage && (
                    <p className="text-xs text-red-400 font-mono">{status.errorMessage}</p>
                  )}
                </div>
              )}

              {/* ── Post-replay agent weights ── */}
              {isComplete && agentWeights && Object.keys(agentWeights).length > 0 && (
                <div className="space-y-2">
                  <p className="text-xs font-semibold text-slate-400 uppercase tracking-wide">
                    Post-Replay Agent Weights
                  </p>
                  <div className="overflow-x-auto">
                    <table className="w-full text-xs">
                      <thead>
                        <tr className="text-slate-500 text-left">
                          <th className="pb-2 pr-4">Agent</th>
                          <th className="pb-2 pr-4">Accuracy</th>
                          <th className="pb-2 pr-4">Latency Wt</th>
                          <th className="pb-2">Decisions</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-slate-700/30">
                        {Object.entries(agentWeights).map(([name, w]) => (
                          <tr key={name} className="text-slate-300">
                            <td className="py-1.5 pr-4 font-mono">{name}</td>
                            <td className="py-1.5 pr-4">
                              <WeightBar value={w.historicalAccuracyScore} />
                            </td>
                            <td className="py-1.5 pr-4">
                              {(w.latencyWeight * 100).toFixed(1)}%
                            </td>
                            <td className="py-1.5">{w.totalDecisions}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              )}

            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

// ── Sub-components ────────────────────────────────────────────────────────────

function LabeledInput({ label, value, onChange, disabled, type = 'text' }) {
  return (
    <div className="flex flex-col gap-1">
      <label className="text-xs text-slate-500">{label}</label>
      <input
        type={type}
        value={value}
        onChange={onChange}
        disabled={disabled}
        className="px-3 py-1.5 rounded-lg bg-slate-800 border border-slate-700
                   text-sm text-slate-200 focus:outline-none focus:border-violet-500
                   disabled:opacity-50 disabled:cursor-not-allowed"
      />
    </div>
  );
}

const VARIANT_CLASSES = {
  primary: 'bg-violet-500/10 border-violet-500/30 text-violet-400 hover:bg-violet-500/20',
  success: 'bg-emerald-500/10 border-emerald-500/30 text-emerald-400 hover:bg-emerald-500/20',
  warning: 'bg-amber-500/10  border-amber-500/30  text-amber-400  hover:bg-amber-500/20',
  danger:  'bg-red-500/10    border-red-500/30    text-red-400    hover:bg-red-500/20',
};

function ActionButton({ children, onClick, disabled, variant = 'primary' }) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className={`px-4 py-2 rounded-lg border text-xs font-medium transition-all duration-200
                  disabled:opacity-40 disabled:cursor-not-allowed active:scale-[0.97]
                  ${VARIANT_CLASSES[variant]}`}
    >
      {children}
    </button>
  );
}

function Stat({ label, value }) {
  return (
    <div className="rounded-lg bg-slate-800/40 border border-slate-700/40 px-3 py-2">
      <p className="text-xs text-slate-500">{label}</p>
      <p className="text-sm font-semibold text-slate-200 mt-0.5">{value}</p>
    </div>
  );
}

function WeightBar({ value }) {
  const pct = Math.round((value ?? 0) * 100);
  return (
    <div className="flex items-center gap-2">
      <div className="h-1.5 w-16 rounded-full bg-slate-700 overflow-hidden">
        <div
          className="h-full rounded-full bg-violet-500"
          style={{ width: `${pct}%` }}
        />
      </div>
      <span className="text-slate-300 tabular-nums">{pct}%</span>
    </div>
  );
}

function ChevronIcon({ open }) {
  return (
    <svg
      className={`w-4 h-4 text-slate-500 transition-transform duration-200 ${open ? 'rotate-180' : ''}`}
      fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}
    >
      <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
    </svg>
  );
}

function statusColor(s) {
  switch (s) {
    case 'RUNNING':  return 'text-emerald-400';
    case 'COMPLETE': return 'text-sky-400';
    case 'ERROR':    return 'text-red-400';
    default:         return 'text-slate-500';
  }
}
