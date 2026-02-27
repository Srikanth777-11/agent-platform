import { useState, useCallback, useEffect, useRef } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import SnapshotCard from './components/SnapshotCard';
import DetailModal from './components/DetailModal';
import MomentumBanner from './components/MomentumBanner';
import TacticalPostureHeader from './components/TacticalPostureHeader';
import TradeReflectionPanel from './components/TradeReflectionPanel';
import FeedbackLoopPanel from './components/FeedbackLoopPanel';
import ReplayPanel from './components/ReplayPanel';

// ── Trading session classifier (mirrors Java TradingSessionClassifier) ───────
function getTradingSession() {
  const now = new Date();
  const ist = new Date(now.toLocaleString('en-US', { timeZone: 'Asia/Kolkata' }));
  const h = ist.getHours(), m = ist.getMinutes();
  const mins = h * 60 + m;
  const day  = ist.getDay(); // 0=Sun,6=Sat
  if (day === 0 || day === 6) return 'OFF_HOURS';
  if (mins >= 555 && mins < 600)  return 'OPENING_BURST';   // 9:15–10:00
  if (mins >= 600 && mins < 900)  return 'MIDDAY_CONSOLIDATION'; // 10:00–15:00
  if (mins >= 900 && mins < 930)  return 'POWER_HOUR';       // 15:00–15:30
  return 'OFF_HOURS';
}

const SESSION_CONFIG = {
  OPENING_BURST:        { label: 'Opening Burst',  color: 'text-emerald-400', dot: 'bg-emerald-400' },
  POWER_HOUR:           { label: 'Power Hour',     color: 'text-amber-400',   dot: 'bg-amber-400'   },
  MIDDAY_CONSOLIDATION: { label: 'Midday',         color: 'text-sky-400',     dot: 'bg-sky-400'     },
  OFF_HOURS:            { label: 'Market Closed',  color: 'text-slate-500',   dot: 'bg-slate-600'   },
};

const AUTO_REFRESH_SECONDS = 30;

const SNAPSHOT_URL = '/api/v1/history/snapshot';
const MARKET_STATE_URL = '/api/v1/history/market-state';
const ACTIVE_TRADE_URL = '/api/v1/trade/active';
const FEEDBACK_LOOP_URL = '/api/v1/history/feedback-loop-status';

