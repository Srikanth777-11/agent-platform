import { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';

const TRADE_HISTORY_URL = '/api/v1/trade/history';

export default function TradeReflectionPanel() {
  const [trades, setTrades] = useState([]);
  const [expanded, setExpanded] = useState(false);
  const [loading, setLoading] = useState(false);

  const fetchHistory = async () => {
    setLoading(true);
    try {
      const res = await fetch(TRADE_HISTORY_URL);
      if (res.ok) {
        const data = await res.json();
        setTrades(data);
      }
    } catch (err) {
      console.error('Trade history fetch failed:', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchHistory();
  }, []);

  if (trades.length === 0 && !loading) return null;

  return (
    <div className="mt-6">
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex items-center gap-2 text-[11px] font-medium text-slate-500 uppercase tracking-wider
                   hover:text-slate-400 transition-colors"
      >
        <svg
          className={`w-3 h-3 transition-transform duration-200 ${expanded ? 'rotate-90' : ''}`}
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
          strokeWidth={2}
        >
          <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
        </svg>
        Trade History ({trades.length})
      </button>

      <AnimatePresence>
        {expanded && (
          <motion.div
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: 'auto' }}
            exit={{ opacity: 0, height: 0 }}
            transition={{ duration: 0.2 }}
            className="overflow-hidden"
          >
            <div className="mt-3 rounded-xl border border-slate-800/60 overflow-hidden">
              {/* Header row */}
              <div className="grid grid-cols-6 gap-2 px-4 py-2 bg-slate-800/30 border-b border-slate-800/40">
                {['Symbol', 'Entry', 'Exit', 'PnL', 'Duration', 'Time'].map((h) => (
                  <span key={h} className="text-[10px] font-medium text-slate-600 uppercase tracking-wider">
                    {h}
                  </span>
                ))}
              </div>

              {/* Trade rows */}
              {trades.map((trade, i) => (
                <div
                  key={trade.id || i}
                  className="grid grid-cols-6 gap-2 px-4 py-2.5 border-b border-slate-800/20
                             hover:bg-slate-800/20 transition-colors"
                >
                  <span className="text-xs font-mono font-semibold text-slate-300">
                    {trade.symbol}
                  </span>
                  <span className="text-xs font-mono text-slate-400">
                    {trade.entryPrice?.toFixed(2) ?? '--'}
                  </span>
                  <span className="text-xs font-mono text-slate-400">
                    {trade.exitPrice?.toFixed(2) ?? '--'}
                  </span>
                  <span className={`text-xs font-mono font-medium ${
                    trade.pnl > 0 ? 'text-emerald-400/80' : trade.pnl < 0 ? 'text-rose-400/80' : 'text-slate-500'
                  }`}>
                    {trade.pnl != null ? (trade.pnl > 0 ? '+' : '') + trade.pnl.toFixed(2) : '--'}
                  </span>
                  <span className="text-xs font-mono text-slate-500">
                    {formatDuration(trade.durationMs)}
                  </span>
                  <span className="text-[10px] font-mono text-slate-600">
                    {formatTime(trade.exitTime)}
                  </span>
                </div>
              ))}

              {trades.length === 0 && (
                <div className="px-4 py-6 text-center">
                  <p className="text-[11px] text-slate-600 italic">No closed trades yet</p>
                </div>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

function formatDuration(ms) {
  if (ms == null) return '--';
  const seconds = Math.floor(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  return `${hours}h ${minutes % 60}m`;
}

function formatTime(dateStr) {
  if (!dateStr) return '--';
  try {
    const date = new Date(dateStr);
    if (isNaN(date.getTime())) return dateStr;
    return date.toLocaleString([], {
      month: 'short', day: 'numeric',
      hour: '2-digit', minute: '2-digit',
    });
  } catch {
    return dateStr;
  }
}
