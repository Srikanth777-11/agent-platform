import { motion } from 'framer-motion';

const SIGNAL_STYLES = {
  BUY:   { color: 'text-emerald-400', bg: 'bg-emerald-400/10', border: 'border-emerald-400/20' },
  SELL:  { color: 'text-rose-400',    bg: 'bg-rose-400/10',    border: 'border-rose-400/20'    },
  HOLD:  { color: 'text-zinc-300',    bg: 'bg-zinc-400/10',    border: 'border-zinc-400/20'    },
  WATCH: { color: 'text-sky-400',     bg: 'bg-sky-400/10',     border: 'border-sky-400/20'     },
};

const REGIME_STYLES = {
  VOLATILE: { color: 'text-amber-300',   bg: 'bg-amber-400/10',   dot: 'bg-amber-400' },
  TRENDING: { color: 'text-emerald-300', bg: 'bg-emerald-400/10', dot: 'bg-emerald-400' },
  RANGING:  { color: 'text-sky-300',     bg: 'bg-sky-400/10',     dot: 'bg-sky-400'    },
  CALM:     { color: 'text-slate-400',   bg: 'bg-slate-400/10',   dot: 'bg-slate-400'  },
  UNKNOWN:  { color: 'text-slate-500',   bg: 'bg-slate-500/10',   dot: 'bg-slate-500'  },
};

const REGIME_STRIP = {
  VOLATILE: { stripBg: 'bg-amber-400/10',   stripBorder: 'border-amber-400/15',  stripText: 'text-amber-300/90',   label: 'VOLATILE \u2014 Fast reevaluation active' },
  TRENDING: { stripBg: 'bg-emerald-400/10',  stripBorder: 'border-emerald-400/15', stripText: 'text-emerald-300/90', label: 'TRENDING \u2014 Directional bias active' },
  RANGING:  { stripBg: 'bg-sky-400/10',      stripBorder: 'border-sky-400/15',     stripText: 'text-sky-300/90',     label: 'RANGING \u2014 Mean-reversion environment' },
  CALM:     { stripBg: 'bg-slate-400/8',     stripBorder: 'border-slate-400/10',   stripText: 'text-slate-400/80',   label: 'CALM \u2014 Low activity' },
  UNKNOWN:  { stripBg: 'bg-slate-500/8',     stripBorder: 'border-slate-500/10',   stripText: 'text-slate-500/70',   label: 'UNKNOWN' },
};

const REGIME_INSIGHT = {
  VOLATILE: 'High uncertainty \u2014 AI reacts faster',
  TRENDING: 'Directional momentum prioritized',
  RANGING:  'Balanced signals \u2014 caution on breakouts',
  CALM:     'Low volatility \u2014 confidence stabilizes',
  UNKNOWN:  'Awaiting regime classification',
};

const REGIME_ANIMATION_CLASS = {
  VOLATILE: 'regime-volatile',
  TRENDING: 'regime-trending',
};

const DIRECTION_STYLES = {
  LONG:  { label: '\u2191 LONG',  color: 'text-emerald-400', bg: 'bg-emerald-400/10', border: 'border-emerald-400/20' },
  SHORT: { label: '\u2193 SHORT', color: 'text-rose-400',    bg: 'bg-rose-400/10',    border: 'border-rose-400/20'    },
  FLAT:  { label: '\u2014 FLAT',  color: 'text-slate-400',   bg: 'bg-slate-400/10',   border: 'border-slate-400/20'   },
};

const BIAS_STYLES = {
  STRONG_BULLISH: { label: 'Strong Bullish Bias', color: 'text-emerald-400' },
  BULLISH:        { label: 'Bullish Bias',         color: 'text-emerald-300' },
  NEUTRAL:        { label: 'Neutral Bias',         color: 'text-slate-400'   },
  BEARISH:        { label: 'Bearish Bias',         color: 'text-rose-300'    },
  STRONG_BEARISH: { label: 'Strong Bearish Bias',  color: 'text-rose-400'    },
};