export default function App() {
  const [snapshotData, setSnapshotData] = useState([]);
  const [marketStates, setMarketStates] = useState([]);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [selectedCard, setSelectedCard] = useState(null);
  const [lastUpdated, setLastUpdated] = useState(null);
  const [activeTrade, setActiveTrade] = useState(null);
  const [istTime, setIstTime] = useState('');
  const [session, setSession] = useState('OFF_HOURS');
  const [countdown, setCountdown] = useState(AUTO_REFRESH_SECONDS);
  const [feedbackAgents, setFeedbackAgents] = useState([]);

  const fetchSnapshot = useCallback(async () => {
    setIsRefreshing(true);
    try {
      const [snapshotRes, stateRes, feedbackRes] = await Promise.all([
        fetch(SNAPSHOT_URL),
        fetch(MARKET_STATE_URL).catch(() => null),
        fetch(FEEDBACK_LOOP_URL).catch(() => null),
      ]);

      if (!snapshotRes.ok) throw new Error(`HTTP ${snapshotRes.status}`);
      const data = await snapshotRes.json();
      setSnapshotData(data);

      // Market state is non-critical — silently degrade if unavailable
      if (stateRes && stateRes.ok) {
        const states = await stateRes.json();
        setMarketStates(states);

        // Fetch active trade for the first symbol with market state
        const firstSymbol = states.length > 0 ? states[0].symbol : null;
        if (firstSymbol) {
          try {
            const tradeRes = await fetch(`${ACTIVE_TRADE_URL}/${firstSymbol}`);
            if (tradeRes.ok) {
              setActiveTrade(await tradeRes.json());
            } else {
              setActiveTrade(null);
            }
          } catch {
            setActiveTrade(null);
          }
        }
      }

      if (feedbackRes && feedbackRes.ok) {
        setFeedbackAgents(await feedbackRes.json());
      }

      setLastUpdated(new Date());
    } catch (err) {
      console.error('Snapshot fetch failed:', err);
    } finally {
      setIsRefreshing(false);
    }
  }, []);

  const handleTradeExit = useCallback(async (symbol, exitPrice) => {
    try {
      const res = await fetch('/api/v1/trade/exit', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ symbol, exitPrice }),
      });
      if (res.ok) {
        setActiveTrade(null);
        fetchSnapshot();
      }
    } catch (err) {
      console.error('Trade exit failed:', err);
    }
  }, [fetchSnapshot]);

  const handleTradeStart = useCallback(async (request) => {
    try {
      const res = await fetch('/api/v1/trade/start', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
      });
      if (res.ok) {
        const tradeRes = await fetch(`${ACTIVE_TRADE_URL}/${request.symbol}`);
        if (tradeRes.ok) setActiveTrade(await tradeRes.json());
      }
    } catch (err) {
      console.error('Trade start failed:', err);
    }
  }, []);

  // ── SSE: real-time snapshot updates ─────────────────────────
  useEffect(() => {
    const es = new EventSource('/api/v1/history/stream');

    es.addEventListener('snapshot', (e) => {
      try {
        const incoming = JSON.parse(e.data);
        setSnapshotData((prev) => {
          const idx = prev.findIndex((d) => d.symbol === incoming.symbol);
          if (idx >= 0) {
            const next = [...prev];
            next[idx] = incoming;
            return next;
          }
          return [incoming, ...prev];
        });
        setLastUpdated(new Date());
      } catch (err) {
        console.error('SSE parse error:', err);
      }
    });

    es.onerror = () => {
      // EventSource auto-reconnects — just log
      console.warn('SSE connection lost, reconnecting...');
    };

    return () => es.close();
  }, []);

  // ── Live IST clock + session + auto-refresh countdown ───────────────────
  useEffect(() => {
    const tick = () => {
      const now = new Date();
      const ist = new Date(now.toLocaleString('en-US', { timeZone: 'Asia/Kolkata' }));
      setIstTime(ist.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }));
      setSession(getTradingSession());
    };
    tick();
    const clockInterval = setInterval(tick, 1000);
    return () => clearInterval(clockInterval);
  }, []);

  useEffect(() => {
    setCountdown(AUTO_REFRESH_SECONDS);
    const interval = setInterval(() => {
      setCountdown(prev => {
        if (prev <= 1) {
          fetchSnapshot();
          return AUTO_REFRESH_SECONDS;
        }
        return prev - 1;
      });
    }, 1000);
    return () => clearInterval(interval);
  }, [fetchSnapshot]);

  return (
    <div className={`min-h-screen flex flex-col ${activeTrade ? 'dashboard--focus-mode' : ''}`}>
      {/* ── Top Bar ──────────────────────────────────────────── */}
      <header className="sticky top-0 z-30 backdrop-blur-xl bg-slate-950/80 border-b border-slate-800/60">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          {/* Left — Title */}
          <div className="flex items-center gap-3">
            <div className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
            <h1 className="text-lg font-semibold tracking-tight text-slate-100">
              AI Intelligence Snapshot
            </h1>
          </div>

          {/* Right — Session + Clock + Auto-refresh */}
          <div className="flex items-center gap-3">
            {/* Session indicator */}
            {(() => {
              const cfg = SESSION_CONFIG[session] || SESSION_CONFIG.OFF_HOURS;
              return (
                <div className="hidden sm:flex items-center gap-1.5">
                  <span className={`w-1.5 h-1.5 rounded-full ${cfg.dot} ${session !== 'OFF_HOURS' ? 'animate-pulse' : ''}`} />
                  <span className={`text-xs font-medium ${cfg.color}`}>{cfg.label}</span>
                </div>
              );
            })()}

            {/* IST Clock */}
            <span className="hidden sm:block text-xs text-slate-500 font-mono tabular-nums">
              {istTime} IST
            </span>

            {/* Auto-refresh button with countdown */}
            <button
              onClick={() => { fetchSnapshot(); setCountdown(AUTO_REFRESH_SECONDS); }}
              disabled={isRefreshing}
              className="group inline-flex items-center gap-2 px-3 py-2 rounded-lg
                         bg-slate-800 hover:bg-slate-700 border border-slate-700 hover:border-slate-600
                         text-xs font-medium text-slate-400 transition-all duration-200
                         disabled:opacity-50 disabled:cursor-not-allowed active:scale-[0.97]"
            >
              <RefreshIcon spinning={isRefreshing} />
              {isRefreshing ? 'Updating...' : `${countdown}s`}
            </button>
          </div>
        </div>
      </header>

      {/* ── Tactical Posture Header (active trade awareness) ── */}
      <TacticalPostureHeader activeTrade={activeTrade} onTradeExit={handleTradeExit} />

      {/* ── Momentum State Banner (calm, top-center) ──────── */}
      <MomentumBanner marketStates={marketStates} />

      {/* ── Main Content ─────────────────────────────────────── */}
      <main className="flex-1 max-w-7xl mx-auto w-full px-4 sm:px-6 lg:px-8 py-8 focus-secondary">
        {snapshotData.length === 0 ? (
          <EmptyState onRefresh={fetchSnapshot} isRefreshing={isRefreshing} />
        ) : (
          <motion.div
            layout
            className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-5"
          >
            <AnimatePresence mode="popLayout">
              {snapshotData.map((item) => (
                <SnapshotCard
                  key={item.symbol}
                  data={item}
                  onClick={() => setSelectedCard(item)}
                />
              ))}
            </AnimatePresence>
          </motion.div>
        )}

        {/* ── Trade History (collapsible) ───────────────────── */}
        <TradeReflectionPanel />

        {/* ── Feedback Loop Status (collapsible) ──────────── */}
        <FeedbackLoopPanel agents={feedbackAgents} />

        {/* ── Phase-32: Historical Replay Tuning (collapsible) ── */}
        <ReplayPanel />
      </main>

      {/* ── Footer ───────────────────────────────────────────── */}
      <footer className="border-t border-slate-800/40 py-4">
        <p className="text-center text-xs text-slate-600">
          Hybrid Trading Intelligence Platform
        </p>
      </footer>

      {/* ── Detail Modal ─────────────────────────────────────── */}
      <AnimatePresence>
        {selectedCard && (
          <DetailModal
            data={selectedCard}
            onClose={() => setSelectedCard(null)}
            marketStates={marketStates}
            activeTrade={activeTrade}
            onTradeStart={handleTradeStart}
          />
        )}
      </AnimatePresence>
    </div>
  );
}

