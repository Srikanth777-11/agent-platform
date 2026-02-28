# Agent Platform — Future Evolution Plan
> **Source:** Principal Architect + Trading Intelligence Specialist Review (2026-02-28)
> **Audience:** Claude AI (implementation partner), Senior Engineers
> **Prerequisite:** Phases 1–38a complete. Angel One credentials pending for Phase 38b.
> **Status:** Planning document — not yet implemented

---

## Overview

The system has reached institutional-grade architecture:
- Decision plane + control plane separated
- AI authority with consensus guardrail
- Hard discipline gates (6 layers)
- Session-aware gating (IST)
- Directional bias enforcement
- Divergence detection and dampening
- Replay-isolated learning loop

The architect's verdict: **"Beyond 95% of retail algo setups."**

The next evolution targets **four compound improvements**:

| Domain | Phase | Priority |
|---|---|---|
| Orchestrator refactor — DecisionPipelineEngine | 39 | P1 (prerequisite for future gate growth) |
| Micro-session segmentation (IST granularity) | 40 | P1 (doubles scalping precision) |
| Multi-horizon outcome resolution | 41 | P2 (learning loop accuracy) |
| PositionSizingEngine | 42 | P2 (signal → trade optimizer) |
| DailyRiskGovernor | 43 | P1 (live trading critical) |
| Peak-mode latency optimization | 44 | P3 (performance) |
| Bayesian edge estimator (38b upgrade) | 45 | P3 (after ≥20 samples) |

**Execution order:** 39 → 40 → 43 → 41 → 42 → 44 → 45
(Gate complexity → Time precision → Risk control → Learning → Sizing → Speed → Statistics)

---

## Phase 39 — DecisionPipelineEngine Extraction

### Problem
`OrchestratorService` has reached complexity ceiling. It currently owns:
- Regime classification
- Session classification
- Weight computation
- Memory fetch
- AI invocation
- Divergence streak calculation
- 6-layer gate execution
- Decision building
- Outcome resolution
- Event publishing

As scalping rules evolve (Phase 40+), gate logic will expand. Mixing gating and orchestration creates maintenance risk.

### Goal
Extract all gating logic into a dedicated `DecisionPipelineEngine`. OrchestratorService becomes a pure coordinator — it assembles inputs, calls the pipeline, receives `FinalDecision`.

### Architecture

```
OrchestratorService (coordinator)
    ├── fetch market data
    ├── call analysis-engine
    ├── build DecisionContext
    └── call DecisionPipelineEngine.evaluate(context) → FinalDecision

DecisionPipelineEngine (pure gating logic)
    ├── Gate 1: AuthorityChain
    ├── Gate 2: SessionGate
    ├── Gate 3: BiasGate
    ├── Gate 4: DivergencePenalty
    ├── Gate 5: MultiFilter
    ├── Gate 6: EligibilityGuard
    └── buildFinalDecision()
```

### Files to create / modify

| File | Change |
|---|---|
| NEW `agent-orchestrator/.../pipeline/DecisionPipelineEngine.java` | Extract gates 1–6 + buildDecision from OrchestratorService |
| MODIFY `agent-orchestrator/.../service/OrchestratorService.java` | Inject DecisionPipelineEngine, remove gate logic, call engine.evaluate() |

### Notes
- `DecisionPipelineEngine` should be `@Component` — injected into OrchestratorService
- All gate logic moves verbatim — no behavioral change in Phase 39
- Phase 40+ gates are added directly into DecisionPipelineEngine, not OrchestratorService
- This is a pure refactor — no replay needed, no schema change

---

## Phase 40 — Micro-Session Segmentation

### Problem
OPENING_BURST (9:15–10:00) is treated as one 45-minute block. But NIFTY opening has distinct phases:

| Minutes | Market Behavior |
|---|---|
| 9:15–9:25 | Price discovery. Extremely volatile. Fake breakouts common. Wide spread. |
| 9:25–9:40 | Real directional expansion. Optimal scalping window. |
| 9:40–10:00 | Continuation OR trap. Momentum weakening. Reversals start. |

