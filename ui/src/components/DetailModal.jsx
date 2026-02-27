import { useEffect, useState } from 'react';
import { motion } from 'framer-motion';

const SIGNAL_COLOR = {
  BUY:   'text-emerald-400',
  SELL:  'text-rose-400',
  HOLD:  'text-zinc-300',
  WATCH: 'text-sky-400',
};

export default function DetailModal({ data, onClose, marketStates, activeTrade, onTradeStart }) {
  const {
    symbol,
    finalSignal,
    confidence,
    marketRegime,
    divergenceFlag,
    aiReasoning,
    savedAt,
  } = data;

  const confidencePct = Math.round((confidence ?? 0) * 100);
  const signalColor = SIGNAL_COLOR[finalSignal] || SIGNAL_COLOR.HOLD;

  // Close on Escape
  useEffect(() => {
    const handler = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [onClose]);

  return (
    <motion.div
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{ duration: 0.2 }}
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
      onClick={onClose}
    >
      {/* Backdrop */}
      <div className="absolute inset-0 bg-slate-950/70 backdrop-blur-sm" />

      {/* Panel */}
      <motion.div
        initial={{ opacity: 0, scale: 0.95, y: 10 }}
        animate={{ opacity: 1, scale: 1, y: 0 }}
        exit={{ opacity: 0, scale: 0.95, y: 10 }}
        transition={{ type: 'spring', stiffness: 400, damping: 30 }}
        onClick={(e) => e.stopPropagation()}
        className="relative w-full max-w-lg bg-slate-900/95 border border-slate-700/60
                   rounded-2xl shadow-2xl shadow-black/40 overflow-hidden"
      >
        {/* ── Header ─────────────────────────────────────── */}
        <div className="px-6 pt-6 pb-4 border-b border-slate-800/60">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <h2 className="text-xl font-bold font-mono tracking-wide text-slate-100">
                {symbol}
              </h2>
              {divergenceFlag && (
                <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md bg-amber-400/10 border border-amber-400/20 text-amber-400 text-[10px] font-semibold uppercase tracking-wider">
                  <svg className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M8.485 2.495c.673-1.167 2.357-1.167 3.03 0l6.28 10.875c.673 1.167-.17 2.625-1.516 2.625H3.72c-1.347 0-2.189-1.458-1.515-2.625L8.485 2.495zM10 6a.75.75 0 01.75.75v3.5a.75.75 0 01-1.5 0v-3.5A.75.75 0 0110 6zm0 9a1 1 0 100-2 1 1 0 000 2z" clipRule="evenodd" />
                  </svg>
                  Divergence
                </span>
              )}
            </div>
            <button
              onClick={onClose}
              className="text-slate-500 hover:text-slate-300 transition-colors p-1 rounded-lg hover:bg-slate-800"
            >
              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
        </div>

        {/* ── Body ────────────────────────────────────────── */}
        <div className="px-6 py-5 space-y-5">

          {/* ── AI Decision Summary ──────────────────────── */}
          <ModalSection title="AI Decision Summary">
            <div className="flex items-center gap-6">
              <div>
                <p className="text-[10px] font-medium text-slate-500 uppercase tracking-wider mb-1">Signal</p>
                <p className={`text-2xl font-bold ${signalColor}`}>{finalSignal}</p>
              </div>
              <div>
                <p className="text-[10px] font-medium text-slate-500 uppercase tracking-wider mb-1">Confidence</p>
                <p className="text-2xl font-bold font-mono text-slate-200">{confidencePct}%</p>
              </div>
              <div>
                <p className="text-[10px] font-medium text-slate-500 uppercase tracking-wider mb-1">Regime</p>
                <p className="text-sm font-semibold text-slate-300 uppercase">{marketRegime || 'UNKNOWN'}</p>
              </div>
            </div>
          </ModalSection>

          {/* ── AI Reasoning Block ───────────────────────── */}
          <ModalSection title="AI Reasoning">
            <div className="p-4 rounded-xl bg-slate-800/50 border border-slate-700/40">
              <p className="text-sm text-slate-300 leading-relaxed">
                {aiReasoning || 'No reasoning available for this decision.'}
              </p>
            </div>
          </ModalSection>

          {/* ── Consensus Insight ────────────────────────── */}
          <ModalSection title="Consensus Insight">
            <div className={`p-4 rounded-xl border ${divergenceFlag ? 'bg-amber-400/5 border-amber-400/15' : 'bg-slate-800/50 border-slate-700/40'}`}>
              <div className="flex items-center gap-2 mb-1.5">
                <span className={`w-1.5 h-1.5 rounded-full ${divergenceFlag ? 'bg-amber-400' : 'bg-emerald-400'}`} />
                <p className={`text-xs font-semibold ${divergenceFlag ? 'text-amber-300' : 'text-emerald-300'}`}>
                  {divergenceFlag ? 'Consensus disagreed with AI' : 'Consensus agreed with AI'}
                </p>
              </div>
              <p className="text-[11px] text-slate-500 leading-relaxed">
                {divergenceFlag
                  ? 'The AI strategist overrode the consensus guardrail for this decision. The weighted agent majority reached a different signal than the AI recommendation.'
                  : 'The AI strategist and the consensus guardrail independently reached the same signal, reinforcing decision confidence.'}
              </p>
              <p className={`text-[10px] mt-2 italic ${divergenceFlag ? 'text-amber-400/60' : 'text-slate-600'}`}>
                {divergenceFlag
                  ? 'AI chose a different path than consensus safety model.'
                  : 'AI and consensus signals were aligned.'}
              </p>
            </div>
          </ModalSection>

          {/* ── Agent Signals placeholder ────────────────── */}
          <ModalSection title="Agent Signals">
            <div className="p-3 rounded-xl bg-slate-800/30 border border-dashed border-slate-700/40">
              <p className="text-[11px] text-slate-600 italic">Coming soon</p>
            </div>
          </ModalSection>

          {/* ── Start Trade Panel ─────────────────────────── */}
          {onTradeStart && (
            <TradeEntryPanel
              data={data}
              marketStates={marketStates}
              activeTrade={activeTrade}
              onTradeStart={onTradeStart}
            />
          )}
        </div>

        {/* ── Footer ─────────────────────────────────────── */}
        <div className="px-6 py-3 border-t border-slate-800/60 flex items-center justify-between">
          <span className="text-[11px] text-slate-600 font-mono">
            {savedAt ? formatTimeFull(savedAt) : '--'}
          </span>
          <span className="text-[10px] text-slate-600 uppercase tracking-wider">
            Snapshot v7
          </span>
        </div>
      </motion.div>
    </motion.div>
  );
}

