# Agent Platform — Session Handoff Document
> **Purpose:** Single file to restore full context after conversation loss.
> **How to use:** Give this file to Claude at start of any new session. Claude reads only this file and is immediately ready to build — no codebase exploration needed.
> **Last updated:** 2026-02-28 (after Phase 38a)
> **Update rule:** Update this file at the end of every session before closing.

---

## 1. SYSTEM IDENTITY

Multi-agent AI trading intelligence platform for NIFTY50 scalping (Indian equity market).
Java 21 + Spring Boot 3 reactive (WebFlux + R2DBC) + React 18 UI + Anthropic Claude API + PostgreSQL.

**What it does:**
Market data → 4 specialist agents (parallel) → AI synthesis (Claude) → 6 hard discipline gates → FinalDecision → persist + notify + learn.

**Core philosophy:**
- AI (Claude) is the primary decision authority
- Consensus (weighted agent votes) is a safety guardrail only
- Every BUY/SELL must pass ALL discipline gates or is forced to WATCH
- System learns from real P&L outcomes (live trades only — replay excluded)

---

## 2. INFRASTRUCTURE — NEVER CHANGE THESE

```
Single stack: docker-compose.dev.yml (ONLY active file — docker-compose.yml is archive)
Network: agent-net
Volume: pgdata
Container postgres: agent-postgres
```

| Service | Host Port | Container name |
|---|---|---|
| market-data-service | 8080 | market-data-service |
| agent-orchestrator | 8081 | agent-orchestrator |
| scheduler-service | 8082 | scheduler-service |
| analysis-engine | 8083 | analysis-engine |
| notification-service | 8084 | notification-service |
| history-service | 8085 | history-service |
| trade-service | 8086 | trade-service |
| ui (Vite) | 5173 | — |
| postgres | 5432 | agent-postgres |

**Rebuild command:**
```bash
docker compose -f docker-compose.dev.yml up -d --build
```

**Manual trigger:**
```bash
curl -X POST http://localhost:8081/api/v1/orchestrate/trigger \
  -H "Content-Type: application/json" \
  -d '{"symbol":"NIFTY50","triggeredAt":"2026-02-28T10:00:00Z","traceId":"manual-001"}'
```

---

## 3. CURRENT STATE — WHAT IS BUILT (Phase 38a Complete)

### Phase Completion Status
```
Phase 1–32:   ✅ Foundation, observability, replay, feedback loop
Phase 33:     ✅ Directional Bias Layer (TrendAgent 5-vote, tradeDirection LONG/SHORT/FLAT)
Phase 34:     ✅ Infra consolidation (single stack, prod ports, replay isolation)
Phase 35:     ✅ Authority Chain + Hard Gates (SessionGate, BiasGate, DivergencePenalty, MultiFilter)
Phase 36:     ✅ TradeEligibilityGuard (regime+bias hard gate)
Phase 37:     ✅ AI prompt SELL bias fix (symmetric BUY/SELL instruction)
Phase 38a:    ✅ WinConditionRegistry PASSIVE (edge_conditions table, LIVE_AI only)
Phase 38b:    ⏳ BLOCKED — needs Angel One API + 6-month data + ≥20 samples per condition
Phase 39–45:  📋 PLANNED — see Section 6
```

### Current Decision Pipeline (in OrchestratorService.buildDecision())
```
Gate-0: DivergenceGuard   — tracks AI vs consensus divergence streak
Gate-1: AuthorityChain    — AI signal is authority, consensus only guardrail
Gate-2: SessionGate       — OPENING_BURST/POWER_HOUR = active, MIDDAY = WATCH, OFF_HOURS = HOLD
Gate-3: BiasGate          — BUY requires BULLISH/STRONG_BULLISH, SELL requires BEARISH/STRONG_BEARISH
Gate-4: DivergencePenalty — confidence × 0.85, floor 0.50
Gate-5: MultiFilter       — confidence ≥ 0.50 floor
Gate-6: EligibilityGuard  — regime+bias+confidence+!divergenceFlag hard eligibility (Phase 36)
        → FinalDecision (v9, 24 fields)
```

### Trading Sessions (IST gating — currently 4 sessions)
```
OPENING_BURST        09:15–10:00   BUY + SELL allowed (if all gates pass)
POWER_HOUR           15:00–15:30   BUY only
MIDDAY_CONSOLIDATION 10:00–15:00   WATCH/HOLD only
OFF_HOURS            outside above  HOLD only
```

