import { useState } from 'react';
import { motion } from 'framer-motion';

const POSTURE_CONFIG = {
  CONFIDENT_HOLD: {
    label: 'Confident Hold',
    color: '#4ade80',
    bg: 'rgba(74, 222, 128, 0.08)',
    border: 'rgba(74, 222, 128, 0.20)',
    textClass: 'text-emerald-400',
  },
  WATCH_CLOSELY: {
    label: 'Watch Closely',
    color: '#60a5fa',
    bg: 'rgba(96, 165, 250, 0.08)',
    border: 'rgba(96, 165, 250, 0.20)',
    textClass: 'text-blue-400',
  },
  DEFENSIVE_HOLD: {
    label: 'Defensive Hold',
    color: '#fb923c',
    bg: 'rgba(251, 146, 60, 0.08)',
    border: 'rgba(251, 146, 60, 0.20)',
    textClass: 'text-orange-400',
  },
  EXIT_CANDIDATE: {
    label: 'Exit Candidate',
    color: '#f87171',
    bg: 'rgba(248, 113, 113, 0.08)',
    border: 'rgba(248, 113, 113, 0.20)',
    textClass: 'text-red-400',
  },
};

const STRUCTURE_CONFIG = {
  CONTINUATION: { label: 'Continuation', textClass: 'text-emerald-400/80', borderClass: 'border-emerald-400/20' },
  STALLING:     { label: 'Stalling',     textClass: 'text-blue-400/80',    borderClass: 'border-blue-400/20' },
  FATIGUED:     { label: 'Fatigued',     textClass: 'text-orange-400/80',  borderClass: 'border-orange-400/20' },
};

const DURATION_CONFIG = {
  FRESH:    { label: 'Fresh',    textClass: 'text-emerald-400/80', borderClass: 'border-emerald-400/20' },
  AGING:    { label: 'Aging',    textClass: 'text-amber-400/80',   borderClass: 'border-amber-400/20' },
  EXTENDED: { label: 'Extended', textClass: 'text-orange-400/80',  borderClass: 'border-orange-400/20' },
};

/**
 * Tactical Cockpit Header — displays trade posture, status chips,
 * risk envelope, exit trade action, and confidence trend mini-sparkline.
 *
 * Personality: CALM TACTICAL CONTROL PANEL
 */
export default function TacticalPostureHeader({ activeTrade, onTradeExit }) {
  if (!activeTrade) return null;

  const { exitAwareness, riskEnvelope } = activeTrade;
  if (!exitAwareness) return null;

  const posture = exitAwareness.tradePosture || 'WATCH_CLOSELY';
  const config = POSTURE_CONFIG[posture] || POSTURE_CONFIG.WATCH_CLOSELY;
  const isExitCandidate = posture === 'EXIT_CANDIDATE';

  const momentum = exitAwareness.momentumShift ? 'Weakening' : 'Stable';
  const structure = exitAwareness.structureSignal || 'CONTINUATION';
  const duration = exitAwareness.durationSignal || 'FRESH';

  const structCfg = STRUCTURE_CONFIG[structure] || STRUCTURE_CONFIG.CONTINUATION;
  const durCfg = DURATION_CONFIG[duration] || DURATION_CONFIG.FRESH;

  // Confidence trend data from recent values (mock sparkline from drift)
  const confidenceDrift = exitAwareness.confidenceDrift ?? 0;

  return (
    <motion.div
      initial={{ opacity: 0, y: -6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35, ease: 'easeOut' }}
      className="w-full flex flex-col items-center px-4 sm:px-6 lg:px-8 pt-3 pb-2"
    >
      {/* Vertical tick marker */}
      <div
        className="w-[2px] h-3 rounded-full mb-2"
        style={{
          backgroundColor: config.color,
          opacity: 0.5,
          transition: 'background-color 0.35s ease',
        }}
      />

      {/* Posture pill */}
      <motion.div
        layout
        className={`
          inline-flex items-center gap-2 px-5 py-2 rounded-full
          tactical-transition
          ${isExitCandidate ? 'calm-pulse' : ''}
        `}
        style={{
          backgroundColor: config.bg,
          border: `1px solid ${config.border}`,
          transition: 'background-color 0.35s ease, border-color 0.35s ease',
        }}
      >
        {isExitCandidate && (
          <span className="text-sm opacity-85">⚠️</span>
        )}
        <span
          className={`text-xs font-semibold uppercase tracking-widest ${config.textClass}`}
          style={{ transition: 'color 0.35s ease' }}
        >
          {config.label}
        </span>
      </motion.div>

      {/* Thin underline */}
      <div
        className="h-[1px] w-32 mt-2 rounded-full"
        style={{
          backgroundColor: config.color,
          opacity: 0.15,
          transition: 'background-color 0.35s ease',
        }}
      />

      {/* Confidence trend mini sparkline */}
      <ConfidenceMini drift={confidenceDrift} color={config.color} />

      {/* Status chips */}
      <div className="flex items-center gap-2 mt-2">
        <StatusChip
          label={`Momentum: ${momentum}`}
          textClass={exitAwareness.momentumShift ? 'text-orange-400/80' : 'text-emerald-400/80'}
          borderClass={exitAwareness.momentumShift ? 'border-orange-400/20' : 'border-emerald-400/20'}
        />
        <StatusChip
          label={`Structure: ${structCfg.label}`}
          textClass={structCfg.textClass}
          borderClass={structCfg.borderClass}
        />
        <StatusChip
          label={`Duration: ${durCfg.label}`}
          textClass={durCfg.textClass}
          borderClass={durCfg.borderClass}
        />
      </div>

      {/* Risk envelope */}
      {riskEnvelope && (
        <div className="flex items-center gap-3 mt-2">
          <span className="text-[10px] text-slate-600 uppercase tracking-wider">Risk</span>
          <span className="text-[10px] font-mono text-amber-400/70">
            Soft: {riskEnvelope.softStopPercent?.toFixed(1)}%
          </span>
          <span className="w-px h-3 bg-slate-700" />
          <span className="text-[10px] font-mono text-rose-400/70">
            Hard: {riskEnvelope.hardInvalidationPercent?.toFixed(1)}%
          </span>
        </div>
      )}

      {/* Exit Trade action */}
      {onTradeExit && activeTrade?.trade?.symbol && (
        <ExitTradePanel
          symbol={activeTrade.trade.symbol}
          onTradeExit={onTradeExit}
        />
      )}
    </motion.div>
  );
}