Treating all three identically causes the system to trade phase-1 noise with phase-2 rules.

### Goal
Sub-divide OPENING_BURST into three distinct micro-sessions with different gate parameters per phase.

### New Enum Values

```java
// In TradingSession enum (common-lib)
OPENING_PHASE_1,   // 9:15–9:25  Price discovery
OPENING_PHASE_2,   // 9:25–9:40  Directional expansion (prime scalp window)
OPENING_PHASE_3,   // 9:40–10:00 Continuation or trap
POWER_HOUR,        // 15:00–15:30
MIDDAY_CONSOLIDATION,
OFF_HOURS
```

### Gate Logic per Micro-Session

**OPENING_PHASE_1 (High noise — strict)**
- Confidence ≥ 0.70 (raised from 0.65)
- Require STRONG_BULLISH or STRONG_BEARISH only (BULLISH/BEARISH blocked)
- Block if divergenceFlag = true
- Position size multiplier = 0.5 (half size — passed through to PositionSizingEngine Phase 42)

**OPENING_PHASE_2 (Optimal window — normal rules)**
- Confidence ≥ 0.65 (current rules apply)
- Bias ∈ {BULLISH, STRONG_BULLISH} for BUY / {BEARISH, STRONG_BEARISH} for SELL
- Normal position size

**OPENING_PHASE_3 (Trap risk — continuation only)**
- Block if momentumState = WEAKENING (see below)
- Block if divergenceStreak ≥ 1
- Allow only bias-aligned continuation trades, not reversals

### New Field: momentumState

**Computation** (in TrendAgent, alongside existing 5-vote bias):
```
lastPrice vs price[2]: compare most recent 3 candles
RISING   → price[0] > price[1] > price[2]
WEAKENING → price[0] > price[1] but price[1] <= price[2]
FALLING  → price[0] < price[1] < price[2]
```

Store in `AnalysisResult.metadata["momentumState"]` for TrendAgent.
Extract in OrchestratorService alongside `directionalBias`.
Add `momentumState` field to `DecisionContext`.

### Files to modify

| File | Change |
|---|---|
| `common-lib/.../model/TradingSession.java` | Add OPENING_PHASE_1, OPENING_PHASE_2, OPENING_PHASE_3 |
| `common-lib/.../cognition/TradingSessionClassifier.java` | Sub-classify OPENING_BURST by minute |
| `common-lib/.../model/DecisionContext.java` | Add `momentumState` field + withMomentumState() copy-factory |
| `agent-orchestrator/.../pipeline/DecisionPipelineEngine.java` | Phase-1/2/3 differentiated gate logic |
| `analysis-engine/.../agent/TrendAgent.java` | Add momentumState to metadata |
| `agent-orchestrator/.../service/OrchestratorService.java` | Extract momentumState from TrendAgent result, inject into context |
| `history-service/schema.sql` | Add `trading_session` values (enum-based col, no schema change if VARCHAR) |

### Expected Impact
- OPENING_PHASE_1 noise reduction: estimated 30–40% fewer false signals
- OPENING_PHASE_2 precision: best quality scalps concentrated here
- OPENING_PHASE_3 trap avoidance: prevent entering at momentum exhaustion

---

## Phase 41 — Multi-Horizon Outcome Resolution

### Problem
Current P&L resolution: **next candle close vs entry price**.

For 5-min candles this is too naive for scalping:
- Momentum may run 3 candles before reversing
- Fake breakouts reverse in 1–2 candles (same candle in Phase 1)
- Stop-hit resolution is more realistic than time-based

### Goal
Resolve each trade under multiple horizons simultaneously. Classify outcome quality, not just win/loss.

### Outcome Labels

| Label | Condition |
|---|---|
| `FAST_WIN` | Profitable at 1-candle, momentum up |
| `SLOW_WIN` | Loss at 1-candle, profitable at 3-candle |
| `STOP_OUT` | Price crossed stop loss before target |
| `TARGET_HIT` | Price reached target before stop |
| `NO_EDGE` | Expired without reaching stop or target |