/* ── Inline sub-components ───────────────────────────────────── */

function RefreshIcon({ spinning }) {
  return (
    <svg
      className={`w-4 h-4 transition-transform ${spinning ? 'animate-spin' : 'group-hover:rotate-45'}`}
      fill="none"
      viewBox="0 0 24 24"
      stroke="currentColor"
      strokeWidth={2}
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
      />
    </svg>
  );
}

function EmptyState({ onRefresh, isRefreshing }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      className="flex flex-col items-center justify-center py-32 text-center"
    >
      <div className="w-16 h-16 rounded-2xl bg-slate-800/50 border border-slate-700/50 flex items-center justify-center mb-6">
        <svg className="w-8 h-8 text-slate-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
          <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 3v11.25A2.25 2.25 0 006 16.5h2.25M3.75 3h-1.5m1.5 0h16.5m0 0h1.5m-1.5 0v11.25A2.25 2.25 0 0118 16.5h-2.25m-7.5 0h7.5m-7.5 0l-1 3m8.5-3l1 3m0 0l.5 1.5m-.5-1.5h-9.5m0 0l-.5 1.5" />
        </svg>
      </div>
      <h2 className="text-xl font-semibold text-slate-300 mb-2">No snapshot loaded</h2>
      <p className="text-sm text-slate-500 mb-8 max-w-sm">
        Press the button below to fetch the latest AI-generated trading intelligence for all watched symbols.
      </p>
      <button
        onClick={onRefresh}
        disabled={isRefreshing}
        className="px-6 py-2.5 rounded-xl bg-emerald-500/10 border border-emerald-500/20
                   text-emerald-400 text-sm font-medium hover:bg-emerald-500/20
                   transition-all duration-200 disabled:opacity-50"
      >
        {isRefreshing ? 'Loading...' : 'Load Snapshot'}
      </button>
    </motion.div>
  );
}