### BUY gate (all must be true)
```
Session ∈ {OPENING_BURST, POWER_HOUR}
Regime ∈ {VOLATILE, TRENDING}
Bias ∈ {BULLISH, STRONG_BULLISH}
Confidence ≥ 0.65
!divergenceFlag
```

### SELL gate (all must be true)
```
Session = OPENING_BURST
Regime = VOLATILE
Bias ∈ {BEARISH, STRONG_BEARISH}
Confidence ≥ 0.65
!divergenceFlag
```

---

## 4. KEY DOMAIN OBJECTS — EXACT CURRENT STATE

### FinalDecision (v9 — 24 fields)
```java
// common-lib/src/main/java/com/agentplatform/common/model/FinalDecision.java
record FinalDecision(
    String symbol, String signal, double confidence, String reasoning,
    String orchestratorVersion, int decisionVersion, int agentCount,
    long decisionLatencyMs, double consensusScore, Map<String,Double> agentWeightSnapshot,
    List<Double> recentClosingPrices, String marketRegime, List<String> analysisInsights,
    double adaptiveAgentWeight, String tradingSession, String traceId,
    String entryPrice, String stopLoss, String targetPrice, int estimatedHoldMinutes,
    String decisionMode,
    String tradeDirection,   // LONG / SHORT / FLAT  (Phase 33)
    String directionalBias   // STRONG_BULLISH → STRONG_BEARISH (Phase 33)
)
// Factory: FinalDecision.v9(...)
```

### DecisionContext (19 fields)
```java
// common-lib/.../model/DecisionContext.java
// Key fields: symbol, prices, marketRegime, tradingSession, traceId,
//             aiStrategyDecision, consensusResult, divergenceStreak,
//             divergenceFlag, recentInsights, adaptiveWeights, directionalBias
// Copy-factories: withDirectionalBias(), withAiDecision(), etc.
```

### AIStrategyDecision (8 fields)
```java
// common-lib/.../model/AIStrategyDecision.java
record AIStrategyDecision(
    String finalSignal, double confidence, String reasoning,
    String entryPrice, String stopLoss, String targetPrice,
    int estimatedHoldMinutes, String tradeDirection
)
```

### DirectionalBias (enum)
```java
// common-lib/.../cognition/DirectionalBias.java
STRONG_BULLISH, BULLISH, NEUTRAL, BEARISH, STRONG_BEARISH
// Methods: isLongBias(), isShortBias()
```

### TradeDirection (enum)
```java
// common-lib/.../model/TradeDirection.java
LONG, SHORT, FLAT
```

---

## 5. KEY FILE PATHS

### common-lib
```
src/main/java/com/agentplatform/common/
  model/FinalDecision.java           ← v9 factory, 24 fields
  model/DecisionContext.java         ← 19 fields, copy-factories
  model/AIStrategyDecision.java      ← 8 fields
  model/TradingSession.java          ← enum (4 sessions currently)
  model/TradeDirection.java          ← LONG/SHORT/FLAT
  cognition/DirectionalBias.java     ← 5-value enum
  cognition/MarketRegime.java        ← TRENDING/RANGING/VOLATILE/CALM/UNKNOWN
  cognition/MarketRegimeClassifier.java
  cognition/TradingSessionClassifier.java  ← IST-aware classify()
```

### analysis-engine
```
src/main/java/com/agentplatform/analysis/agent/
  TrendAgent.java          ← 5-vote directional bias, metadata["directionalBias"]
  RiskAgent.java
  PortfolioAgent.java
  DisciplineCoach.java     ← calls Claude Haiku, skipped in replay
```

### agent-orchestrator
```
src/main/java/com/agentplatform/orchestrator/
  service/OrchestratorService.java   ← MAIN PIPELINE. Contains all 6 gates currently.
  ai/AIStrategistService.java        ← Claude API call, buildPrompt(), parseResponse(), fallback()
  ai/ModelSelector.java              ← regime-aware Haiku vs Sonnet selection
```