### Implementation

**New column in `decision_history`:**
```sql
outcome_label VARCHAR(20)    -- FAST_WIN, SLOW_WIN, STOP_OUT, TARGET_HIT, NO_EDGE
outcome_1c    DOUBLE PRECISION  -- 1-candle P&L%
outcome_3c    DOUBLE PRECISION  -- 3-candle P&L%
```

**Logic in `HistoryService.resolveOutcomes()`:**
```
For each unresolved trade:
  1c = (price_at_1_candle - entryPrice) / entryPrice
  3c = (price_at_3_candle - entryPrice) / entryPrice
  stopHit = any candle between entry and +3 crossed stopLoss
  targetHit = any candle between entry and +3 crossed targetPrice

  if targetHit → TARGET_HIT
  elif stopHit → STOP_OUT
  elif 1c > 0 → FAST_WIN
  elif 3c > 0 → SLOW_WIN
  else → NO_EDGE
```

**`WinConditionRegistryService.record()` change:**
- `FAST_WIN` and `TARGET_HIT` = win (count toward win_count)
- `STOP_OUT` = loss
- `SLOW_WIN` = win (but penalized in future sizing — Phase 42)
- `NO_EDGE` = loss

### Files to modify

| File | Change |
|---|---|
| `history-service/schema.sql` | Add outcome_label, outcome_1c, outcome_3c columns |
| `history-service/.../model/DecisionHistory.java` | Add fields |
| `history-service/.../service/HistoryService.java` | Multi-horizon resolution logic |
| `history-service/.../service/WinConditionRegistryService.java` | Use outcomeLabel for win/loss classification |
| `history-service/.../dto/SnapshotDecisionDTO.java` | Add outcomeLabel for UI display |

---

## Phase 42 — PositionSizingEngine

### Problem
The system generates high-quality signals but output is binary: trade or no trade. A prop desk never sizes the same on every trade — position sizing is where edge compounds.

### Goal
Add `PositionSizingDecision` to every FinalDecision. Trade-service (operator layer) uses it to set lot size. No automation — human executes, but with computed guidance.

### Input Factors

| Factor | Source |
|---|---|
| confidence | DecisionContext (from AIStrategistService) |
| regime | DecisionContext |
| directionalBias strength | DirectionalBias enum |
| divergenceFlag | DecisionContext |
| edgeCondition winRate | WinConditionRegistry (Phase 38a data) |
| dailyPnL | trade-service daily tracker |
| consecutiveLosses | trade-service daily tracker |
| microSession | TradingSession (Phase 40) |

### Sizing Formula

```
Base risk = 1% capital per trade

Confidence multiplier   = confidence                     (0.65–0.90 → 0.65x–0.90x)
Regime multiplier       = VOLATILE:1.0 TRENDING:0.9 RANGING:0.6 CALM:0.3
Divergence multiplier   = divergenceFlag ? 0.6 : 1.0
Edge multiplier         = winRate > 0.60 → 1.2 | winRate < 0.52 → 0.5 | else → 1.0
MicroSession multiplier = OPENING_PHASE_1:0.5 OPENING_PHASE_2:1.0 OPENING_PHASE_3:0.7

positionRisk = R × confidence × regime × divergence × edge × microSession

lotSize = floor(positionRisk / stopLossPercent)
```

### Implementation

**New class:**
```java
// common-lib/risk/PositionSizingEngine.java
public record PositionSizingDecision(
    double riskPercent,
    double lotMultiplier,
    String reasoning
) {}

public class PositionSizingEngine {
    public PositionSizingDecision compute(DecisionContext ctx, double edgeWinRate) { ... }
}
```

**Wire into:**
- `DecisionPipelineEngine.buildFinalDecision()` — compute and attach to FinalDecision
- Add `positionRisk` and `lotMultiplier` fields to `FinalDecision`
- `trade-service` displays sizing guidance to operator (read-only, no auto-execution)

