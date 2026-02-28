# Baseline Snapshot — Phase 34 Complete
**Frozen:** 2026-02-28 (after Phase 34 infra stabilization)
**Replay dataset:** NIFTY50, 2,979 candles, 2025-12-31 → 2026-02-27 (Yahoo Finance, 59 days)
**Replay mode:** REPLAY_CONSENSUS_ONLY (AI Strategist + DisciplineCoach skipped)
**DB:** agent-postgres (port 5432) — single stack, prod ports
**Replay duration:** ~25 minutes (was ~9 hours in pre-Phase 34)

---

## Replay Performance

| Metric | Value | vs Pre-Phase 34 |
|--------|-------|-----------------|
| Total candles | 2,979 | same |
| Replay duration | ~25 min | ↓ from ~9 hours ✅ |
| Total decisions | 3,764 | ↑ (consensus generates more signals) |
| Resolved trades | 3,273 | ↑ |
| Win rate (endpoint) | 50.1% | ↓ from 47.3% (consensus-only, different path) |
| Win rate (DB calc) | 43.7% | reference |
| Divergence % | 0.00% | ↓ from 73.8% ✅ (no AI = no divergence) |

---

## Signal Distribution (resolved trades only)

| Signal | Count | % of resolved |
|--------|-------|---------------|
| BUY | 1,661 | 50.7% |
| SELL | 1,612 | 49.3% |
| WATCH | 74 | — |
| HOLD | 416 | — |

**Note:** SELL is now 49.3% — consensus-only is more balanced than AI (which was 89% BUY).
This is expected: AI has BUY bias, consensus uses raw agent votes which are more symmetric.

---

## P&L by Session + Signal

| Session | Signal | Trades | Win Rate | Avg P&L |
|---------|--------|--------|----------|---------|
| MIDDAY_CONSOLIDATION | BUY | **1,065** | 49.0% | -0.001357% |
| MIDDAY_CONSOLIDATION | SELL | **1,181** | 53.3% | +0.002458% |
| OPENING_BURST | BUY | 273 | 44.0% | -0.011227% |
| OPENING_BURST | SELL | 203 | 49.3% | -0.005221% |
| POWER_HOUR | BUY | 323 | 52.6% | -0.002192% |
| POWER_HOUR | SELL | 228 | 45.6% | -0.015096% |

**Critical finding: MIDDAY still has 2,246 trades (68.7% of all trades) — Phase 35 must fix this.**
**Best window: MIDDAY SELL = 53.3% win rate — but MIDDAY trades should be ZERO after Phase 35.**

---

## Risk Metrics

| Metric | Value |
|--------|-------|
| Avg P&L per trade | -0.002083% |
| Worst single trade | -1.9511% |
| Best single trade | +0.7743% |
| Divergence rate | 0.00% (no AI = no divergence flag) |

---

## Agent Performance Snapshot

| Agent | Win Rate | Total Decisions | Avg Confidence |
|-------|----------|-----------------|----------------|
| PortfolioAgent | **87.3%** | 3,764 | 0.768 |
| TrendAgent | 56.8% | 3,764 | 0.639 |
| DisciplineCoach | 4.8% | 785 | 0.248 |
| RiskAgent | 3.9% | 3,764 | 0.535 |

**Note:** DisciplineCoach has only 785 decisions — it was skipped during replay, these are from
a prior session. RiskAgent low win_rate is expected — it is a contrarian/risk-flag agent.

---

## Infrastructure Metrics (Phase 34 Goals — All Met)

| Goal | Result |
|------|--------|
| Single DB | ✅ agent-postgres only |
| Prod ports | ✅ 8080-8086, 5432 |
| Scheduler silent during replay | ✅ isReplayRunning() gate works |
| AI skipped in replay | ✅ REPLAY_CONSENSUS_ONLY on all rows |
| DisciplineCoach skipped | ✅ 3 agents dispatched (was 4) |
| Replay duration < 30 min | ✅ ~25 minutes |
| decision_mode column populated | ✅ all rows = REPLAY_CONSENSUS_ONLY |

---

## Phase 35 Targets (Starting Point = This Snapshot)

| Metric | Phase 34 Baseline | Phase 35 Target |
|--------|-------------------|-----------------|
| MIDDAY trade count | 2,246 | **0** |
| Total trade count | 3,273 | < 1,000 |
| SELL % of active | 49.3% | 25–40% (with AI bias correction) |
| Win rate | 50.1% | 52–55% |
| Divergence % | 0% (no AI) | < 30% (with AI back in play) |
| Avg P&L | -0.002083% | Positive or near-zero |
