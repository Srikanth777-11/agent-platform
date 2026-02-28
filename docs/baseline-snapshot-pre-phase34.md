# Baseline Snapshot — Pre-Phase 34
**Frozen:** 2026-02-28 (before any Phase 34 code changes)
**Replay dataset:** NIFTY50, 2,979 candles, 2025-12-31 → 2026-02-27 (Yahoo Finance, 59 days)
**Replay mode:** Full AI pipeline (Claude API on every candle)
**DB:** dev-agent-postgres (port 9432)

---

## Replay Performance

| Metric | Value |
|--------|-------|
| Total candles | 2,979 |
| Replay duration | ~9 hours (AI latency bottleneck) |
| Total decisions | 2,920 |
| Resolved trades | 1,045 |
| Win rate (replay endpoint) | 47.3% |
| Win rate (DB calculated) | 16.9% ← discrepancy: DB counts WATCH as 0-win |

---

## Signal Distribution

| Signal | Count | % of resolved |
|--------|-------|---------------|
| BUY | 931 | 89.1% |
| SELL | 114 | 10.9% |
| WATCH | ~1,400 | — (not resolved) |
| HOLD | ~4 | — (not resolved) |

**SELL is only 4% of total decisions — system is heavily BUY-biased.**

---

## P&L by Session + Signal

| Session | Signal | Trades | Win Rate | Avg P&L | Max Loss | Max Gain |
|---------|--------|--------|----------|---------|----------|----------|
| MIDDAY_CONSOLIDATION | BUY | 576 | 46.7% | -0.003478% | -0.4822% | +0.2188% |
| OPENING_BURST | BUY | 194 | 43.8% | -0.011217% | -0.2947% | +0.3208% |
| OPENING_BURST | SELL | 93 | **55.9%** | -0.006262% | -0.4082% | +0.2539% |
| POWER_HOUR | BUY | 161 | 49.7% | -0.009914% | -0.6404% | +0.5529% |
| POWER_HOUR | SELL | 21 | 38.1% | -0.031456% | -0.2702% | +0.1648% |

**Best window: OPENING_BURST + SELL = 55.9% win rate**
**Worst window: MIDDAY BUY = 576 trades that should NEVER happen**

---

## Risk Metrics

| Metric | Value |
|--------|-------|
| Avg P&L per trade | -0.006716% |
| Worst single trade | -0.640383% |
| Best single trade | +0.552907% |
| Max drawdown | -7.286219% cumulative |
| Avg drawdown | -3.804027% |
| Profit factor | 0.7734 (< 1.0 = net losing) |

**System is net-losing. Profit factor < 1 means losses outweigh gains.**

---

## System Health

| Metric | Value |
|--------|-------|
| Divergence rate | 73.8% of decisions |
| Divergence trajectory | RISING (permanent, never stabilizes) |
| Agent performance snapshots | 0 resolved (feedback loop broken) |
| Agents in live mode | 0/4 (all in warmup) |
| Replay duration | ~9 hours |
| Scheduler noise | Yes — NIFTYBEES.BSE firing every 30min during replay |

---

## Root Causes Identified (to be fixed in Phase 34+)

1. **MIDDAY trades** — 576 BUYs during consolidation hours (session gate broken in consensus)
2. **Consensus overrides AI upward** — AI says WATCH, consensus forces BUY (authority chain violated)
3. **DirectionalBias not in consensus path** — STRONG_BEARISH producing BUY trades
4. **Agent feedback loop broken** — `agent_performance_snapshot` never updates (0 resolved)
5. **AI called on every replay candle** — 6,641ms avg latency → 9hr replay
6. **Scheduler fires live symbol during replay** — log noise + API waste
7. **Two DB stacks** — prod stack separate from dev, UI reads wrong DB
8. **No drawdown tracking** — profit factor not measured before this snapshot

---

## What Changes in Phase 34 (Infrastructure Only)

- Single DB
- Scheduler pauses during replay
- Replay skips AI (consensus-only) + marks `decision_mode = REPLAY_CONSENSUS_ONLY`
- Agent win-rate query fixed

## Comparison Target After Phase 34 Replay

| Metric | Baseline | Phase 34 Target |
|--------|----------|-----------------|
| Replay duration | ~9 hours | < 20 minutes |
| Scheduler noise | Yes | Zero |
| Agent performance | 0 resolved | > 0 updating |
| DB consistency | Split | Single |
| Win rate | 47.3% | Expect similar (no strategy change) |
| Trade count | 1,045 | Expect similar (no gate change) |