### Files to create / modify

| File | Change |
|---|---|
| NEW `common-lib/.../risk/PositionSizingEngine.java` | Formula implementation |
| NEW `common-lib/.../risk/PositionSizingDecision.java` | Output record |
| MODIFY `common-lib/.../model/FinalDecision.java` | Add positionRisk, lotMultiplier fields (v10) |
| MODIFY `agent-orchestrator/.../pipeline/DecisionPipelineEngine.java` | Call PositionSizingEngine, inject result |
| MODIFY `history-service/.../model/DecisionHistory.java` | Add positionRisk column |
| MODIFY `ui/.../components/SnapshotCard.jsx` | Show lot guidance badge |
| MODIFY `ui/.../components/DetailModal.jsx` | Show full sizing breakdown |

---

## Phase 43 — DailyRiskGovernor

### Problem
Signal quality is good. But scalping systems fail not due to bad signals — they fail due to:
- 2 losses → revenge trading
- 3 wins → overconfidence / oversizing
- Volatility collapse mid-session (CALM after VOLATILE)

This cannot be solved by signal gates alone. It needs a daily risk envelope.

### Goal
`DailyRiskGovernor` tracks intraday state and issues kill switches and size reductions. Enforced at `DecisionPipelineEngine` entry — before any gate runs.

### Rules

| Condition | Action |
|---|---|
| dailyLoss ≥ 1.5R | STOP TRADING — force all signals to HOLD for rest of session |
| dailyProfit ≥ 3.0R | LOCK GAINS — force HOLD (protect profits, no more trades today) |
| consecutiveLosses ≥ 2 | REDUCE SIZE — positionMultiplier × 0.5 for next trade |
| consecutiveLosses ≥ 3 | STOP TRADING — force HOLD for rest of session |
| Regime collapses VOLATILE → CALM mid-session | STOP TRADING — no edge in calm |

### State Storage
`trade-service` already tracks intraday state (in-memory, accepted loss on restart).
Extend its `RiskStateMap` with:
- `dailyPnLPercent`
- `consecutiveLosses`
- `dailyTradeCount`
- `sessionKillSwitch` (boolean, reset at market open)

### Implementation

**New component:**
```java
// agent-orchestrator/.../pipeline/DailyRiskGovernor.java
@Component
public class DailyRiskGovernor {
    // polls trade-service for daily state
    // returns GovernorDecision: ALLOW | REDUCE_SIZE | HALT
}
```

**Wire into `DecisionPipelineEngine`** as first check — before all gates:
```java
GovernorDecision gov = dailyRiskGovernor.evaluate(context);
if (gov == HALT) return FinalDecision.hold("DailyRiskGovernor: session halted");
if (gov == REDUCE_SIZE) context = context.withSizeMultiplier(0.5);
```

### Files to create / modify

| File | Change |
|---|---|
| NEW `agent-orchestrator/.../pipeline/DailyRiskGovernor.java` | Governor logic, polls trade-service |
| MODIFY `trade-service/.../service/RiskStateService.java` | Expose dailyPnL, consecutiveLosses, killSwitch |
| MODIFY `trade-service/.../controller/TradeController.java` | Add GET /daily-risk-state endpoint |
| MODIFY `agent-orchestrator/.../pipeline/DecisionPipelineEngine.java` | Governor as gate-0 (before all others) |
| MODIFY `common-lib/.../model/DecisionContext.java` | Add sizeMultiplier field + withSizeMultiplier() |
| MODIFY `history-service/schema.sql` | Add daily_trade_count, consecutive_losses to trade_sessions |

**Critical:** This phase is required before ANY live capital deployment. Non-negotiable.

---

## Phase 44 — Peak-Mode Latency Optimization

### Problem
During OPENING_BURST in VOLATILE regime, every millisecond matters. Current pipeline includes:
- 4 parallel agents (~100ms)
- AI call — Sonnet (~1500ms) or Haiku (~800ms)
- Persistence
- Notification