/* ── Exit Trade Panel ────────────────────────────────────────── */

function ExitTradePanel({ symbol, onTradeExit }) {
  const [exitPrice, setExitPrice] = useState('');
  const [expanded, setExpanded] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  if (!expanded) {
    return (
      <button
        onClick={() => setExpanded(true)}
        className="mt-2 px-3 py-1 rounded-lg bg-slate-800/60 border border-slate-700/40
                   text-[10px] text-slate-500 uppercase tracking-wider
                   hover:border-slate-600 hover:text-slate-400 transition-all"
      >
        Exit Trade
      </button>
    );
  }

  const handleConfirm = async () => {
    if (!exitPrice || isNaN(parseFloat(exitPrice))) return;
    setSubmitting(true);
    await onTradeExit(symbol, parseFloat(exitPrice));
    setSubmitting(false);
    setExpanded(false);
  };

  return (
    <div className="mt-2 flex items-center gap-2">
      <input
        type="number"
        step="0.01"
        value={exitPrice}
        onChange={(e) => setExitPrice(e.target.value)}
        placeholder="Exit price"
        className="w-28 bg-slate-900 border border-slate-700 rounded-lg px-2 py-1
                   text-xs text-slate-200 font-mono placeholder:text-slate-600
                   focus:outline-none focus:border-slate-500 transition-colors"
        autoFocus
      />
      <button
        onClick={handleConfirm}
        disabled={!exitPrice || submitting}
        className="px-3 py-1 rounded-lg bg-rose-500/10 border border-rose-500/20
                   text-rose-400 text-[10px] font-medium uppercase tracking-wider
                   hover:bg-rose-500/20 transition-all disabled:opacity-40"
      >
        {submitting ? '...' : 'Confirm Exit'}
      </button>
      <button
        onClick={() => { setExpanded(false); setExitPrice(''); }}
        className="text-slate-600 hover:text-slate-400 text-xs transition-colors"
      >
        Cancel
      </button>
    </div>
  );
}

/* ── Status Chip ──────────────────────────────────────────────── */

function StatusChip({ label, textClass, borderClass }) {
  return (
    <span
      className={`
        inline-flex px-2.5 py-1 rounded text-[10px] font-mono font-medium
        uppercase tracking-wider border
        bg-slate-900/60 tactical-transition
        ${textClass} ${borderClass}
      `}
    >
      {label}
    </span>
  );
}

/* ── Confidence Trend Mini Sparkline ──────────────────────────── */

function ConfidenceMini({ drift, color }) {
  // Generate a simple 7-point sparkline from drift direction
  const mid = 12;
  const driftScale = Math.max(-1, Math.min(1, drift * 5));
  const points = [];
  for (let i = 0; i < 7; i++) {
    const t = i / 6;
    const noise = Math.sin(i * 1.8) * 2;
    const trend = driftScale * t * 6;
    points.push({ x: 6 + i * 14, y: mid - trend + noise });
  }

  const pathD = points
    .map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x},${p.y}`)
    .join(' ');

  return (
    <svg
      width="104"
      height="24"
      viewBox="0 0 104 24"
      className="mt-1.5"
      style={{ overflow: 'visible' }}
    >
      <path
        d={pathD}
        fill="none"
        stroke={color}
        strokeWidth="2.5"
        strokeLinecap="round"
        strokeLinejoin="round"
        opacity="0.55"
        style={{ transition: 'stroke 0.35s ease' }}
      />
    </svg>
  );
}