### history-service
```
src/main/java/com/agentplatform/history/
  service/HistoryService.java        ← save, resolveOutcomes, rescoreAgentsByOutcome
  service/WinConditionRegistryService.java  ← Phase 38a, records edge_conditions
  model/DecisionHistory.java
  model/EdgeCondition.java           ← Phase 38a
  repository/EdgeConditionRepository.java   ← Phase 38a, upsertCondition()
  dto/SnapshotDecisionDTO.java
src/main/resources/schema.sql        ← all 8 tables
```

### Database schema (8 tables)
```sql
decision_history          ← all decisions (LIVE_AI + REPLAY_CONSENSUS_ONLY)
agent_performance_snapshot
decision_metrics_projection
trade_sessions
replay_candles            ← Yahoo Finance NIFTY50 candles
portfolio_summary
risk_snapshots
edge_conditions           ← Phase 38a (LIVE_AI only, session+regime+bias+signal keyed)
```

### UI
```
ui/src/components/
  SnapshotCard.jsx         ← shows tradeDirection badge (↑ LONG / ↓ SHORT / — FLAT)
  DetailModal.jsx          ← shows tradeDirection + directionalBias
```

---

## 6. NEXT PHASES TO BUILD — IN ORDER

> Read `docs/future-evolution-plan.md` for full detail on each phase.
> This section is the actionable summary for Claude to start building immediately.

---

### PHASE 39 — DecisionPipelineEngine Extraction
**Why:** OrchestratorService is a cognitive monolith (regime + session + weights + AI + 6 gates + decision builder + events). As gate logic grows through Phase 40–43, this will become unmaintainable.
**What:** Extract gates 1–6 + buildFinalDecision() into a new `@Component DecisionPipelineEngine`.
**What stays in OrchestratorService:** Fetch data → call analysis-engine → build DecisionContext → call engine.evaluate() → publish events.
**Result:** Pure refactor. Zero behavioral change. No replay needed.

**New file:**
```
agent-orchestrator/src/main/java/com/agentplatform/orchestrator/pipeline/DecisionPipelineEngine.java
```

**Modify:**
```
agent-orchestrator/.../service/OrchestratorService.java  ← inject DecisionPipelineEngine, remove gate logic
```

---

### PHASE 40 — Micro-Session Segmentation
**Why:** OPENING_BURST (9:15–10:00) contains 3 behaviourally different market phases. Treating them identically causes Phase-1 noise to trade with Phase-2 rules. This is the single highest-impact change for scalping precision.
**What:** Split OPENING_BURST into 3 micro-sessions. Different confidence/bias/momentum rules per phase.

**New enum values in TradingSession:**
```java
OPENING_PHASE_1,   // 9:15–9:25  Price discovery. High noise, fake breakouts.
OPENING_PHASE_2,   // 9:25–9:40  Directional expansion. PRIME SCALPING WINDOW.
OPENING_PHASE_3,   // 9:40–10:00 Continuation or trap. Momentum weakening.
```

**Gate rules per phase:**
- PHASE_1: Confidence ≥ 0.70, STRONG bias only, block if divergenceFlag
- PHASE_2: Current rules (confidence ≥ 0.65, BULLISH ok)
- PHASE_3: Block if momentumState = WEAKENING, block if divergenceStreak ≥ 1

**New field needed:** `momentumState` (RISING / WEAKENING / FALLING)
- Computed in TrendAgent (last 3 candles)
- Stored in AnalysisResult.metadata["momentumState"]
- Extracted in OrchestratorService, added to DecisionContext

**Files to touch:**
```
common-lib/.../model/TradingSession.java              ← add 3 new enum values
common-lib/.../cognition/TradingSessionClassifier.java ← sub-classify by minute
common-lib/.../model/DecisionContext.java             ← add momentumState field
analysis-engine/.../agent/TrendAgent.java             ← compute + store momentumState
agent-orchestrator/.../pipeline/DecisionPipelineEngine.java  ← phase-differentiated gates
agent-orchestrator/.../service/OrchestratorService.java       ← extract + inject momentumState
```

---

### PHASE 43 — DailyRiskGovernor ⚠️ REQUIRED BEFORE LIVE CAPITAL
**Why:** Scalping systems fail not due to bad signals — they fail due to revenge trading after losses and overconfidence after wins. No gate logic prevents intraday behavioural spirals.
**What:** Governor checks daily intraday state BEFORE all gates. If kill conditions met → force HOLD for rest of session.

