import { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';

export default function FeedbackLoopPanel({ agents }) {
  const [expanded, setExpanded] = useState(false);

  if (!agents || agents.length === 0) return null;

  const truthCount = agents.filter((a) => a.source === 'market-truth').length;

  return (
    <div className="mt-4">
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
        Feedback Loop
        <span className={`ml-1 text-[10px] font-mono px-1.5 py-0.5 rounded ${
          truthCount === agents.length
            ? 'bg-emerald-500/10 text-emerald-400'
            : truthCount > 0
            ? 'bg-amber-500/10 text-amber-400'
            : 'bg-slate-700/50 text-slate-500'
        }`}>
          {truthCount}/{agents.length} live
        </span>
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
              {/* Header */}
              <div className="grid grid-cols-4 gap-2 px-4 py-2 bg-slate-800/30 border-b border-slate-800/40">
                {['Agent', 'Win Rate', 'Samples', 'Source'].map((h) => (
                  <span key={h} className="text-[10px] font-medium text-slate-600 uppercase tracking-wider">
                    {h}
                  </span>
                ))}
              </div>

              {/* Agent rows */}
              {agents.map((agent) => (
                <div
                  key={agent.agentName}
                  className="grid grid-cols-4 gap-2 px-4 py-2.5 border-b border-slate-800/20
                             hover:bg-slate-800/20 transition-colors last:border-0"
                >
                  <span className="text-xs font-mono text-slate-300 truncate">
                    {formatAgentName(agent.agentName)}
                  </span>
                  <span className={`text-xs font-mono font-medium ${winRateColor(agent.winRate, agent.source)}`}>
                    {agent.source === 'fallback'
                      ? <span className="text-slate-600 italic">—</span>
                      : `${(agent.winRate * 100).toFixed(1)}%`}
                  </span>
                  <span className="text-xs font-mono text-slate-500">
                    {agent.sampleSize > 0 ? agent.sampleSize : '< 5'}
                  </span>
                  <span className={`text-[10px] font-medium px-1.5 py-0.5 rounded self-center w-fit ${
                    agent.source === 'market-truth'
                      ? 'bg-emerald-500/10 text-emerald-400'
                      : 'bg-slate-700/40 text-slate-600'
                  }`}>
                    {agent.source === 'market-truth' ? 'live' : 'warmup'}
                  </span>
                </div>
              ))}
            </div>

            <p className="mt-2 text-[10px] text-slate-700 px-1">
              Win rate based on resolved P&amp;L outcomes. Agents need ≥5 resolved trades to switch from warmup to live.
            </p>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

function formatAgentName(name) {
  if (!name) return '—';
  // "TrendAgent" → "Trend", strip "Agent" suffix for compact display
  return name.replace(/Agent$/, '');
}

function winRateColor(rate, source) {
  if (source === 'fallback') return 'text-slate-600';
  if (rate >= 0.6) return 'text-emerald-400';
  if (rate >= 0.45) return 'text-amber-400';
  return 'text-rose-400';
}