For OPENING_PHASE_2 scalping, the AI reasoning essay is counterproductive. Speed > depth.

### Goal
Detect PEAK_MODE conditions and activate a fast-path: force Haiku, short prompt, 1200ms AI timeout.

### Peak Mode Conditions
All must be true:
- Session ∈ {OPENING_PHASE_1, OPENING_PHASE_2}
- Regime = VOLATILE
- No divergence streak (divergenceStreak = 0)
- Confidence slope rising (current confidence > previous confidence)

### Changes

**In `ModelSelector.java`:**
```java
// Add PEAK_MODE detection:
if (isPeakMode(context)) return "claude-haiku-4-5-20251001";
```

**In `AIStrategistService.buildPrompt()`:**
```java
// In peak mode: use short prompt variant
// Skip detailed reasoning sections
// Max tokens: 150 (vs current 300)
```

**AI timeout:**
```java
// In AIStrategistService (WebClient call):
.timeout(isPeakMode ? Duration.ofMillis(1200) : Duration.ofMillis(4000))
// On timeout: fallback to consensus (already implemented)
```

**New field in DecisionContext:**
```java
boolean peakMode  // set by OrchestratorService before AI call
```

### Files to modify

| File | Change |
|---|---|
| `common-lib/.../model/DecisionContext.java` | Add peakMode boolean |
| `agent-orchestrator/.../ai/ModelSelector.java` | Peak mode → force Haiku |
| `agent-orchestrator/.../ai/AIStrategistService.java` | Short prompt in peak mode, 1200ms timeout |
| `agent-orchestrator/.../service/OrchestratorService.java` | Set peakMode on context before AI call |

### Expected Impact
- Peak mode AI latency: ~800ms (from ~1500ms)
- Decision cycle during OPENING_PHASE_2: < 1000ms end-to-end

---

## Phase 45 — Bayesian Edge Estimator (38b Upgrade)

### Problem
Phase 38b uses static thresholding: `winRate < 0.52 → WATCH`. This is brittle:
- 20 samples at 52% has wide confidence interval
- A win rate of 0.63 on 25 samples is more reliable than 0.55 on 100 samples (sample variance)
- Regime shifts can make historical win rate stale

### Goal
Replace binary threshold with Bayesian posterior probability:
`P(true win rate > 0.52 | observed data)`

This is statistically honest and regime-shift resilient.

### Formula

Using Beta distribution (conjugate prior for Bernoulli):
```
prior: Beta(1, 1)  — uninformative (no initial assumption)
posterior: Beta(winCount + 1, lossCount + 1)

posteriorProbability = P(theta > 0.52) from Beta CDF

if posteriorProbability < 0.70 → WATCH  (not confident enough)
if posteriorProbability ≥ 0.70 → ALLOW
```

### Implementation

**New class:**
```java
// common-lib/risk/BayesianEdgeEstimator.java
public class BayesianEdgeEstimator {
    // Uses regularized incomplete beta function
    // Returns P(winRate > threshold)
    public double posteriorProbability(int winCount, int lossCount, double threshold) { ... }
}
```

**Wire into `WinConditionRegistryService`** (Phase 38b gate):
```java
// Replace:
if (condition.getWinRate() < 0.52) → WATCH

// With:
double confidence = estimator.posteriorProbability(winCount, lossCount, 0.52);
if (confidence < 0.70) → WATCH
```

### Files to create / modify

| File | Change |
|---|---|
| NEW `common-lib/.../risk/BayesianEdgeEstimator.java` | Beta distribution CDF computation |
| MODIFY `history-service/.../service/WinConditionRegistryService.java` | Replace static threshold with Bayesian check |
| MODIFY `history-service/.../repository/EdgeConditionRepository.java` | Return winCount + lossCount raw (not just winRate) |

**Prerequisite:** Phase 38b must be live first. This is a 38b upgrade, not a standalone phase.

---

## Technical Debt (from Architect Review)

These are not phases — they are hardening items. Implement during any phase that touches the relevant file.