**Kill switch rules:**
```
dailyLoss ≥ 1.5R               → HALT (stop all trades rest of session)
dailyProfit ≥ 3.0R             → HALT (lock gains)
consecutiveLosses ≥ 3          → HALT
consecutiveLosses = 2          → REDUCE_SIZE (×0.5 on next trade only)
Regime shifts VOLATILE → CALM  → HALT (no scalping edge in calm)
```

**New files:**
```
agent-orchestrator/.../pipeline/DailyRiskGovernor.java   ← @Component, polls trade-service
common-lib/.../model/GovernorDecision.java               ← enum: ALLOW / REDUCE_SIZE / HALT
```

**Modify:**
```
trade-service/.../service/RiskStateService.java          ← expose dailyPnL, consecutiveLosses
trade-service/.../controller/TradeController.java        ← GET /daily-risk-state
agent-orchestrator/.../pipeline/DecisionPipelineEngine.java  ← gate-0 before all others
common-lib/.../model/DecisionContext.java               ← add sizeMultiplier field
history-service/schema.sql                              ← consecutive_losses in trade_sessions
```

---

### PHASE 41 — Multi-Horizon Outcome Resolution
**Why:** Next-candle P&L is too blunt. OPENING_PHASE_1 fake breakouts recover by candle 3. Stop-hit is more realistic exit than time-based.
**What:** Resolve each trade under 1-candle, 3-candle, stop-hit, and target-hit horizons. Label each outcome.

**Outcome labels:** FAST_WIN / SLOW_WIN / STOP_OUT / TARGET_HIT / NO_EDGE

**New DB columns in decision_history:**
```sql
outcome_label VARCHAR(20)
outcome_1c    DOUBLE PRECISION
outcome_3c    DOUBLE PRECISION
```

**Files to touch:**
```
history-service/schema.sql
history-service/.../model/DecisionHistory.java
history-service/.../service/HistoryService.java          ← multi-horizon resolution logic
history-service/.../service/WinConditionRegistryService.java  ← use outcomeLabel for win/loss
history-service/.../dto/SnapshotDecisionDTO.java         ← expose outcomeLabel to UI
```

---

### PHASE 42 — PositionSizingEngine
**Why:** Binary in/out signals leave edge on the table. A high-confidence PHASE_2 trade in VOLATILE regime with proven edge deserves bigger size than a borderline PHASE_1 trade.
**What:** Compute risk-adjusted position size for every FinalDecision. Operator uses this as guidance.

**Formula (5-factor):**
```
Base risk = 1% capital
× confidence factor     (0.65–0.90)
× regime factor         (VOLATILE:1.0 TRENDING:0.9 RANGING:0.6 CALM:0.3)
× divergence factor     (divergenceFlag ? 0.6 : 1.0)
× edge factor           (winRate >0.60 → 1.2 | <0.52 → 0.5 | else 1.0)
× microSession factor   (PHASE_1:0.5 PHASE_2:1.0 PHASE_3:0.7)
= positionRisk % → lotMultiplier
```

**New files:**
```
common-lib/src/main/java/com/agentplatform/common/risk/PositionSizingEngine.java
common-lib/src/main/java/com/agentplatform/common/risk/PositionSizingDecision.java
```

**Modify:**
```
common-lib/.../model/FinalDecision.java                  ← add positionRisk + lotMultiplier (v10)
agent-orchestrator/.../pipeline/DecisionPipelineEngine.java  ← call engine, attach to FinalDecision
history-service/.../model/DecisionHistory.java           ← add position_risk column
ui/src/components/SnapshotCard.jsx                       ← show lot guidance badge
ui/src/components/DetailModal.jsx                        ← show full sizing breakdown
```

---

### PHASE 44 — Peak-Mode Latency Optimization
**Why:** OPENING_PHASE_2 is time-critical. Current Sonnet call = ~1500ms. We need decision < 1000ms.
**What:** Detect PEAK_MODE (PHASE_2 + VOLATILE + no divergence streak) → force Haiku + short prompt + 1200ms timeout.

**Files to touch:**
```
common-lib/.../model/DecisionContext.java                ← add peakMode boolean
agent-orchestrator/.../ai/ModelSelector.java             ← peakMode → Haiku
agent-orchestrator/.../ai/AIStrategistService.java       ← short prompt + 1200ms timeout in peak
agent-orchestrator/.../service/OrchestratorService.java  ← set peakMode before AI call
```

---

