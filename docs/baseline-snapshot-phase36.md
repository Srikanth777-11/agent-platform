# Baseline Snapshot — Phase 36 Complete
**Frozen:** 2026-02-28 (after Phase 36 TradeEligibilityGuard)
**Replay dataset:** NIFTY50, 2,979 candles, 2025-12-31 → 2026-02-27 (Yahoo Finance, 59 days)
**Replay mode:** REPLAY_CONSENSUS_ONLY (AI Strategist + DisciplineCoach skipped)
**DB:** agent-postgres (port 5432) — single stack, prod ports

---

## Gate Added (Phase 36)
**TradeEligibilityGuard** — placed in `buildDecision()` after Phase-35 Gate 5, before FinalDecision.

BUY eligible only if ALL:
- session ∈ {OPENING_BURST, POWER_HOUR}
- regime ∈ {VOLATILE, TRENDING}
- bias ∈ {BULLISH, STRONG_BULLISH}
- confidence ≥ 0.65
- divergenceFlag = false

SELL eligible only if ALL:
- session = OPENING_BURST
- regime = VOLATILE
- bias ∈ {BEARISH, STRONG_BEARISH}
- confidence ≥ 0.65
- divergenceFlag = false

Everything else → WATCH.

---

## Gate Firing Summary
| Gate | Count | Effect |
|---|---|---|
| Phase36-EligibilityGuard | **11** | NEUTRAL bias in VOLATILE regime → WATCH |
| Phase35-SessionGate | 1,664+ | MIDDAY/OFF_HOURS → WATCH |
| Phase35-MultiFilter | 286+ | Low confidence → WATCH |
| Phase35-BiasGate | 85+ | Bias-misaligned → WATCH |

**Phase 36 blocked 11 trades** (all were NEUTRAL directionalBias in OPENING_BURST/POWER_HOUR).

---

## Overall Metrics vs Phase 35 Baseline

| Metric | Phase 35 | Phase 36 | Change |
|--------|----------|----------|--------|
| Total decisions | 2,979 | 2,979 | — |
| Resolved trades | **434** | **421** | ↓ 3% |
| Win rate | 49.1% | **49.2%** | → flat |
| MIDDAY trades | 0 | **0** | ✅ maintained |
| SELL signals | 0 | **0** | same (expected) |
| WATCH signals | 2,309 | **2,322** | ↑ 13 more filtered |
| Avg P&L | -0.003499% | **-0.003586%** | flat |

---

## P&L by Session + Signal

| Session | Signal | Trades | Win Rate | Avg P&L |
|---------|--------|--------|----------|---------|
| OPENING_BURST | BUY | 188 | 45.2% | -0.006894% |
| POWER_HOUR | BUY | 233 | **52.4%** | -0.000917% |

**MIDDAY trades = 0** ✅ Session gate maintained.
**SELL trades = 0** — expected in consensus-only replay (Phase 37 fixes AI SELL bias).

---

## Key Finding — Why Trade Count Target Was Not Met

**Target:** 150–200 trades. **Actual:** 421 trades (↓3% from Phase 35's 434).

Root cause: In consensus-only replay, NIFTY50's OPENING_BURST and POWER_HOUR sessions almost universally produce:
- **regime = VOLATILE** (one of Phase 36's allowed regimes)
- **bias = BULLISH or STRONG_BULLISH** (NIFTY50 was in an uptrend through this period)

So Phase 36's regime+bias filter has very little to block — the conditions it requires are already present in nearly every active-session candle.

**The gate is correct.** It will have more impact in live mode when:
- Regime varies between VOLATILE, TRENDING, RANGING, CALM across different market phases
- AI Strategist generates signals in more diverse condition sets
- BEARISH/STRONG_BEARISH bias days trigger SELL eligibility checks

---

## Phase 35 vs Phase 36 Summary
- ✅ Gate implemented and confirmed firing (11 NEUTRAL bias blocks)
- ✅ MIDDAY = 0 maintained
- ✅ All remaining trades in OPENING_BURST or POWER_HOUR
- ✅ Gate logic verified: only VOLATILE/TRENDING regime + directional bias allowed through
- ⚠️ Trade count 421 (target was 150–200 — not met in consensus-only replay, expected to improve in live)
- ⚠️ Win rate 49.2% (target 55–58% — requires AI Strategist + Phase 37 prompt fix)
- ⚠️ SELL = 0 (expected in consensus-only replay)

---

## Phase 37 Targets (Starting Point = This Snapshot)

| Metric | Phase 36 Baseline | Phase 37 Target |
|--------|-------------------|--------------------|
| SELL trade count | 0 | ≥ 25% of active trades |
| Win rate | 49.2% | 52–56% |
| BUY % in STRONG_BEARISH | unknown | 0% (AI prompt corrected) |
| AI reasoning quality | N/A (consensus-only) | SELL reasoning visible in logs |
