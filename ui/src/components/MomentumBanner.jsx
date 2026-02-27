import { motion, AnimatePresence } from 'framer-motion';

const STATE_CONFIG = {
  CALM: {
    icon: '\u2022',
    label: 'Calm',
    description: 'Mixed signals — no clear momentum',
    dotColor: 'bg-slate-400',
    textColor: 'text-slate-400',
    bgColor: 'bg-slate-800/40',
    borderColor: 'border-slate-700/30',
  },
  BUILDING: {
    icon: '\u25B2',
    label: 'Building',
    description: 'Momentum forming — not yet confirmed',
    dotColor: 'bg-amber-400',
    textColor: 'text-amber-300',
    bgColor: 'bg-amber-400/5',
    borderColor: 'border-amber-400/15',
  },
  CONFIRMED: {
    icon: '\u25CF',
    label: 'Confirmed Momentum',
    description: 'Stable alignment — attention warranted',
    dotColor: 'bg-emerald-400',
    textColor: 'text-emerald-300',
    bgColor: 'bg-emerald-400/5',
    borderColor: 'border-emerald-400/15',
  },
  WEAKENING: {
    icon: '\u25BC',
    label: 'Weakening',
    description: 'Momentum losing stability',
    dotColor: 'bg-sky-400',
    textColor: 'text-sky-300',
    bgColor: 'bg-sky-400/5',
    borderColor: 'border-sky-400/15',
  },
};

/**
 * Calm informational banner displaying per-symbol market momentum state.
 * Designed for top-center placement. Subtle, non-intrusive, no flashing.
 */
export default function MomentumBanner({ marketStates }) {
  if (!marketStates || marketStates.length === 0) return null;

  return (
    <div className="w-full flex justify-center px-4 sm:px-6 lg:px-8 py-2">
      <motion.div
        initial={{ opacity: 0, y: -8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.5, ease: 'easeOut' }}
        className="flex flex-wrap items-center justify-center gap-3"
      >
        <AnimatePresence mode="popLayout">
          {marketStates.map((state) => (
            <MomentumPill key={state.symbol} data={state} />
          ))}
        </AnimatePresence>
      </motion.div>
    </div>
  );
}

function MomentumPill({ data }) {
  const { symbol, marketState, dominantSignal, confidenceTrend, signalAlignment } = data;
  const config = STATE_CONFIG[marketState] || STATE_CONFIG.CALM;

  return (
    <motion.div
      layout
      initial={{ opacity: 0, scale: 0.95 }}
      animate={{ opacity: 1, scale: 1 }}
      exit={{ opacity: 0, scale: 0.95 }}
      transition={{ type: 'spring', stiffness: 400, damping: 35 }}
      className={`
        inline-flex items-center gap-2.5 px-4 py-2 rounded-xl
        ${config.bgColor} border ${config.borderColor}
        backdrop-blur-sm
      `}
    >
      {/* State dot */}
      <span className={`w-2 h-2 rounded-full ${config.dotColor} momentum-dot-${marketState.toLowerCase()}`} />

      {/* Symbol */}
      <span className="text-xs font-mono font-semibold text-slate-200 tracking-wide">
        {symbol}
      </span>

      {/* Divider */}
      <span className="text-slate-700">|</span>

      {/* State label */}
      <span className={`text-xs font-medium ${config.textColor}`}>
        {config.label}
      </span>

      {/* Subtle alignment indicator */}
      {marketState === 'CONFIRMED' && (
        <span className="text-[10px] text-emerald-400/60 font-mono">
          {dominantSignal}
        </span>
      )}

      {/* Confidence trend micro-indicator */}
      {confidenceTrend === 'RISING' && (
        <span className="text-[10px] text-emerald-400/50">{'\u2191'}</span>
      )}
      {confidenceTrend === 'DECLINING' && (
        <span className="text-[10px] text-rose-400/50">{'\u2193'}</span>
      )}
    </motion.div>
  );
}