### PHASE 45 — Bayesian Edge Estimator (Phase 38b Upgrade)
**Why:** Static `winRate ≥ 0.52` threshold treats 20 samples and 200 samples identically. Wrong.
**What:** Replace with Beta distribution posterior: `P(true winRate > 0.52 | observed data)`. Gate passes at posterior ≥ 0.70.
**Prerequisite:** Phase 38b must be live first (needs Angel One data, ≥20 samples).

**New file:**
```
common-lib/src/main/java/com/agentplatform/common/risk/BayesianEdgeEstimator.java
```

**Modify:**
```
history-service/.../service/WinConditionRegistryService.java  ← replace static threshold
```

---

## 7. TECHNICAL DEBT (Fix During Any Touching Phase)

| Item | File | Fix |
|---|---|---|
| TD-1: Duplicate rows on trace_id | history-service/schema.sql + HistoryService | UNIQUE(trace_id) + ON CONFLICT DO NOTHING |
| TD-2: Unbounded recentClosingPrices | OrchestratorService (context assembly) | Cap at 50 entries |
| TD-3: Blunt divergence penalty | OrchestratorService (divergencePenalty block) | Use `confidence × (1 - divergenceMagnitude)` where magnitude ∈ [0, 0.3] |

---

## 8. HARD RULES — NEVER BREAK

1. **AI overrides downward only** — WATCH/HOLD. Never upward. Consensus cannot promote a signal.
2. **REPLAY_CONSENSUS_ONLY rows never enter WinConditionRegistry** — filter in WinConditionRegistryService.
3. **Phase 38b gate only after ≥20 samples per condition** — no exceptions.
4. **Phase 43 (DailyRiskGovernor) BEFORE any live capital** — non-negotiable.
5. **Paper trade minimum 2 weeks after Phase 43** — before any real money.
6. **No phase skipping** — each phase builds on previous. Do not implement Phase 42 before Phase 39.
7. **Replay data cannot train the learning loop** — decision_mode = REPLAY_CONSENSUS_ONLY is always excluded.
8. **Single compose file** — docker-compose.dev.yml is the only active file. Never create a second.

---

## 9. REPLAY WORKFLOW (when needed after a phase)

```bash
# 1. Clear all data
docker exec agent-postgres psql -U agent -d agent_platform \
  -c "TRUNCATE decision_history, agent_performance_snapshot, decision_metrics_projection, trade_sessions RESTART IDENTITY;"

# 2. Reset replay cursor (clears replay_candles too)
curl -X POST "http://localhost:8080/api/v1/market-data/replay/reset?symbol=NIFTY50"

# 3. Reload candles (always after reset)
python3 scripts/load_nifty_candles.py

# 4. Start market-data-service in replay profile
SPRING_PROFILES_ACTIVE=historical-replay \
  docker compose -f docker-compose.dev.yml up -d market-data-service

# 5. Start replay
curl -X POST "http://localhost:8080/api/v1/market-data/replay/start?symbol=NIFTY50"

# 6. Monitor (~25 min for 2,979 candles)
curl "http://localhost:8080/api/v1/market-data/replay/status?symbol=NIFTY50"
```

**Which phases need replay:** Phase 40 (new session gates change trade counts), Phase 41 (new outcome labels), Phase 42 (sizing visible in history). Phases 39, 43, 44 do NOT need replay (refactor / runtime-only).

---

## 10. WHAT THIS SYSTEM WILL ACHIEVE (After All Phases)

| Capability | Now (Phase 38a) | After Phases 39–45 |
|---|---|---|
| Session granularity | 4 sessions | 6 sessions (OPENING sub-divided) |
| Gate execution | Monolith in OrchestratorService | Clean DecisionPipelineEngine |
| Position sizing | Binary (in/out) | Risk-adjusted 5-factor formula per trade |
| Outcome learning | Next-candle P&L only | Multi-horizon: 1c / 3c / stop / target |
| Daily risk control | None | Kill switch at -1.5R, lock at +3R |
| Peak-hour latency | ~1500ms | < 1000ms in OPENING_PHASE_2 |
| Edge confidence | Static 52% threshold | Bayesian posterior ≥ 70% |

**The system evolves from:**
Signal generator with discipline gates

**To:**
Full trade optimizer: signal + time precision + risk-adjusted size + daily risk envelope + statistical edge validation

---

*End of handoff document. Update this file at the end of every session.*