/* ── Sub-components ──────────────────────────────────────────── */

function ModalSection({ title, children }) {
  return (
    <div>
      <p className="text-[11px] font-medium text-slate-500 uppercase tracking-wider mb-2">
        {title}
      </p>
      <div className="h-px bg-slate-800/60 mb-3" />
      {children}
    </div>
  );
}

function TradeEntryPanel({ data, marketStates, activeTrade, onTradeStart }) {
  const [entryPrice, setEntryPrice] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // Don't show if active trade already exists for this symbol
  if (activeTrade?.trade?.symbol === data.symbol) return null;

  const momentumState = marketStates?.find(s => s.symbol === data.symbol);
  const entryMomentum = momentumState?.marketState || 'CALM';

  const handleSubmit = async () => {
    const price = parseFloat(entryPrice);
    if (!entryPrice || isNaN(price)) return;
    setSubmitting(true);
    await onTradeStart({
      symbol: data.symbol,
      entryPrice: price,
      entryConfidence: data.confidence ?? 0.5,
      entryRegime: data.marketRegime || 'UNKNOWN',
      entryMomentum,
    });
    setSubmitting(false);
  };

  return (
    <ModalSection title="Start Trade">
      <div className="p-4 rounded-xl bg-slate-800/30 border border-slate-700/40 space-y-3">
        <div className="flex items-center gap-3">
          <label className="text-[11px] text-slate-500 uppercase tracking-wider w-24">
            Entry Price
          </label>
          <input
            type="number"
            step="0.01"
            value={entryPrice}
            onChange={(e) => setEntryPrice(e.target.value)}
            placeholder="0.00"
            className="flex-1 bg-slate-900 border border-slate-700 rounded-lg px-3 py-1.5
                       text-sm text-slate-200 font-mono placeholder:text-slate-600
                       focus:outline-none focus:border-slate-500 transition-colors"
          />
        </div>
        <div className="flex items-center justify-between text-[10px] text-slate-600">
          <span>Confidence: {Math.round((data.confidence ?? 0) * 100)}%</span>
          <span>Regime: {data.marketRegime || 'UNKNOWN'}</span>
          <span>Momentum: {entryMomentum}</span>
        </div>
        <button
          onClick={handleSubmit}
          disabled={submitting || !entryPrice}
          className="w-full py-2 rounded-lg bg-emerald-500/10 border border-emerald-500/20
                     text-emerald-400 text-xs font-medium uppercase tracking-wider
                     hover:bg-emerald-500/20 transition-all disabled:opacity-40"
        >
          {submitting ? 'Starting...' : 'Start Trade'}
        </button>
      </div>
    </ModalSection>
  );
}

function formatTimeFull(savedAt) {
  try {
    const date = new Date(savedAt);
    if (isNaN(date.getTime())) return savedAt;
    return date.toLocaleString([], {
      month: 'short', day: 'numeric',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    });
  } catch {
    return savedAt;
  }
}