### TD-1: Idempotency on history save
**Problem:** Duplicate traceId = duplicate rows on replay spikes or retries.
**Fix:** Add `UNIQUE (trace_id)` constraint to `decision_history`. Use `INSERT ... ON CONFLICT DO NOTHING` in `HistoryService.save()`.
**File:** `history-service/schema.sql`, `HistoryService.java`

### TD-2: Cap recentClosingPrices
**Problem:** `recentClosingPrices` size depends on market data response. Unbounded list = memory risk.
**Fix:** Cap at 50 in `OrchestratorService` during context assembly.
**File:** `agent-orchestrator/.../service/OrchestratorService.java`

### TD-3: Continuous confidence (ExpectedEdge)
**Problem:** `confidence ≥ 0.65` is binary. Two values of 0.65 and 0.88 are treated identically.
**Fix:** Use `expectedEdge = confidence × edgeWinRate` for position sizing (Phase 42 already implements this).
**File:** Addressed by PositionSizingEngine in Phase 42.

### TD-4: Proportional divergence penalty
**Problem:** Current `confidence × 0.85` is a blunt flat cut.
**Fix:** `adjustedConfidence = confidence × (1 - divergenceMagnitude)` where `divergenceMagnitude ∈ [0.0, 0.3]`.
**File:** `agent-orchestrator/.../service/OrchestratorService.java` (divergencePenalty calculation)

---

## Mathematical Gate Analysis (Architect's Probability Review)

Current BUY gate pass probability (approximate, assuming independence):

| Gate | Estimated Pass Rate |
|---|---|
| Session ∈ {OB, PH} | ~30% of all 5-min candles |
| Regime ∈ {VOLATILE, TRENDING} | ~40% |
| Bias ∈ {BULLISH, STRONG_BULLISH} | ~45% |
| Confidence ≥ 0.65 | ~35% |
| !divergenceFlag | ~75% |

**Combined:** 0.30 × 0.40 × 0.45 × 0.35 × 0.75 ≈ **1.4% of cycles produce BUY**

**This is correct.** Selective scalping, not high-frequency noise. The architect confirms this as the right design posture.

After Phase 40 (micro-session segmentation), OPENING_PHASE_2 concentration means fewer trades but higher precision per trade.

---

## Recommended Execution Order

```
Phase 38b  ← waiting for Angel One data (BLOCKED)
    ↓
Phase 39   ← DecisionPipelineEngine refactor (prerequisite for clean Phase 40+ implementation)
    ↓
Phase 40   ← Micro-session segmentation (OPENING_PHASE_1/2/3)
    ↓
Phase 43   ← DailyRiskGovernor (REQUIRED before any live capital)
    ↓
Phase 41   ← Multi-horizon outcome resolution (improve learning loop quality)
    ↓
Phase 42   ← PositionSizingEngine (compound the edge)
    ↓
Phase 44   ← Peak-mode latency optimization (performance polish)
    ↓
Phase 45   ← Bayesian edge estimator (38b upgrade, needs 38b live first)
```

**Paper trading gate:** Minimum 2 weeks live paper trading after Phase 43 before any capital.

---

## What This Achieves

| Capability | Before | After (all phases) |
|---|---|---|
| Session granularity | 4 sessions | 6 sessions (OPENING sub-divided) |
| Position sizing | Binary (in/out) | Risk-adjusted per trade |
| Outcome learning | Next-candle P&L | Multi-horizon labeled outcomes |
| Daily risk control | None | Governor with kill switch |
| Peak latency | ~1500ms | < 1000ms in PHASE_2 |
| Edge confidence | Static threshold | Bayesian posterior |
| Orchestrator complexity | Monolith | Pipeline engine (maintainable) |

The system evolves from:
> "A disciplined signal generator with discipline gates"

To:
> "A full trade optimizer: signal + size + timing + daily risk envelope + statistical edge validation"

---

*Document authored from architect review dated 2026-02-28. Update after each phase completes.*
