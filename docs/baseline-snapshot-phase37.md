# Baseline Snapshot — Phase 37 Complete
**Frozen:** 2026-02-28 (after Phase 37 AI Prompt Adjustment)
**Replay:** SKIPPED — prompt-only change, no structural gate added. Replay impact only visible in live mode.

---

## Change Made (Phase 37)
**File:** `agent-orchestrator/src/main/java/com/agentplatform/orchestrator/ai/AIStrategistService.java`
**Method:** `buildPrompt()`

### 1. Session gating removed from prompt
- MIDDAY and OFF_HOURS no longer say "Do NOT generate BUY or SELL signals" / "Respond HOLD only"
- Replaced with informational-only text
- Single source of truth for session enforcement = Phase-35 SessionGate + Phase-36 EligibilityGuard (in OrchestratorService)

### 2. SELL bias corrected — now symmetric with BUY
Before:
- BEARISH: "Prefer SELL when agent signals support it. Avoid BUY."
- STRONG_BEARISH: "Only signal SELL. Do NOT signal BUY in a strongly bearish market."

After:
- BEARISH: "Signal SELL when agent signals support it. The market is trending down. SELL = ENTER SHORT (buy PE options). BUY is incorrect in this direction."
- STRONG_BEARISH: "Signal SELL. The market is strongly bearish. SELL = ENTER SHORT (sell Nifty futures or buy PE options). BUY is incorrect in this direction."

### 3. Trade direction clarification updated
Added explicit instruction: "Both BUY and SELL are valid signals. Match your signal to the directional bias."
BUY and SELL lines now both show when to use them (BULLISH vs BEARISH conditions).

---

## Expected Impact in Live Mode
- SELL signals should appear in BEARISH/STRONG_BEARISH market conditions
- SELL% should reach ≥ 25% of active trades once AI Strategist is live
- Win rate should hold or improve (SELL entries in downtrends should have similar quality to BUY in uptrends)

---

## Phase 38a Targets (Starting Point = Phase 36 Metrics)
Since Phase 37 replay was skipped, Phase 36 metrics remain the quantitative baseline:

| Metric | Phase 36 (last replay) | Phase 38a Target |
|--------|------------------------|-----------------|
| Trade count | 421 | Stable (passive mode — no blocking) |
| Win rate | 49.2% | Stable or improving |
| SELL trades | 0 (consensus-only) | Visible in live mode |
| Registry populating | N/A | edge_conditions table filling |
| REPLAY rows excluded | N/A | decision_mode filter verified |