export default function SnapshotCard({ data, onClick }) {
  const {
    symbol,
    finalSignal,
    confidence,
    marketRegime,
    divergenceFlag,
    aiReasoning,
    savedAt,
    tradingSession,
    entryPrice,
    targetPrice,
    stopLoss,
    estimatedHoldMinutes,
    tradeDirection,
    directionalBias,
  } = data;

  const signal = SIGNAL_STYLES[finalSignal] || SIGNAL_STYLES.HOLD;
  const regime = REGIME_STYLES[marketRegime] || REGIME_STYLES.UNKNOWN;
  const strip  = REGIME_STRIP[marketRegime]  || REGIME_STRIP.UNKNOWN;
  const insight = REGIME_INSIGHT[marketRegime] || REGIME_INSIGHT.UNKNOWN;
  const regimeAnimClass = REGIME_ANIMATION_CLASS[marketRegime] || '';

  const confidencePct = Math.round((confidence ?? 0) * 100);
  const timeStr = savedAt ? formatTime(savedAt) : '--:--';

  const modelLabel = marketRegime === 'VOLATILE' ? 'haiku-fast' : 'sonnet-deep';
  const decisionStrength = divergenceFlag
    ? 'CAUTIOUS DECISION'
    : (confidence ?? 0) >= 0.75
      ? 'STRONG DECISION'
      : 'BALANCED DECISION';

  return (
    <motion.div
      layout
      initial={{ opacity: 0, scale: 0.95 }}
      animate={{ opacity: 1, scale: 1 }}
      exit={{ opacity: 0, scale: 0.95 }}
      transition={{ type: 'spring', stiffness: 300, damping: 30 }}
      onClick={onClick}
      className={`
        relative group cursor-pointer select-none
        bg-slate-900 border border-slate-800 rounded-2xl overflow-hidden
        hover:scale-[1.02] hover:border-slate-700 hover:bg-slate-900/80
        transition-all duration-300 ease-out
        ${divergenceFlag ? 'divergence-glow' : ''}
        ${regimeAnimClass}
      `}
    >
      {/* ── Regime Header Strip ─────────────────────────────── */}
      <div className={`px-4 py-1.5 border-b ${strip.stripBg} ${strip.stripBorder}`}>
        <span className={`text-[10px] font-semibold uppercase tracking-widest ${strip.stripText}`}>
          {strip.label}
        </span>
      </div>

      {/* ── Card body ───────────────────────────────────────── */}
      <div className="p-6">
        {/* ── Top row: Symbol + Regime badge ────────────────── */}
        <div className="flex items-center justify-between mb-1.5">
          <h3 className="text-lg font-semibold font-mono tracking-wide text-slate-100">
            {symbol}
          </h3>
          <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-medium uppercase tracking-wider ${regime.bg} ${regime.color}`}>
            <span className={`w-1.5 h-1.5 rounded-full ${regime.dot}`} />
            {marketRegime || 'UNKNOWN'}
          </span>
        </div>

        {/* ── AI Intent Badge + Model Label ────────────────── */}
        <div className="flex justify-end mb-4">
          <span className={`text-[10px] font-medium tracking-wide opacity-70 ${divergenceFlag ? 'text-amber-400' : 'text-slate-400'}`}>
            {divergenceFlag ? '\uD83E\uDDE0 AI Diverging' : '\uD83E\uDDE0 AI Aligned'}
            <span className="text-slate-500 opacity-60 ml-1">&middot; {modelLabel}</span>
          </span>
        </div>

        {/* ── Signal — large prominent text ─────────────────── */}
        <div className="flex items-baseline gap-3 mb-1.5">
          <span className={`text-3xl font-bold tracking-tight ${signal.color}`}>
            {finalSignal}
          </span>
          {tradeDirection && (
            <DirectionBadge direction={tradeDirection} />
          )}
          {divergenceFlag && (
            <motion.span
              initial={{ scale: 0 }}
              animate={{ scale: 1 }}
              className="text-amber-400 text-lg"
              title="AI diverges from consensus"
            >
              <WarningIcon />
            </motion.span>
          )}
        </div>
        {/* ── Directional Bias label ────────────────────────── */}
        {directionalBias && (
          <p className={`text-[10px] font-semibold uppercase tracking-wider mb-1 ${BIAS_STYLES[directionalBias]?.color || 'text-slate-500'}`}>
            {BIAS_STYLES[directionalBias]?.label || directionalBias}
          </p>
        )}

        {/* ── Regime Insight Line ───────────────────────────── */}
        <p className={`text-[11px] mb-1 ${regime.color} opacity-70`}>
          {insight}
        </p>

        {/* ── AI vs Consensus Insight ─────────────────────── */}
        <p className={`text-[11px] mb-1 opacity-60 ${divergenceFlag ? 'text-amber-400' : 'text-slate-500'}`}>
          {divergenceFlag ? 'AI overrode consensus guardrail' : 'AI aligned with consensus'}
        </p>

        {/* ── Decision Strength Label ─────────────────────── */}
        <p className="text-[10px] font-semibold uppercase tracking-wider text-slate-400 mb-1">
          {decisionStrength}
        </p>

        {/* ── Guardrail Awareness Indicator ───────────────── */}
        {divergenceFlag && (
          <p className="text-[11px] text-amber-400 opacity-70 mb-1">
            {'\u2696'} Consensus guardrail overridden
          </p>
        )}

        <div className="mb-1" />

        {/* ── Confidence bar ────────────────────────────────── */}
        <div className="mb-4">
          <div className="flex items-center justify-between mb-1.5">
            <span className="text-[11px] font-medium text-slate-500 uppercase tracking-wider">
              Confidence
            </span>
            <span className="text-sm font-mono font-medium text-slate-300">
              {confidencePct}%
            </span>
          </div>
          <div className="h-1.5 rounded-full bg-slate-800 overflow-hidden">
            <motion.div
              className={`h-full rounded-full ${signal.color.replace('text-', 'bg-')}`}
              initial={{ width: 0 }}
              animate={{ width: `${confidencePct}%` }}
              transition={{ duration: 0.8, ease: 'easeOut' }}
            />
          </div>
        </div>

        {/* ── Entry / Target / Stop row ──────────────────────── */}
        {(entryPrice || targetPrice || stopLoss) && (
          <div className="flex items-center gap-3 mb-3 py-2 px-3 rounded-lg bg-slate-800/40 border border-slate-700/30">
            {entryPrice && (
              <PriceChip label="Entry" value={entryPrice} color="text-sky-400" />
            )}
            {targetPrice && (
              <PriceChip label="Target" value={targetPrice} color="text-emerald-400" />
            )}
            {stopLoss && (
              <PriceChip label="Stop" value={stopLoss} color="text-rose-400" />
            )}
            {estimatedHoldMinutes && (
              <span className="ml-auto text-[10px] font-mono text-slate-500">
                ~{estimatedHoldMinutes}m
              </span>
            )}
          </div>
        )}

        {/* ── AI Reasoning preview ───────────────────────────── */}
        {aiReasoning && (
          <p className="text-[11px] text-slate-500 leading-relaxed mb-3 line-clamp-2">
            {aiReasoning}
          </p>
        )}

        {/* ── Footer: session + timestamp ───────────────────── */}
        <div className="flex items-center justify-between pt-3 border-t border-slate-800/60">
          <span className="text-[11px] text-slate-600 font-mono">
            {timeStr}
          </span>
          <div className="flex items-center gap-2">
            {tradingSession && (
              <SessionChip session={tradingSession} />
            )}
            {divergenceFlag && (
              <span className="text-[10px] font-medium text-amber-400/70 uppercase tracking-wider">
                Divergence
              </span>
            )}
          </div>
        </div>
      </div>

      {/* ── Hover hint ────────────────────────────────────── */}
      <div className="absolute inset-0 rounded-2xl opacity-0 group-hover:opacity-100 transition-opacity duration-300 pointer-events-none flex items-end justify-center pb-3">
        <span className="text-[10px] text-slate-500 bg-slate-900/90 px-2 py-0.5 rounded-md">
          Click for details
        </span>
      </div>
    </motion.div>
  );
}

/* ── Helpers ──────────────────────────────────────────────────── */

function formatTime(savedAt) {
  try {
    const date = new Date(savedAt);
    if (isNaN(date.getTime())) return savedAt;
    const now = new Date();
    const isToday = date.toDateString() === now.toDateString();
    if (isToday) {
      return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    }
    return date.toLocaleDateString([], { month: 'short', day: 'numeric' }) +
      ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  } catch {
    return savedAt;
  }
}

const SESSION_STYLES = {
  OPENING_BURST:        { label: 'Opening', color: 'text-emerald-400/80', border: 'border-emerald-400/20' },
  POWER_HOUR:           { label: 'Power Hour', color: 'text-amber-400/80', border: 'border-amber-400/20' },
  MIDDAY_CONSOLIDATION: { label: 'Midday', color: 'text-slate-400/80', border: 'border-slate-400/20' },
  OFF_HOURS:            { label: 'Off Hours', color: 'text-slate-500/70', border: 'border-slate-500/20' },
};

function SessionChip({ session }) {
  const cfg = SESSION_STYLES[session] || { label: session, color: 'text-slate-500/70', border: 'border-slate-500/20' };
  return (
    <span className={`text-[10px] font-mono px-1.5 py-0.5 rounded border ${cfg.color} ${cfg.border} bg-slate-900/50`}>
      {cfg.label}
    </span>
  );
}

function PriceChip({ label, value, color }) {
  return (
    <div className="flex flex-col items-center gap-0.5">
      <span className="text-[9px] text-slate-600 uppercase tracking-wider">{label}</span>
      <span className={`text-[11px] font-mono font-medium ${color}`}>
        {value.toFixed(2)}
      </span>
    </div>
  );
}

function DirectionBadge({ direction }) {
  const cfg = DIRECTION_STYLES[direction] || DIRECTION_STYLES.FLAT;
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded-md text-[11px] font-bold font-mono border ${cfg.color} ${cfg.bg} ${cfg.border}`}>
      {cfg.label}
    </span>
  );
}

function WarningIcon() {
  return (
    <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
      <path
        fillRule="evenodd"
        d="M8.485 2.495c.673-1.167 2.357-1.167 3.03 0l6.28 10.875c.673 1.167-.17 2.625-1.516 2.625H3.72c-1.347 0-2.189-1.458-1.515-2.625L8.485 2.495zM10 6a.75.75 0 01.75.75v3.5a.75.75 0 01-1.5 0v-3.5A.75.75 0 0110 6zm0 9a1 1 0 100-2 1 1 0 000 2z"
        clipRule="evenodd"
      />
    </svg>
  );
}
