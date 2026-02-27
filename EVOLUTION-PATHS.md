# Agent Platform — Architectural Evolution Paths

**Classification:** Internal Engineering Reference
**Audience:** Senior Backend Engineers, Staff Engineers, Principal Architects
**Source of truth:** Codebase at `agent-platform/` — verified against all 9 modules
**Last updated:** 2026-02-26

---

## Table of Contents

1. [Architectural Evolution Timeline](#1-architectural-evolution-timeline)
2. [Architecture Consistency Review](#2-architecture-consistency-review)
3. [Control Plane vs Decision Plane Analysis](#3-control-plane-vs-decision-plane-analysis)
4. [Scalability Capability Assessment](#4-scalability-capability-assessment)
5. [Future Evolution Paths](#5-future-evolution-paths)
6. [Architecture Risk Radar](#6-architecture-risk-radar)

---

## 1. Architectural Evolution Timeline

### Phase 1 — Multi-Agent Consensus Foundation

The system began as a straightforward multi-agent analysis platform. Four agents (`TrendAgent`, `RiskAgent`, `PortfolioAgent`, `DisciplineCoach`) each receive a `Context` record containing market prices and metadata, and independently produce an `AnalysisResult` with a signal (`BUY`/`SELL`/`HOLD`/`WATCH`) and a confidence score.

`DefaultWeightedConsensusStrategy` aggregates these signals using a fixed weight of `1.0` per agent. The consensus maps signals to numeric scores (`BUY=+1.0`, `SELL=-1.0`, `HOLD=0.0`, `WATCH=+0.5`), computes a weighted average, and applies thresholds to derive a final signal. This was the original decision authority.

At this phase, `OrchestratorService` was a linear pipeline:

```
MarketDataEvent → fetch prices → call analysis-engine → consensus → FinalDecision → persist + notify
```

`FinalDecision` had 7 record components. `scheduler-service` ran a fixed `@Scheduled(cron = "0 */5 * * * *")` loop.

**Responsibility model:** Agents generate signals. Consensus decides. Orchestrator coordinates. Scheduler triggers.

---

### Phase 2 — Observability and Versioning (v2)

`FinalDecision` grew from 7 to 11 components: `decisionVersion`, `orchestratorVersion`, `agentCount`, `decisionLatencyMs`. The `DecisionFlowLogger` was introduced with five lifecycle stages (`TRIGGER_RECEIVED` through `EVENTS_DISPATCHED`). `TraceContextUtil` solved the MDC-vs-Reactor-Context problem by bridging `traceId` from Reactor Context into MDC only for the duration of each log call.

**Responsibility shift:** None. Pure observability layer — additive, no behavioral change.

---

### Phase 3 — Consensus Metadata (v3)

`FinalDecision` grew to 13 components: `consensusScore` and `agentWeightSnapshot`. The `ConsensusResult` record was introduced to carry the equal-weight snapshot alongside the final signal. `ConsensusIntegrationGuard` was added as a null/empty safety wrapper around `ConsensusEngine.compute()`.

**Responsibility shift:** Consensus now exposes its internal state (weights, normalized confidence) as observable metadata rather than hiding it behind a single signal.

---

### Phase 4 — Adaptive Performance Feedback Loop (v4)

This is where the system's intelligence architecture began to diverge from simple voting.

New types: `AgentPerformanceModel`, `AgentFeedback`, `AgentScoreCalculator`.
New services: `PerformanceWeightAdapter`, `AgentFeedbackAdapter`.
New endpoints: `GET /agent-performance`, `GET /agent-feedback` on history-service.

`AgentScoreCalculator` computes per-agent adaptive weights using a three-layer formula:

- **Performance layer:** `(historicalAccuracyScore * 0.5) - (latencyWeight * 0.2)`
- **Feedback layer:** `(winRate * 0.4) + (avgConfidence * 0.3) - (normalizedLatency * 0.2)`
- Final weight clamped to `[0.1, 2.0]`

`FinalDecision` grew to 14 components with `adaptiveAgentWeights`.

**Critical architectural decision:** These weights are computed and **stored** but **not consumed** by `DefaultWeightedConsensusStrategy`, which continues to use equal weights. This is intentional staging — seed the feedback loop before trusting it.

**Responsibility shift:** `history-service` evolved from a pure persistence layer into a **feedback data provider**. `OrchestratorService` gained two new adapter dependencies and a computational step before `buildDecision`.

---

### Phase 5 — Market Regime Classification (v5)

New types: `MarketRegime` enum (`TRENDING`, `RANGING`, `VOLATILE`, `CALM`, `UNKNOWN`), `MarketRegimeClassifier`.

`MarketRegimeClassifier.classify(Context)` is a pure stateless function using standard deviation and SMA crossover thresholds: `stdDev > 7 -> VOLATILE`, `price > SMA50 && SMA20 -> TRENDING`, `stdDev < 3 -> CALM`, else `RANGING`.

`AgentScoreCalculator` gained a 4th argument (`MarketRegime`) and a regime boost: agents whose name contains `"trend"` get `+0.20` in TRENDING markets, `"risk"` gets `+0.20` in VOLATILE, `"portfolio"` gets `+0.15` in RANGING.

`FinalDecision` grew to 15 components with `marketRegime`.

**Responsibility shift:** `OrchestratorService` now performs regime classification synchronously inside a `doOnNext` after market data fetch, capturing the result in a `MarketRegime[] regime = {UNKNOWN}` array for downstream use. The orchestrator took on a classification responsibility that lives in `common-lib` as pure logic.

---

### Phase 6 — AI Strategist Layer (v6)

This is the most significant architectural evolution. A new `AIStrategistService` in `agent-orchestrator` calls the Anthropic Claude API reactively (no `.block()`) to synthesize all agent signals, adaptive weights, and market regime into a primary strategic recommendation.

New types: `AIStrategyDecision` record (`finalSignal`, `confidence`, `reasoning`).
New service: `AIStrategistService` in package `com.agentplatform.orchestrator.ai`.
New utility: `ModelSelector` — regime-aware Claude model selection.
New log stages: `AI_MODEL_SELECTED`, `AI_STRATEGY_EVALUATED`.

`FinalDecision` grew to 16 components with `aiReasoning`.

**The fundamental responsibility inversion:** Before Phase 6, `DefaultWeightedConsensusStrategy` was the decision authority. After Phase 6, `AIStrategistService` is the primary decision authority. The consensus engine now runs as a **safety guardrail** — its `normalizedConfidence` is stored as `consensusScore` for observability and divergence tracking, but its `finalSignal` is no longer used as the output.

In `OrchestratorService.buildDecision()`:

```java
// Before Phase 6:
consensus.finalSignal()          -> FinalDecision.finalSignal
avgConfidence                    -> FinalDecision.confidenceScore

// After Phase 6:
aiDecision.finalSignal()         -> FinalDecision.finalSignal
aiDecision.confidence()          -> FinalDecision.confidenceScore
consensus.normalizedConfidence() -> FinalDecision.consensusScore  // guardrail metric only
```

**Regime-aware model selection:** `ModelSelector` maps market regime to the optimal Claude model. VOLATILE → `claude-haiku-4-5-20251001` (cheap/fast — decisions are short-lived). All others → `claude-sonnet-4-6` (deeper reasoning justifies cost). `ModelSelector.resolveLabel()` provides human-readable labels (`haiku-fast` / `sonnet-deep`) for observability and UI.

**Short reasoning mode:** During VOLATILE regimes, the prompt instructs the model to respond with only one short sentence of reasoning — minimizing token cost and latency for decisions that will be superseded in 30 seconds.

**Responsibility shift:** Decision authority moved from a deterministic algorithm (`DefaultWeightedConsensusStrategy`) to an LLM-backed intelligence service (`AIStrategistService`). The orchestrator now manages two AI integration points — `DisciplineCoach` in analysis-engine (via HTTP, blocking) and `AIStrategistService` in agent-orchestrator (direct, reactive).

---

### Phase 7 — Adaptive Tempo Controller

`scheduler-service` evolved from a fixed-cron trigger into a regime-aware adaptive scheduler.

New types: `AdaptiveTempoStrategy` (pure `MarketRegime -> Duration` mapping), `HistoryClient` (reactive WebClient to `GET /latest-regime`).
New endpoint: `GET /api/v1/history/latest-regime?symbol={symbol}` on history-service.

`MarketDataScheduler` replaced `@Scheduled(cron)` with `@PostConstruct startAdaptiveScheduling()`. Each symbol runs an independent reactive loop:

```
Mono.delay(interval) -> triggerOrchestration() -> fetchLatestRegime() -> compute next interval -> recurse
```

Regime-to-interval mapping:

| Regime | Interval |
|---|---|
| VOLATILE | 30 seconds |
| TRENDING | 2 minutes |
| RANGING | 5 minutes |
| CALM | 10 minutes |
| UNKNOWN | 5 minutes (fallback) |

**Responsibility shift:** The scheduler evolved from a dumb cron into a **temporal control plane** that reads decision outcomes (regime) from the decision plane (via history-service) and adjusts its own behavior. This creates a closed feedback loop: decisions influence future trigger frequency.

---

### Phase 8 — Divergence Awareness (v7)

A new observability dimension: tracking when the AI strategist and consensus engine disagree.

New field: `Boolean divergenceFlag` added to `FinalDecision` (v7, 17th component).
New column: `divergence_flag BOOLEAN` in `decision_history` schema.

Computed in `OrchestratorService.buildDecision()`:
```java
boolean divergenceFlag = !aiDecision.finalSignal().equals(consensus.finalSignal());
```

The flag is persisted, logged, and surfaced in the UI (AI Diverging/Aligned badge, guardrail warning indicator). It does not trigger any automatic behavior — pure observability enabling future evolution toward divergence-triggered safety overrides.

**Responsibility shift:** The orchestrator gained a new quality: **self-awareness of decision disagreement**. The system can now track and analyze patterns in AI-consensus divergence across history.

---

### Phase 9 — Persistent Storage (PostgreSQL R2DBC)

`history-service` migrated from H2 in-memory to PostgreSQL via R2DBC.

Schema uses PostgreSQL-specific types: `BIGSERIAL`, `DOUBLE PRECISION`. The DDL (`CREATE TABLE IF NOT EXISTS`) works unchanged. Zero Java code changes — R2DBC's `ReactiveCrudRepository` abstracts the driver.

**Impact:** The feedback loop's effectiveness is no longer bounded by container uptime. Adaptive weights, agent performance history, and decision history survive restarts. The system maintains accumulated intelligence across deployments.

---

### Phase 10 — DecisionContext Domain Abstraction

A unified immutable record `DecisionContext` was introduced in `common-lib` to consolidate the scattered pipeline parameters into a single self-describing domain object.

New type: `DecisionContext` record (11 fields) with two-phase lifecycle:
- `assemble()` — pre-AI snapshot with defensive copies (`List.copyOf()`, `Map.copyOf()`)
- `withAIDecision()` — enrichment copy-factory, returns new instance with AI fields populated

New overloads: `AIStrategistService.evaluate(DecisionContext, Context)`, `HistoryService.toEntity(DecisionContext)`.
New log stage: `DECISION_CONTEXT_ASSEMBLED`.
New logger method: `DecisionFlowLogger.logDecisionContext()` — compact summary with symbol, regime, divergenceFlag, modelLabel, aiSignal.

`OrchestratorService` assembles the context pre-AI, enriches it post-AI, and logs the enriched snapshot. The `DecisionContext` does not replace `FinalDecision`, `Context`, or `AIStrategyDecision` — it composes them.

**Responsibility shift:** The pipeline gained a **first-class intelligence snapshot** that any participant can reason about without reconstructing state from scattered locals. This is a composability upgrade — future pipeline stages (risk engines, audit trails, strategy memory) can accept a single `DecisionContext` instead of 5+ arguments.

---

### Phase 11 — Reactive In-Memory Cache (market-data-service)

`market-data-service` gained a `MarketDataCache` component that stores the latest quote per symbol with regime-aware TTL.

New types: `MarketDataCache` (@Component, ConcurrentHashMap-backed), `CachedMarketData` (record: data, fetchedAt, regime).

Cache classifies regime at put-time using `MarketRegimeClassifier` via a minimal `Context` constructed from `MarketDataQuote` — no logic duplication.

TTL by regime: VOLATILE → 2 min, TRENDING → 5 min, RANGING → 7 min, CALM → 10 min.

`MarketDataService.getQuote()` uses `Mono.defer()` to check the cache first. On hit: immediate `Mono.just(cached.data())`. On miss: calls Alpha Vantage, stores result via `doOnSuccess`.

**Responsibility shift:** `market-data-service` evolved from a pure pass-through into a **cache-aware data provider** with regime-sensitive freshness policy. This protects Alpha Vantage rate limits while keeping VOLATILE-regime data fresh enough for 30-second polling intervals.

---

### Phase 12 — Snapshot API and React UI

`history-service` gained a snapshot endpoint; a React UI was built to consume it.

New types: `SnapshotDecisionDTO` (record: symbol, finalSignal, confidence, marketRegime, divergenceFlag, aiReasoning, savedAt).
New query: `@Query findLatestPerSymbol()` — correlated subquery returning one row per symbol (GROUP BY + MAX(saved_at)).
New endpoint: `GET /api/v1/history/snapshot` returning `Flux<SnapshotDecisionDTO>`.

UI module (`ui/`): React 18 + Vite + TailwindCSS + Framer Motion. Features:
- Regime header strip with regime-specific colors and labels
- AI intent badge (🧠 AI Diverging / AI Aligned) with model label
- Signal text with confidence bar
- Decision strength label (STRONG / CAUTIOUS / BALANCED DECISION)
- Guardrail awareness indicator (when divergenceFlag = true)
- Detail modal with AI reasoning, consensus insight, and divergence analysis
- Regime-driven subtle animations (VOLATILE: opacity pulse, TRENDING: gradient)

**Responsibility shift:** The system gained its first **user-facing presentation layer**. `history-service` evolved further: persistence + feedback provider + regime state provider + UI data provider.

---

### Phase 13 — Momentum State Classification

A new cognitive layer for interpreting decision history into market momentum stability.

New types: `MarketState` enum (CALM/BUILDING/CONFIRMED/WEAKENING), `MomentumStateCalculator` (pure stateless classifier).
New package: `com.agentplatform.common.momentum`.

`MomentumStateCalculator.classify()` evaluates four dimensions from recent decision history:

1. **Signal alignment** — fraction of recent signals matching dominant signal
2. **Confidence trend** — linear slope of confidence scores (least-squares regression)
3. **Divergence pressure** — fraction of recent decisions with AI-consensus divergence
4. **Regime stability** — whether market regime is consistent across the analysis window

State resolution follows a priority decision tree:
- `CONFIRMED`: strong alignment (≥0.80) + not declining + low divergence (<0.40) + stable regime
- `WEAKENING`: moderate alignment (≥0.65) + (declining OR high divergence)
- `BUILDING`: moderate alignment + rising confidence + low divergence
- `CALM`: default fallback

Minimum window of 3 decisions required for meaningful classification; returns `CALM` with insufficient data.

**Responsibility shift:** `common-lib` gained a cognitive interpretation layer that transforms raw decision history into operator-readable momentum states. This is consumed by `trade-service`, not by the orchestration pipeline — a deliberate separation keeping momentum awareness in the operator's domain.

---

### Phase 14 — Trade Posture & Exit Awareness

New types: `TradePosture` enum (CONFIDENT_HOLD/WATCH_CLOSELY/DEFENSIVE_HOLD/EXIT_CANDIDATE), `StructureSignal` enum (CONTINUATION/STALLING/FATIGUED), `ExitAwareness` record, `PostureStabilityState` record.
New classes: `TradePostureInterpreter`, `StructureInterpreter` (pure logic).
New packages: `com.agentplatform.common.posture`, `com.agentplatform.common.structure`.

`StructureInterpreter.evaluate()` classifies momentum structure:
- `FATIGUED`: divergence streak ≥ 2 AND confidence slope < -0.02
- `STALLING`: confidence slope nearly flat (within ±0.02)
- `CONTINUATION`: default — structure intact

`TradePostureInterpreter.evaluateStable()` derives posture with **60-second stability window**:
- `CONFIDENT_HOLD`: CONFIRMED momentum + CONTINUATION structure + FRESH duration
- `WATCH_CLOSELY`: STALLING structure (or default)
- `DEFENSIVE_HOLD`: FATIGUED structure OR EXTENDED duration
- `EXIT_CANDIDATE`: WEAKENING momentum AND confidence drift < -0.1

Stability window prevents posture downgrades within 60 seconds of last posture change, suppressing noisy oscillation.

`ExitAwareness` captures cognitive exit conditions: `momentumShift`, `confidenceDrift`, `divergenceGrowing`, `durationSignal` (FRESH/AGING/EXTENDED at 3min/8min thresholds), `structureSignal`, and `tradePosture`.

**Responsibility shift:** Pure cognitive interpretation gained a trade-aware dimension. These interpreters compose momentum, structure, and time signals into a single operator-readable posture — the system can now describe not just *what the market is doing* but *how the operator should feel about their position*.

---

### Phase 15 — Adaptive Risk Engine

New types: `RiskEnvelope` record (softStopPercent, hardInvalidationPercent, reasoning), `AdaptiveRiskState` record, `AdaptiveRiskEngine.MetricsInput`, `AdaptiveRiskEngine.MomentumInput`, `AdaptiveRiskEngine.EvaluationResult`, `AdaptiveRiskEngine.AdaptiveEvaluationResult`.
New class: `AdaptiveRiskEngine` (pure logic).
New package: `com.agentplatform.common.risk`.

`AdaptiveRiskEngine.evaluateAdaptive()` computes risk envelopes with exponential smoothing:
- Base stops: `softStop = -0.6%`, `hardStop = -1.1%`
- Adjustments for WEAKENING momentum (×0.6), confidence drift < -0.1 (×0.8/×0.85), confidence slope < -0.05 (×0.9)
- Smoothing: `newStop = previousStop + (targetStop - previousStop) × factor` (soft=0.35, hard=0.25)
- Bypass smoothing for first evaluation and WEAKENING momentum (immediate response)

Also computes full `ExitAwareness` by delegating to `StructureInterpreter` and `TradePostureInterpreter` internally.

**Responsibility shift:** `common-lib` gained a risk computation layer that translates cognitive signals (momentum, structure, divergence) into concrete risk boundaries. The operator can now see not just posture labels but quantified stop-loss guidance — still purely informational, never automated.

---

### Phase 16 — Trade Service & Operator UI

New module: `trade-service` (port 8086) — operator intelligence layer for trade lifecycle.
New types: `TradeSession` (JPA entity), `ActiveTradeContext`, `TradeReflectionStats`, `ReflectionInterpreter`.
New classes: `TradeService`, `TradeController`, `HistoryServiceClient`.
New packages: `com.agentplatform.trade`, `com.agentplatform.common.reflection`.
New UI components: `TacticalPostureHeader.jsx`, `MomentumBanner.jsx`.

`TradeService` manages:
- Trade session lifecycle (start/exit) with persistence
- Active trade context retrieval with adaptive risk evaluation
- Per-symbol in-memory state: `riskStateMap`, `postureStabilityMap`, `postureMap`
- Per-posture trade reflection statistics via `ReflectionInterpreter`
- Integration with `history-service` for decision metrics and market state (read-only)

New `history-service` endpoints consumed by trade-service:
- `GET /api/v1/history/decision-metrics/{symbol}` — confidence, divergence, and momentum metrics
- `GET /api/v1/history/market-state` — current market state projections
- `GET /api/v1/history/agent-performance-snapshot` — agent performance projections

UI gains two major components:
- `TacticalPostureHeader` — trade posture pill, status chips (momentum/structure/duration), confidence sparkline
- `MomentumBanner` — per-symbol momentum pills (CALM/BUILDING/CONFIRMED/WEAKENING)

**Responsibility shift:** The system gained its first **operator-facing trade intelligence layer**. `trade-service` exists entirely outside the decision pipeline — it reads from `history-service` but never writes to or influences the orchestrator. This creates a clean separation: the decision pipeline produces intelligence, the trade service interprets it for operator awareness.

---

### Phase 17 — Stability Hardening, Operator UI Completion & Real-Time SSE

Three sub-phases executed as a single evolutionary step:

**Phase 17A — Stability Hardening:**
- `DisciplineCoach.callClaude()`: added `subscribeOn(Schedulers.boundedElastic())` before `.block()` — moves the blocking call off Netty I/O threads onto a bounded elastic pool
- `AgentDispatchService.dispatchAll()`: converted from sequential synchronous dispatch to parallel reactive dispatch using `Flux.fromIterable(agents).flatMap(agent -> Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic()))`. All 4 agents now execute concurrently with per-agent error isolation (degraded HOLD result on failure)
- `AnalysisController.analyze()`: return type changed from `ResponseEntity<List<AnalysisResult>>` to `Mono<ResponseEntity<List<AnalysisResult>>>` — fully reactive HTTP endpoint
- New `AgentCapability` enum in `common-lib`: replaces fragile `agentName.toLowerCase().contains()` string matching in `AgentScoreCalculator.regimeBoost()` with type-safe capability mapping (`TrendAgent→TREND`, `RiskAgent→RISK`, `PortfolioAgent→PORTFOLIO`, `DisciplineCoach→DISCIPLINE`)

**Phase 17B — Calm Operator UI Completion:**
- Vite proxy: added `/api/v1/trade` → port 8086 before catch-all `/api` rule
- `TacticalPostureHeader`: risk envelope display (soft/hard stop percentages), `ExitTradePanel` sub-component (collapsible button → price input → confirm)
- `DetailModal`: `TradeEntryPanel` sub-component (auto-populates symbol, confidence, regime, momentum; operator inputs entry price)
- `App.jsx`: `handleTradeStart` and `handleTradeExit` callbacks wired to trade-service REST API
- `TradeReflectionPanel.jsx` (new): collapsible table of closed trades (symbol, entry, exit, PnL, duration, time) — fetches from `GET /api/v1/trade/history`
- Backend: `GET /api/v1/trade/history` and `GET /api/v1/trade/reflection-stats` endpoints added to `TradeController`

**Phase 17C — Real-Time SSE Awareness:**
- `HistoryService`: added `Sinks.Many<SnapshotDecisionDTO>` (multicast, backpressure buffer 64). Each `save()` success emits a snapshot event via `tryEmitNext()`
- `HistoryController`: new `GET /api/v1/history/stream` endpoint producing `text/event-stream` with `Flux<ServerSentEvent<SnapshotDecisionDTO>>`, event name "snapshot"
- `App.jsx`: `useEffect` with `EventSource('/api/v1/history/stream')` — listens for `snapshot` events, merges into state by symbol. Auto-reconnect via browser EventSource API. Manual refresh button retained as override

**Responsibility shift:** The analysis engine became fully non-blocking and parallel — the last synchronous bottleneck was removed. The UI evolved from read-only intelligence display to interactive operator workstation with trade lifecycle controls and real-time awareness. The platform now pushes intelligence to operators rather than requiring manual polling.

---

### Evolution Summary — How Responsibilities Shifted

```
Phase 1:   Agents -> Consensus (authority) -> FinalDecision
Phase 4:   Agents -> Adaptive Weights (computed, stored, unused) -> Consensus (authority) -> FinalDecision
Phase 6:   Agents -> Adaptive Weights -> AI Strategist (authority) -> Consensus (guardrail) -> FinalDecision
Phase 7:   FinalDecision.regime -> Scheduler reads -> adjusts trigger interval -> next cycle
Phase 8:   AI signal ≠ consensus signal -> divergenceFlag tracked and persisted
Phase 10:  DecisionContext snapshot flows through pipeline as first-class object
Phase 13:  Decision history -> MomentumStateCalculator -> MarketState (operator awareness)
Phase 14:  MarketState + metrics -> StructureInterpreter + TradePostureInterpreter -> TradePosture
Phase 15:  TradePosture + metrics -> AdaptiveRiskEngine -> RiskEnvelope (adaptive smoothing)
Phase 16:  TradeService reads history -> computes risk/posture -> UI displays tactical awareness
Phase 17:  Parallel agents + SSE push -> UI becomes interactive operator workstation with real-time awareness
Phase 27:  Consensus guardrail uses adaptive weights — divergence now compares AI vs historically-informed signal
Phase 28:  AI strategist receives last 3 decisions as strategy memory — detects flip-flop across cycles
Phase 29:  DivergenceGuard applied in buildDecision() — ConsensusOverride + ConfidenceDampen rules active
Phase 30:  DivergenceGuard confidence floor (0.50) + RecentDecisionMemoryDTO replaces entity on /recent endpoint
```

```
history-service:
  Phase 1:   pure persistence
  Phase 4:   persistence + feedback data provider
  Phase 7:   persistence + feedback + regime state provider (to scheduler)
  Phase 12:  persistence + feedback + regime + snapshot provider (to UI)
  Phase 16:  ... + decision metrics + market state provider (to trade-service)
  Phase 17:  ... + SSE snapshot stream (real-time push to UI)
  Phase 28:  ... + /recent/{symbol} strategy memory endpoint (RecentDecisionMemoryDTO — 4-field projection)

market-data-service:
  Phase 1:   pure Alpha Vantage pass-through
  Phase 11:  cache-aware data provider with regime-sensitive TTL

scheduler-service:
  Phase 1:   fixed 5-minute cron
  Phase 7:   adaptive tempo controller (30s - 10min based on regime)

agent-orchestrator:
  Phase 1:   fetch -> analyze -> consensus -> persist
  Phase 6:   fetch -> classify regime -> analyze -> adaptive weights -> AI strategy -> consensus guardrail -> persist
  Phase 10:  ... + DecisionContext assembly/enrichment + divergence detection + model selection
  Phase 27:  ... + adaptiveWeights passed to ConsensusIntegrationGuard (PerformanceWeightedConsensusStrategy active)
  Phase 28:  ... + fetchStrategyMemory() (last 3 decisions) chained before AI evaluate; memory injected into prompt
  Phase 29:  ... + computeDivergenceStreak() + DivergenceGuard applied in buildDecision()

analysis-engine:
  Phase 1:   sequential synchronous agent dispatch
  Phase 17:  parallel reactive dispatch on boundedElastic + DisciplineCoach off Netty I/O threads

trade-service:
  Phase 16:  read history projections -> compute momentum/posture/risk -> operator UI
  Phase 17:  ... + trade history/reflection-stats endpoints + start/exit trade from UI

common-lib:
  Phase 1:   domain records + interfaces
  Phase 5:   + MarketRegimeClassifier + AgentScoreCalculator
  Phase 10:  + DecisionContext
  Phase 13:  + MomentumStateCalculator + MarketState
  Phase 14:  + TradePostureInterpreter + StructureInterpreter + TradePosture + StructureSignal
  Phase 15:  + AdaptiveRiskEngine + RiskEnvelope + AdaptiveRiskState + ExitAwareness
  Phase 16:  + ReflectionInterpreter + TradeReflectionStats + ActiveTradeContext
  Phase 17:  + AgentCapability enum (type-safe agent capability mapping)
  Phase 18:  + cognition package: CalmTrajectory, DivergenceTrajectory, StabilityPressureCalculator, CalmTrajectoryInterpreter, DivergenceTrajectoryInterpreter
  Phase 19:  + ReflectionState, ArchitectReflectionInterpreter (cognition/reflection)
  Phase 20:  + CalmMood, CalmMoodInterpreter; ReflectionResult record absorbs CalmMood derivation
  Phase 21:  + ReflectionPersistence, ReflectionPersistenceCalculator; ReflectionResult carries all three cognition signals
  Phase 22:  + TradingSession, TradingSessionClassifier (session-aware signal gating)
  Phase 27:  + PerformanceWeightedConsensusStrategy; ConsensusEngine gains compute(results, weights) default method
  Phase 29:  + DivergenceGuard (guard package): OverrideResult record, ConsensusOverride + ConfidenceDampen rules
  Phase 30:  + DivergenceGuard CONFIDENCE_FLOOR = 0.50 applied to ConfidenceDampen rule
```

---

## 2. Architecture Consistency Review

### Design Philosophy Compliance

The original design philosophy — reactive non-blocking, incremental evolution, no rewrites — has been **consistently maintained** across all seventeen phases. Every new field added to `FinalDecision` is nullable with a backward-compatible factory overload (8 overloads, v1–v7). Every new adapter has `onErrorResume -> empty fallback`. No existing endpoint was removed or modified. The `DecisionContext` abstraction was introduced without replacing any existing type — purely additive composition.

### Accidental Coupling Identified

**1. ~~Agent name -> regime boost coupling in `AgentScoreCalculator.regimeBoost()`~~** **(RESOLVED in Phase 17A)**

Previously used `agentName.toLowerCase().contains("trend")` substring matching — fragile and naming-dependent. Now resolved via `AgentCapability` enum with explicit `Map<String, AgentCapability>` mapping. `regimeBoost()` uses `AgentCapability.fromAgentName(agentName)` + switch on enum values. Adding or renaming agents requires a one-line mapping update, not behavioral debugging.

**2. Dual Anthropic API integration with divergent patterns**

`DisciplineCoach` (analysis-engine) and `AIStrategistService` (agent-orchestrator) both call the Anthropic API. Since Phase 17A, `DisciplineCoach.block()` runs on `Schedulers.boundedElastic()` (not Netty I/O threads), and `AIStrategistService` uses fully reactive `Mono.flatMap()`. The concurrency risk is resolved but the API management divergence remains:

Both independently configure their own `WebClient` from `WebClient.Builder`. Both independently read `anthropic.api-key` from their respective `application.yml` files. There is no shared Anthropic client abstraction. If the Anthropic API contract changes (e.g., new header requirement), two services must be updated independently.

`AIStrategistService` now uses regime-aware model selection (`ModelSelector`), while `DisciplineCoach` uses a fixed model. This divergence is intentional (cost optimization for different use cases) but increases the surface area of Anthropic API management.

**3. Scheduler -> history-service creates a cross-plane data dependency**

`scheduler-service` (Control Plane) directly queries `history-service` (Decision Plane data store) for the latest regime. This means the scheduler's behavior depends on the correctness and availability of a service it previously had no relationship with. While the fallback (`UNKNOWN -> 5min`) preserves the original behavior, the runtime dependency exists.

**4. Cache regime classification duplicates classifier invocation context**

`MarketDataCache.put()` constructs a minimal `Context` from `MarketDataQuote` to reuse `MarketRegimeClassifier`. The classifier runs identically in the orchestrator (from the full `Context`) and in the cache (from the quote-derived minimal `Context`). The regime result may differ slightly if the full `Context` contains different price data than what the cache sees. In practice this is a non-issue because the same price list drives both — but the dual invocation is architecturally redundant.

### Reactive Boundary Violations

**~~Single violation: `DisciplineCoach.callClaude()`~~** **(RESOLVED in Phase 17A)**

Previously, `DisciplineCoach` called `.block()` on a Netty I/O thread. Phase 17A resolved this by:
1. Adding `subscribeOn(Schedulers.boundedElastic())` before `.block()` — moves blocking off Netty I/O threads
2. Converting `AgentDispatchService.dispatchAll()` to parallel reactive dispatch via `Flux.fromIterable(agents).flatMap(agent -> Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic()))`
3. Changing `AnalysisController.analyze()` return type to `Mono<ResponseEntity<List<AnalysisResult>>>` — fully reactive

**No reactive boundary violations remain in the current codebase.** All I/O operations use non-blocking WebClient chains. The `.block()` call in `DisciplineCoach` still exists syntactically but executes on `boundedElastic` threads, not Netty I/O threads.

### Service Responsibility Assessment

**`agent-orchestrator` is accumulating concerns:**

Currently `OrchestratorService` owns:

1. Market data fetching and JSON parsing
2. Context assembly
3. Market regime classification
4. Analysis engine invocation
5. Performance weight fetching
6. Feedback fetching
7. Adaptive weight computation (`AgentScoreCalculator.compute()`)
8. DecisionContext assembly (pre-AI snapshot)
9. AI model selection (`ModelSelector.selectModel()`)
10. AI strategy evaluation (`AIStrategistService.evaluate()`)
11. Consensus guardrail execution
12. Divergence detection (AI vs consensus)
13. DecisionContext enrichment (post-AI snapshot)
14. `FinalDecision` assembly (17 fields)
15. History persistence (fire-and-forget)
16. Notification dispatch (fire-and-forget)

This is 16 distinct responsibilities in a single `orchestrate()` method. While each is composed via reactive operators (no procedural God-method), the constructor injection list has grown to 10 dependencies. The orchestrator is still architecturally correct — it's the coordinator, not the implementor — but it's approaching a complexity ceiling.

**`history-service` has grown into a multi-role service:**

Currently `HistoryService` owns:

1. Decision persistence (`save()`)
2. Agent performance aggregation (`getAgentPerformance()`)
3. Agent feedback aggregation (`getAgentFeedback()`)
4. Latest regime lookup (`getLatestRegime()`)
5. Snapshot projection (`getLatestSnapshot()`)
6. Decision metrics for trade-service (`getDecisionMetrics()`)
7. Market state projections for trade-service (`getMarketState()`)
8. Agent performance snapshots (`getAgentPerformanceSnapshot()`)
9. Alternative DecisionContext mapping (`toEntity(DecisionContext)` — not yet active)

Nine responsibilities with seven distinct query patterns. The service has evolved from pure persistence into a **decision intelligence data API** serving the orchestrator, scheduler, UI, and trade-service.

**`trade-service` is a new operator intelligence layer:**

Currently `TradeService` owns:

1. Trade session lifecycle (start/exit)
2. Active trade context retrieval with adaptive risk evaluation
3. History-service integration (decision metrics, market state)
4. Per-symbol adaptive risk state tracking (in-memory)
5. Per-symbol posture stability tracking (in-memory)
6. Per-posture trade reflection statistics

Six responsibilities with clear separation: trade lifecycle (persistence) + risk/posture computation (pure logic delegation) + history integration (read-only). The service is architecturally clean — it consumes intelligence without producing or influencing it.

### Hidden Technical Debt

| Item | Location | Impact | Status |
|---|---|---|---|
| ~~Full table scan on every cycle~~ | `HistoryService` | ~~O(N) with no bound~~ | **RESOLVED** (Phase 17 — pre-aggregated `agent_performance_snapshot` table) |
| Self-referential win rate | `aggregateFeedback()` | `winRate` measures alignment with AI strategist signal (since Phase 6), not market truth | OPEN |
| No idempotency on history save | `DecisionHistoryRepository` | Duplicate `traceId` -> duplicate rows | OPEN |
| ~~Sequential agent execution~~ | `AgentDispatchService.dispatchAll()` | ~~Four agents run sequentially~~ | **RESOLVED** (Phase 17A — parallel reactive dispatch on boundedElastic) |
| Unbounded `recentClosingPrices` | `fetchMarketDataAndBuildContext()` | Alpha Vantage response size determines memory per request | OPEN |
| ~~No index on snapshot query~~ | `findLatestPerSymbol()` | ~~GROUP BY + MAX subquery full scan~~ | **RESOLVED** (v8 schema — composite index `idx_decision_history_symbol_savedat`) |
| Trade-service in-memory state | `TradeService` maps | `riskStateMap`, `postureStabilityMap` lost on restart — adaptive state resets | ACCEPTED |
| Projection pipeline non-atomic | `HistoryService.updateProjections()` | Projections may lag if upsert fails — wrapped in `onErrorResume`, never blocks save | ACCEPTED |

---

## 3. Control Plane vs Decision Plane Analysis

### Decision Plane — Current Boundaries

The Decision Plane encompasses everything involved in producing, evaluating, and refining the market signal:

| Component | Location | Role |
|---|---|---|
| `TrendAgent`, `RiskAgent`, `PortfolioAgent`, `DisciplineCoach` | analysis-engine | Feature extraction from raw prices |
| `TechnicalIndicators` | analysis-engine | Stateless math library for indicator computation |
| `MarketRegimeClassifier` | common-lib | Price data -> regime enum classification |
| `AgentScoreCalculator` | common-lib | Multi-layer adaptive weight computation |
| `DefaultWeightedConsensusStrategy` | common-lib | Equal-weight signal aggregation (safety guardrail) |
| `ConsensusIntegrationGuard` | agent-orchestrator | Null/empty guard before consensus |
| `AIStrategistService` | agent-orchestrator | **Primary decision intelligence** (Claude API) |
| `ModelSelector` | agent-orchestrator | Regime -> Claude model mapping |
| `DecisionContext` | common-lib | Unified intelligence snapshot |
| `HistoryService.aggregatePerformance()`, `.aggregateFeedback()` | history-service | Feedback loop data provider |
| `HistoryService.getLatestSnapshot()` | history-service | UI data projection |
| `HistoryService.getDecisionMetrics()` | history-service | Trade-service metrics provider |
| `HistoryService.getMarketState()` | history-service | Trade-service market state provider |
| `MomentumStateCalculator` | common-lib | 4-dimensional momentum classification |
| `TradePostureInterpreter` | common-lib | Posture derivation with stability window |
| `StructureInterpreter` | common-lib | Momentum structure classification |
| `AdaptiveRiskEngine` | common-lib | Exit awareness + risk envelope computation |
| `ReflectionInterpreter` | common-lib | Trade outcome statistics per posture |
| `TradeService` | trade-service | **Operator intelligence layer** (read-only) |

The Decision Plane's intelligence is distributed across four services: analysis-engine (agent execution), agent-orchestrator (regime classification, weight computation, AI strategy, consensus, divergence detection), history-service (aggregation, snapshot, metrics), and trade-service (momentum, posture, risk interpretation for operators). The stateless computation logic (`AgentScoreCalculator`, `MarketRegimeClassifier`, `MomentumStateCalculator`, `TradePostureInterpreter`, `StructureInterpreter`, `AdaptiveRiskEngine`, `ReflectionInterpreter`, `TechnicalIndicators`, `DecisionContext`) lives in `common-lib`, which is architecturally correct — shared logic without runtime coupling.

### Control Plane — Current Boundaries

| Component | Location | Role |
|---|---|---|
| `MarketDataScheduler` | scheduler-service | Temporal control: when to trigger, per symbol |
| `AdaptiveTempoStrategy` | scheduler-service | Regime -> interval mapping policy |
| `HistoryClient` | scheduler-service | Regime state reader from Decision Plane |
| `OrchestratorService.orchestrate()` | agent-orchestrator | Pipeline sequencing and side-effect dispatch |
| `MarketDataCache` | market-data-service | Regime-aware data freshness control |

### Boundary Evaluation

**The boundaries are clean but asymmetric.** The Control Plane reads from the Decision Plane (scheduler fetches regime from history-service, cache classifies regime from prices) but never writes to it. The Decision Plane is unaware of the Control Plane's existence — `OrchestratorService` doesn't know who called it or at what frequency.

**One boundary leak exists:** `OrchestratorService` performs market regime classification (`MarketRegimeClassifier.classify(ctx)`) inside a `doOnNext`. This is a Decision Plane computation running inside what is functionally a Control Plane coordinator. The classification doesn't affect pipeline sequencing — it only modulates downstream weight computation, model selection, and gets stored in `FinalDecision`. But architecturally, it means the orchestrator is doing intelligence work, not just orchestration.

This is an acceptable compromise today because `MarketRegimeClassifier` is a pure function with no dependencies, no I/O, and nanosecond-scale execution. Moving it to a separate service would add network latency for negligible architectural benefit. But it should be acknowledged as a Decision Plane concern living inside the Control Plane coordinator.

**A second boundary consideration:** `MarketDataCache` classifies regime independently of the orchestrator. The cache's regime classification is used only for TTL decisions — it never escapes `market-data-service`. The orchestrator's classification is used for weight computation, model selection, and persistence. These are independent uses of the same classifier, not a consistency concern.

---

## 4. Scalability Capability Assessment

### Number of Symbols Monitored

**Current pattern:** `MarketDataScheduler.startAdaptiveScheduling()` iterates `symbolsConfig.split(",")` and launches one independent reactive loop per symbol. Each loop creates one `Mono.delay()` -> trigger -> regime-fetch subscription.

**Scaling behavior:** Each symbol adds one inflight `Mono.delay()` on Reactor's `Schedulers.parallel()` timer and one orchestration pipeline execution. `Mono.delay()` uses a hashed wheel timer — thousands of concurrent delays are O(1) memory per delay. The bottleneck is not the scheduler but the downstream services:

- **market-data-service:** Alpha Vantage free tier allows 25 requests/day. The `MarketDataCache` mitigates this: at CALM tempo (10min) with 10min cache TTL, a symbol makes ~1 API call per cache miss. With 2 symbols, most cycles hit cache. But at VOLATILE tempo (30s) with 2min cache TTL, cache misses happen every ~4 cycles — still well within limits. Without the cache, 2 symbols at 5-minute intervals would exhaust the daily limit in ~1 hour.
- **analysis-engine:** Since Phase 17A, all four agents run in parallel on `Schedulers.boundedElastic()`. `DisciplineCoach.block()` no longer blocks Netty I/O threads. Bounded elastic pool defaults to `10 × CPU cores` threads — sufficient for ~40 concurrent agent evaluations. The bottleneck shifts to Anthropic API rate limits at scale.

**Realistic capacity:** 2-5 symbols with the current Alpha Vantage tier. Architecture supports hundreds with a paid data provider.

### Orchestration Concurrency

Each incoming `POST /trigger` creates a fresh `Mono.defer()` pipeline. Netty handles concurrent requests via NIO — no thread-per-request model. Concurrency is bounded by:

1. **Netty thread count:** `2 x CPU cores` I/O threads. The pipeline is fully non-blocking (since Phase 17A), so each thread can handle many concurrent pipelines interleaved at I/O boundaries.
2. **WebClient connection pools:** Spring Boot's default reactor-netty connection pool is 500 connections per host, 45 second idle timeout. Each orchestration cycle opens connections to 4 downstream services. At 50 concurrent orchestrations, that's 200 connections — within limits.
3. **History-service pre-aggregated reads:** `getAgentPerformance()` and `getAgentFeedback()` now read from the `agent_performance_snapshot` table (single row per agent, 4 rows total). This eliminates the previous full-table-scan bottleneck. PostgreSQL connection pool (default 10 connections via R2DBC) is the remaining serialization point.

**Realistic capacity:** 20-50 concurrent orchestrations without backpressure, bounded primarily by PostgreSQL connection pool sizing and Anthropic API rate limits.

### AI Call Flow

Two AI integration points with different cost profiles:

| Service | Model | Max Tokens | Frequency | Blocking |
|---|---|---|---|---|
| `DisciplineCoach` | `claude-haiku-4-5-20251001` | 300 | Once per agent dispatch (inside analysis-engine) | Yes (`.block()`) |
| `AIStrategistService` | Regime-dependent (Haiku or Sonnet) | 300 | Once per orchestration cycle (inside agent-orchestrator) | No (reactive) |

At VOLATILE tempo (30s interval), 2 symbols: `4 calls/minute x 2 AI calls/cycle = 8 Claude calls/minute`. At 10 symbols x VOLATILE tempo: `20 calls/minute x 2 = 40 Claude calls/minute`. Anthropic rate limits (tokens per minute) become the constraint before cost does.

Both services have fallbacks: `DisciplineCoach` uses rule-based thresholds, `AIStrategistService` uses majority-vote over agent signals. The system degrades gracefully if the API is unavailable or rate-limited.

### Database Growth

**Schema:** 4 tables (v8): `decision_history` (19 columns), `agent_performance_snapshot` (12 columns), `decision_metrics_projection` (6 columns), `trade_sessions` (11 columns). Composite index on `(symbol, saved_at DESC)`.

**Growth rate:** `decision_history`: 1 row per symbol per orchestration cycle. At 2 symbols x 5min intervals: `576 rows/day`, ~2.3MB/day. `agent_performance_snapshot`: fixed 4 rows (one per agent), updated in-place. `decision_metrics_projection`: fixed N rows (one per symbol), updated in-place. `trade_sessions`: grows with operator trade activity only.

**Aggregation cost:** Since the v8 projection pipeline, `getAgentPerformance()` and `getAgentFeedback()` read from `agent_performance_snapshot` — O(agents) = 4 rows. Full-table scans are no longer triggered during normal operation. The projection pipeline (`updateProjections()`) runs as a non-fatal side-effect of each save, updating running counters via SQL upsert.

**Snapshot query cost:** `findLatestPerSymbol()` uses the composite index `idx_decision_history_symbol_savedat ON (symbol, saved_at DESC)` — O(symbols) not O(rows). Measurably fast at any table size.

**Remaining growth concern:** `decision_history` grows indefinitely with no retention policy. After months of operation, the table size itself (disk, backup time, DDL migrations) becomes a management concern. Consider time-based partitioning or retention policies for production.

---

## 5. Future Evolution Paths

### ✅ COMPLETED — Path 2: Persistent Storage (Postgres R2DBC)

**Completed in Phase 9.** H2 in-memory replaced with PostgreSQL via R2DBC. Zero Java code changes. Feedback loop now survives restarts. Schema uses `BIGSERIAL`, `DOUBLE PRECISION`.

---

### ✅ COMPLETED — Path 5: AI Consensus Divergence Detection

**Completed in Phase 8.** `divergenceFlag` computed in `buildDecision()`, stored in `FinalDecision` and `decision_history`, surfaced in UI. Pure additive — no behavioral change, pure observability.

---

### ✅ COMPLETED — Path 6: Multi-Model AI Orchestration (Partial)

**Partially completed in Phase 6.** `ModelSelector` selects between Haiku (VOLATILE) and Sonnet (all others) based on regime. The regime-aware model selection is in production. The "two models in parallel via `Mono.zip()`" variant remains unimplemented — see Path 10 below.

---

### ✅ COMPLETED — Path 1: Performance-Weighted Consensus Strategy

**Completed in Phase 27.** `PerformanceWeightedConsensusStrategy` implements `ConsensusEngine` with adaptive per-agent weights. `ConsensusEngine` interface gained a `default compute(results, weights)` method for backward compatibility. `ConsensusIntegrationGuard` gained a `resolve(results, engine, weights)` overload. `OrchestratorConfig` bean swapped. `buildDecision()` passes `adaptiveWeights` to the guard. Divergence now compares AI against a historically-informed guardrail — a stronger signal than equal-weight comparison.

---

### ✅ COMPLETED — Path 3: Pre-Aggregated Performance Metrics

**Completed in Phase 17 (v8 schema).** `agent_performance_snapshot` table stores running counters per agent (total decisions, sum confidence, sum latency, sum wins). `decision_metrics_projection` table stores per-symbol trend metrics (confidence slope, divergence streak, momentum streak). Updated via non-fatal projection pipeline on each `save()`. `getAgentPerformance()` and `getAgentFeedback()` read from projections — no full-table scans.

---

### ✅ COMPLETED — Path 4: Parallel Agent Execution

**Completed in Phase 17A.** `AgentDispatchService.dispatchAll()` converted to `Flux.fromIterable(agents).flatMap(agent -> Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic()))`. All 4 agents execute concurrently with per-agent error isolation. `AnalysisController.analyze()` returns `Mono<ResponseEntity<...>>` — fully reactive. `DisciplineCoach.block()` runs on boundedElastic, not Netty I/O threads.

---

### Path 7: Event Streaming Migration (Kafka)

**Problem it solves:** All inter-service communication is synchronous HTTP. Fire-and-forget patterns (history save, notification dispatch) use `.subscribe()` which provides no delivery guarantee.

**Which layer evolves:** `agent-orchestrator` (publisher bean swap) + `history-service` and `notification-service` (new Kafka consumers).

**Implementation:** The `DecisionEventPublisher` interface is an explicit seam:

```java
public interface DecisionEventPublisher {
    void publish(FinalDecision decision);
}
```

Replace `RestDecisionEventPublisher` with `KafkaDecisionPublisher`. `OrchestratorService` calls `decisionEventPublisher.publish(decision)` with zero knowledge of transport. `notification-service` and `history-service` become Kafka consumers.

**What changes:** New `KafkaDecisionPublisher` bean, `OrchestratorConfig` bean swap, Kafka consumer in history-service and notification-service, Docker Compose adds Kafka/Zookeeper.

**Risk level:** MEDIUM. The publisher seam is clean, but the consumers must be built. `history-service` currently receives `FinalDecision` via HTTP POST — switching to Kafka consumption changes its activation model from request-driven to event-driven.

---

### Path 8: Per-Symbol Pacing with Regime Memory

**Problem it solves:** `AdaptiveTempoStrategy` resolves interval from the **last** regime only. A VOLATILE->CALM transition immediately jumps to 10-minute intervals. No smoothing, no hysteresis.

**Which layer evolves:** `scheduler-service` (scheduling loop + new buffer class).

**Implementation:** Maintain a `Map<String, List<MarketRegime>>` of recent regimes per symbol (last 5-10). Use majority or trend detection to smooth transitions:

- If 4 of last 5 regimes are VOLATILE, stay at 30s even if the latest is CALM.
- Require 3 consecutive CALM readings before widening to 10min.

**What changes:** State in `MarketDataScheduler` (or a new `RegimeHistoryBuffer` class). `scheduleNextCycle` reads from the buffer instead of the single latest regime.

**Risk level:** LOW. The scheduling loop already handles per-symbol state (the recursive `scheduleNextCycle` pattern). Adding a small in-memory buffer is purely additive.

---

### ✅ COMPLETED — Path 9: Strategy Memory Layer

**Completed in Phase 28 (implementation) and Phase 30 (DTO refinement).** `GET /api/v1/history/recent/{symbol}?limit=3` added to history-service, returning `Flux<RecentDecisionMemoryDTO>` (4 fields: `finalSignal`, `confidenceScore`, `divergenceFlag`, `marketRegime`). `OrchestratorService.fetchStrategyMemory()` fetches prior decisions reactively before the AI call — `onErrorResume` returns empty list so the pipeline never stalls. `AIStrategistService` gained a memory-aware `evaluate(DecisionContext, Context, List<Map>)` overload and `buildMemorySection()`. A 3-line bullet summary injected into the prompt after the session section. `DecisionContext` not modified. Phase 30 replaced `Flux<DecisionHistory>` (25+ field entity) with `RecentDecisionMemoryDTO` projection, reducing per-call payload from ~10KB to ~200 bytes.

---

### Path 10: Multi-Model Cross-Validation

**Problem it solves:** The AI strategist uses a single model per evaluation. No cross-validation of the AI recommendation.

**Which layer evolves:** `agent-orchestrator` (`AIStrategistService` internal logic).

**Implementation:** `AIStrategistService` calls two models in parallel via `Mono.zip()` — e.g., Haiku and Sonnet — and compares their signals. Agreement increases confidence. Disagreement could flag an additional `aiModelDivergence` metric.

**What it enables:** A second dimension of divergence awareness (AI-vs-AI alongside AI-vs-consensus). The `DecisionContext` could carry both model recommendations for downstream logging and analysis.

**What changes:** `AIStrategistService.evaluate()` internal logic. No interface change. No pipeline change.

**Risk level:** MEDIUM. Doubles AI API cost. Increases latency if not parallelized. Rate limit risk at scale. But the reactive pattern (`Mono.zip`) handles it cleanly.

---

### Path 11: DecisionContext as Primary Persistence Source

**Problem it solves:** `HistoryService.toEntity(DecisionContext)` exists but is not wired. The save pipeline still decomposes `FinalDecision` into entity fields. Using `DecisionContext` as the persistence source would eliminate the `FinalDecision` serialization overhead and provide a richer, more cohesive history record.

**Which layer evolves:** `agent-orchestrator` (passes `DecisionContext` to save) + `history-service` (wires `toEntity(DecisionContext)` into save path).

**Implementation:** After assembling the enriched `DecisionContext`, the orchestrator passes it (alongside or instead of `FinalDecision`) to history-service. `HistoryService.save()` gains an overload accepting `DecisionContext` and delegates to the existing `toEntity(DecisionContext)` method.

**What changes:** `OrchestratorService.saveToHistory()` sends `DecisionContext` instead of `FinalDecision`. `HistoryController` gains a new `POST /save-context` endpoint. The database schema is unchanged — both paths produce identical row shapes.

**Risk level:** LOW. The `toEntity(DecisionContext)` mapping already exists. The schema is unchanged. The transition can be gradual — both save paths can coexist.

---

### ✅ COMPLETED — Path 12: Divergence-Triggered Safety Override

**Completed in Phase 29 (implementation) and Phase 30 (confidence floor refinement).** `DivergenceGuard` added to `common-lib/guard`. Two rules: **Rule 1 (ConsensusOverride)** — when `divergenceFlag = true` AND `consensusConfidence ≥ 0.65`, AI signal replaced with consensus signal; **Rule 2 (ConfidenceDampen)** — when `divergenceFlag = true` AND `recentDivergenceStreak ≥ 2`, AI confidence multiplied by `0.80` (floored at `0.50`). `OrchestratorService.buildDecision()` gained `divergenceStreak` parameter computed from the already-fetched strategy memory list — zero extra I/O. `divergenceFlag` in `FinalDecision` still reflects pre-override AI vs consensus disagreement. `aiReasoning` appended with `[OVERRIDE: ...]` marker when guard fires. Thresholds: `OVERRIDE_CONSENSUS_THRESHOLD = 0.65`, `STREAK_DAMPEN_THRESHOLD = 2`, `CONFIDENCE_DAMPEN_FACTOR = 0.80`, `CONFIDENCE_FLOOR = 0.50`.

---

### ✅ COMPLETED — Path 13: Database Indexing and Query Optimization (Partial)

**Partially completed in v8 schema.** Composite index `idx_decision_history_symbol_savedat ON decision_history(symbol, saved_at DESC)` added. Optimizes `findBySymbolOrderBySavedAtDesc`, `findLatestPerSymbol`, and `getLatestRegime`. Index on `trace_id` and time-based partitioning remain unimplemented.

---

### ✅ COMPLETED — Path 14: Real-Time UI via SSE

**Completed in Phase 17C.** `GET /api/v1/history/stream` endpoint returns `Flux<ServerSentEvent<SnapshotDecisionDTO>>`. `HistoryService` uses `Sinks.Many` (multicast, backpressure buffer 64) — each `save()` emits a snapshot event via `tryEmitNext()`. UI connects via `EventSource('/api/v1/history/stream')`, listens for `snapshot` events, merges by symbol. Manual refresh button retained as override. Existing snapshot endpoint serves initial page load.

---

### Evolution Path Safety Matrix

| Path | Risk | Status | Cross-Module | Breaking |
|---|---|---|---|---|
| 1. Weighted Consensus | LOW | ✅ DONE (Phase 27) | common-lib + orchestrator | No |
| 3. Pre-Aggregated Metrics | LOW | ✅ DONE | history-service only | No |
| 4. Parallel Agents | MEDIUM | ✅ DONE | analysis-engine only | No |
| 7. Kafka Streaming | MEDIUM | OPEN | orchestrator + history + notification | No (interface seam) |
| 8. Regime Memory | LOW | OPEN | scheduler-service only | No |
| 9. Strategy Memory | MEDIUM | ✅ DONE (Phase 28+30) | history-service + orchestrator | No |
| 10. Multi-Model Cross-Validation | MEDIUM | OPEN | orchestrator only | No |
| 11. DecisionContext Persistence | LOW | OPEN | orchestrator + history | No |
| 12. Divergence Safety Override | MEDIUM | ✅ DONE (Phase 29+30) | common-lib + orchestrator | No |
| 13. Database Indexing | LOW | ✅ PARTIAL | history-service schema only | No |
| 14. Real-Time UI (SSE) | LOW | ✅ DONE | history-service + UI | No |

### Recommended Execution Order (Remaining Paths)

Paths 1, 9, and 12 are complete. The system now has adaptive consensus, AI memory, and divergence safety logic active.

**Next evolution must be data-driven, not speculative.** Run the system, collect divergence frequency, measure override frequency, measure average dampening impact — then decide. The remaining paths in priority order once data is available:

1. **Path 8: Regime Memory** — smooths scheduling transitions (LOW risk, scheduler-only)
2. **Path 11: DecisionContext Persistence** — architectural cleanup, non-urgent (LOW risk)
3. **Path 13 (remaining): trace_id index + partitioning** — prerequisite for long-term operation
4. **Path 7: Kafka Streaming** — infrastructure upgrade, deferred until scale demands it
5. **Path 10: Multi-Model Cross-Validation** — research path, cost and latency implications

---

## 6. Architecture Risk Radar

### Hidden Scaling Risks

| Risk | Severity | Trigger Condition |
|---|---|---|
| **~~Full table scan on every cycle~~** | ~~HIGH~~ RESOLVED | Fixed in v8 schema: `getAgentPerformance()` and `getAgentFeedback()` read from `agent_performance_snapshot` projection table (4 rows). Full-table scans eliminated from normal operation |
| **Alpha Vantage rate limit** | MEDIUM | Free tier: 25 requests/day. `MarketDataCache` mitigates this significantly (cache hits avoid API calls), but sustained VOLATILE tempo with cache misses can still exhaust the limit |
| **~~Netty thread exhaustion via DisciplineCoach~~** | ~~MEDIUM~~ RESOLVED | Fixed in Phase 17A: `.block()` now runs on `Schedulers.boundedElastic()`, not Netty I/O threads. Agents dispatch in parallel via `Flux.flatMap()` |
| **Unbounded regime fetch in scheduler** | LOW | `findBySymbolOrderBySavedAtDesc()` returns full `Flux` — `.next()` reads only the first element, but Spring Data R2DBC may fetch multiple rows depending on driver behavior |
| **~~Snapshot query without index~~** | ~~MEDIUM~~ RESOLVED | Fixed in v8 schema: composite index `idx_decision_history_symbol_savedat ON (symbol, saved_at DESC)` — O(symbols) not O(rows) |
| **PostgreSQL connection pool saturation** | LOW | Default R2DBC pool = 10 connections. Under concurrent orchestrations, performance/feedback aggregation calls could saturate the pool |
| **Trade-service in-memory state loss** | LOW | `riskStateMap`, `postureStabilityMap`, `postureMap` are in-memory. Container restart loses adaptive risk state and posture stability — resets to defaults on next evaluation |
| **History-service load from trade-service** | LOW | Trade-service calls `/decision-metrics/{symbol}` and `/market-state` on every `getActiveTrade()` request, adding query load to an already multi-role service |

### Reactive Anti-Patterns

| Pattern | Location | Description |
|---|---|---|
| **~~`.block()` on Netty I/O thread~~** | `DisciplineCoach.callClaude()` | RESOLVED in Phase 17A: `.subscribeOn(Schedulers.boundedElastic())` moves the block off Netty I/O threads. `AgentDispatchService` wraps all agents in `Mono.fromCallable().subscribeOn(boundedElastic)` for parallel execution |
| **Mutable capture in lambda** | `OrchestratorService.orchestrate()` | `MarketRegime[] regime = {UNKNOWN}` and `Context[] capturedCtx = {null}` — idiomatic but non-obvious to readers unfamiliar with Reactor single-subscriber semantics |
| **Fire-and-forget with no delivery guarantee** | `saveToHistory()`, `RestDecisionEventPublisher.publish()` | `.subscribe(success, error)` — if the subscriber's error handler merely logs, the decision is silently lost. No retry, no dead-letter, no at-least-once guarantee |

### AI Cost Risks

| Scenario | Claude Calls/Hour | Monthly Estimate |
|---|---|---|
| 2 symbols, RANGING (5min) | 48 calls/hr | ~35K calls/month |
| 2 symbols, VOLATILE (30s) | 480 calls/hr | ~350K calls/month |
| 10 symbols, VOLATILE (30s) | 2,400 calls/hr | ~1.75M calls/month |

With regime-aware model selection: VOLATILE cycles use Haiku (~$0.25/M input, ~$1.25/M output), while RANGING/TRENDING/CALM cycles use Sonnet (~5x cost). The regime-aware strategy optimizes for the common case — VOLATILE bursts are cheap, deep analysis during stable periods is worth the premium.

The real risk is not cost but **rate limiting**. Anthropic's rate limits are per-organization. At 2,400 calls/hour, bursts from concurrent VOLATILE-regime symbols could hit per-minute token limits.

### Scheduler Edge Cases

| Edge Case | Behavior | Safety |
|---|---|---|
| History-service down at startup | `fetchLatestRegime()` returns `UNKNOWN` via `onErrorResume` | Safe — falls back to 5-minute interval |
| Orchestrator down | `triggerOrchestration()` absorbs error via `onErrorResume(-> Mono.empty())`, chain continues to regime fetch | Safe — reschedules with last known or fallback interval |
| Both services down | Both errors absorbed, `subscribe` success handler runs with `UNKNOWN` | Safe — 5-minute fallback, loop continues |
| All services fail (complete subscribe error) | Error handler in `.subscribe()` reschedules with `FALLBACK_INTERVAL` | Safe — loop never stops |
| VOLATILE regime sustained for hours | 30-second intervals x 2 symbols = 240 calls/hour to orchestrator | Functional but rate-limited by Alpha Vantage (mitigated by cache) and Anthropic. No built-in circuit breaker |
| Container restart during `Mono.delay()` | In-flight delay and all per-symbol loop state is lost | Safe — `@PostConstruct` re-initializes all loops with `UNKNOWN` interval on restart. PostgreSQL history survives |
| Cache and scheduler disagree on regime | Cache TTL uses regime from market data prices; scheduler uses regime from last persisted decision | Acceptable — they serve different purposes. Cache controls data freshness; scheduler controls trigger cadence |

---

*Review conducted against codebase as of 2026-02-26. All class names, method signatures, and data flows verified against source. 9 modules (8 Java + 1 React UI), 70+ Java files, 20+ REST endpoints, 22-component FinalDecision (v8), 18-field DecisionContext, 4 analysis agents, 2 AI integration points, regime-aware model selection, regime-aware cache, 1 adaptive scheduler, momentum tracking (MomentumStateCalculator), trade posture interpretation (TradePostureInterpreter + StructureInterpreter), adaptive risk engine (AdaptiveRiskEngine), trade service (operator intelligence), PostgreSQL persistence.*

---

## Phase 18 — Calm Omega Evolution ✅ COMPLETED 2026-02-26

**Goal:** Introduce anticipatory stability intelligence as derived cognition. No new services, no schema changes, no pipeline restructuring.

### 18A — common-lib cognition package

New package: `com.agentplatform.common.cognition`

| Class | Role |
|---|---|
| `CalmTrajectory` | Enum: `STABILIZING`, `NEUTRAL`, `DESTABILIZING` |
| `DivergenceTrajectory` | Enum: `RISING`, `STABLE`, `COOLING` |
| `StabilityPressureCalculator` | Derives pressure score [0.0–1.0] from signal entropy + confidence dispersion + weight penalty |
| `CalmTrajectoryInterpreter` | Maps pressure → CalmTrajectory (thresholds: <0.30 = STABILIZING, >0.60 = DESTABILIZING) |
| `DivergenceTrajectoryInterpreter` | Maps consensus ratio → DivergenceTrajectory (≥0.75 = COOLING, ≤0.40 or 3+ signals = RISING) |

### 18B — DecisionContext additive fields

`DecisionContext` extended from 11 → 14 fields (all nullable, Phase-18 omega fields):
- `Double stabilityPressure`
- `CalmTrajectory calmTrajectory`
- `DivergenceTrajectory divergenceTrajectory`

New copy-factory: `withCalmOmega(stabilityPressure, calmTrajectory, divergenceTrajectory)`
`withAIDecision()` updated to preserve omega fields through the post-AI enrichment step.

### 18C — Orchestrator enrichment step

Single enrichment block inserted in `OrchestratorService` between `DecisionContext.assemble()` and `aiStrategistService.evaluate()`:
1. Compute `stabilityPressure` via `StabilityPressureCalculator`
2. Derive `calmTrajectory` via `CalmTrajectoryInterpreter`
3. Derive `divergenceTrajectory` via `DivergenceTrajectoryInterpreter`
4. Produce `omegaCtx = decisionCtx.withCalmOmega(...)` — passed to AI strategist

No WebClient calls. No blocking. Pure derived computation from already-present pipeline data.

### 18D — AIStrategist Omega Mode

`AIStrategistService.evaluate(DecisionContext, Context)` now routes through omega-aware `buildPrompt()`.

Prompt delta rules (applied ≤25% weight):
- `DESTABILIZING` → "Soften any BUY signal toward HOLD bias."
- `STABILIZING` → "Cautious early BUY permitted if agent signals support it."
- `NEUTRAL` → No trajectory bias.
- `VOLATILE` regime → omega section suppressed entirely. Agent signals dominate.

### Reactive contract
All enrichment is synchronous pure computation — no reactive chain modification. Omega fields travel as value objects through the existing `DecisionContext` carrier.

---

## Phase 19 — Architect Reflection: Decision Self-Observation Layer ✅ COMPLETED 2026-02-26

**Goal:** Self-observation intelligence derived from existing Phase-18 omega signals. No new services, endpoints, schema, or reactive chain changes.

### 19A — common-lib additions

| Artifact | Location | Role |
|---|---|---|
| `ReflectionState` | `cognition/ReflectionState.java` | Enum: `ALIGNED`, `DRIFTING`, `UNSTABLE` |
| `ArchitectReflectionInterpreter` | `cognition/reflection/` | Pure static interpreter — derives `reflectionState` from `signalVariance`, `stabilityPressureLevel`, `trajectoryFlipRate` |

Sub-metrics (all derived from in-pipeline data, no external calls):
- `signalVariance` — proportion of agents not on dominant signal
- `stabilityPressureLevel` — raw Phase-18 pressure value as single-cycle proxy
- `trajectoryFlipRate` — composite score: DESTABILIZING(+1.5) + RISING(+1.0) → UNSTABLE ≥ 2.0

### 19B — DecisionContext additive field

`DecisionContext` extended from 14 → 15 fields:
- `ReflectionState reflectionState` — nullable; Phase-19; populated after Omega enrichment

New copy-factory: `withReflection(ReflectionState)`
All existing copy-factories (`withAIDecision`, `withCalmOmega`) updated to preserve `reflectionState`.

### 19C — Orchestrator enrichment step

Single step inserted after Phase-18 block in `OrchestratorService`:

```
assemble() → withCalmOmega() → withReflection() → aiStrategistService.evaluate()
```

Pure synchronous computation. No I/O. No reactive chain change.

### 19D — AIStrategist prompt delta

Reflection section injected after Omega section (suppressed for `VOLATILE` regime):
- `ALIGNED`  → "Normal calm reasoning. No additional bias."
- `DRIFTING` → "Soften confidence language. Early HOLD bias permitted."
- `UNSTABLE` → "Suppress aggressive BUY/SELL flips. Prefer HOLD to stabilize."
- Weight constraint: ≤ 20% of reasoning influence.

---

## Phase 20 — CalmMood: Operator Emotional State ✅ COMPLETED 2026-02-26

**Goal:** Derive operator emotional context from existing Phase-18/19 signals. No new services, no schema changes, no pipeline restructuring. CalmMood is produced inside the cognition layer — no separate orchestration step.

### 20A — common-lib additions

| Artifact | Location | Role |
|---|---|---|
| `CalmMood` | `cognition/CalmMood.java` | Enum: `CALM`, `BALANCED`, `PRESSURED` |
| `CalmMoodInterpreter` | `cognition/CalmMoodInterpreter.java` | Pure static — derives CalmMood from stabilityPressure, calmTrajectory, divergenceTrajectory, reflectionState |

Derivation rules:
- `PRESSURED` — UNSTABLE reflection OR (DESTABILIZING + RISING) OR pressure > 0.65
- `CALM`      — ALIGNED + non-destabilizing + converging + pressure < 0.40
- `BALANCED`  — all other signal combinations (mixed or inconclusive)

### 20B — ArchitectReflectionInterpreter refactor

`ArchitectReflectionInterpreter` now returns `ReflectionResult(reflectionState, calmMood)` — a single interpretation pass produces both signals. `CalmMoodInterpreter.interpret()` is called at the end of the same pass.

**Architecture benefit:** Removes a separate `withCalmMood()` orchestration step. CalmMood is a cognition-layer concern — it lives and is computed in `common-lib`, not in `OrchestratorService`.

### 20C — DecisionContext additive field

`DecisionContext` extended from 15 → 16 fields:
- `CalmMood calmMood` — nullable; Phase-20; derived in same pass as `reflectionState`

New overload: `withReflection(ReflectionState, CalmMood)` — populates both fields in one call. Single-arg `withReflection(ReflectionState)` retained for backwards compatibility.

### 20D — AIStrategist prompt delta

`moodSection` injected after reflection section (suppressed for `VOLATILE` regime):
- `CALM`      → "Operator mood: CALM. Confidence language may be assertive."
- `BALANCED`  → "Operator mood: BALANCED. Maintain measured, neutral tone."
- `PRESSURED` → "Operator mood: PRESSURED. Use restrained language. Avoid amplifying uncertainty."

### Pipeline after Phase-20 refactor

```
assemble() → withCalmOmega() → withReflection(state, mood) → evaluate() → withAIDecision()
```

---

## Phase 21 — ReflectionPersistence: Rolling Window Stability Label ✅ COMPLETED 2026-02-26

**Goal:** Distinguish persistent instability from momentary instability. A single UNSTABLE reading could be noise; three UNSTABLE readings in the last 5 cycles indicate a structural problem. No new services, no schema changes.

### 21A — common-lib additions

| Artifact | Location | Role |
|---|---|---|
| `ReflectionPersistence` | `cognition/ReflectionPersistence.java` | Enum: `STABLE`, `SOFT_DRIFT`, `HARD_DRIFT`, `CHRONIC_INSTABILITY` |
| `ReflectionPersistenceCalculator` | `cognition/ReflectionPersistenceCalculator.java` | Per-symbol rolling window `ConcurrentHashMap<String, Deque<ReflectionState>>`, window = 5. Thread-safe via per-symbol `synchronized` block. |

Derivation thresholds (from window of 5 `ReflectionState` values):
- `CHRONIC_INSTABILITY` — ≥ 3 UNSTABLE
- `HARD_DRIFT`          — 1–2 UNSTABLE
- `SOFT_DRIFT`          — ≥ 2 DRIFTING, 0 UNSTABLE
- `STABLE`              — ≤ 1 DRIFTING, 0 UNSTABLE

### 21B — ReflectionResult extension

`ArchitectReflectionInterpreter.ReflectionResult` extended: `(reflectionState, calmMood, reflectionPersistence)` — all three cognition signals produced in one `interpret()` call.

`interpret()` gains a symbol-keyed overload (preferred for multi-symbol pipelines):
```java
interpret(results, stabilityPressure, calmTrajectory, divergenceTrajectory, symbol)
```
Backwards-compatible no-symbol overload delegates to a `"_global"` window key.

### 21C — DecisionContext additive field

`DecisionContext` extended from 16 → 17 fields:
- `ReflectionPersistence reflectionPersistence` — nullable; Phase-21; rolling-window label

`withReflection(ReflectionState, CalmMood, ReflectionPersistence)` is now the primary copy-factory. Two-arg `withReflection(state, mood)` overload retained (passes `null` for persistence).

### 21D — OrchestratorService

`ArchitectReflectionInterpreter.interpret()` call updated to pass `event.symbol()`.
`omegaCtx.withReflection()` call updated to pass all three: `reflectionState`, `calmMood`, `reflectionPersistence`.
Debug log updated to include `reflectionPersistence`.

---

## Phase 22 — Trading Session Intelligence ✅ COMPLETED 2026-02-27

**Goal:** System knows what time of day it is. BUY/SELL signals suppressed outside prime scalping windows. No scheduler changes — session awareness lives in the AI prompt layer.

### 22A — common-lib additions

| Artifact | Role |
|---|---|
| `TradingSession` | Enum: `OPENING_BURST`, `MIDDAY_CONSOLIDATION`, `POWER_HOUR`, `OFF_HOURS`. Method `isActiveScalpingWindow()` returns true for OPENING_BURST and POWER_HOUR |
| `TradingSessionClassifier` | Pure static. `classify(Instant)` converts to US/Eastern via `ZonedDateTime`. No I/O, no Spring dependency |

Session windows (US Eastern):
- `OPENING_BURST` — 09:30–10:30 → full BUY/SELL allowed
- `MIDDAY_CONSOLIDATION` — 10:30–14:45 → WATCH/HOLD only
- `POWER_HOUR` — 14:45–16:00 → full BUY/SELL allowed
- `OFF_HOURS` — 16:00–09:30, weekends → HOLD only

### 22B — DecisionContext additive field

`DecisionContext` extended from 17 → 18 fields: `TradingSession tradingSession` (nullable).
New copy-factory: `withTradingSession(TradingSession)`.

### 22C — OrchestratorService

Session classified in the `doOnNext` block alongside regime classification.
`decisionCtx.withTradingSession(capturedSession[0])` called before `withCalmOmega`.
Log: `[Session] tradingSession={} symbol={} traceId={}`.

### 22D — AIStrategistService prompt delta

`sessionSection` injected after `moodSection`:
- OPENING_BURST/POWER_HOUR → "Active scalping window. BUY/SELL signals are appropriate."
- MIDDAY_CONSOLIDATION → "Respond WATCH or HOLD only. Do NOT generate BUY or SELL."
- OFF_HOURS → "Market is closed. Respond HOLD only."
- `isActiveScalpingWindow()` flag passed to prompt builder to control entry/exit JSON request.

### 22E — FinalDecision v8 field

`String tradingSession` added (v8, nullable, enum name stored as string).

---

## Phase 23 — Scalping Entry/Exit Intelligence ✅ COMPLETED 2026-02-27

**Goal:** Every BUY/SELL signal in an active session includes a complete trade instruction. HOLD/WATCH have null entry/exit fields.

### 23A — AIStrategyDecision extended

Four new nullable fields added:
- `Double entryPrice` — recommended entry
- `Double targetPrice` — take-profit level (~0.3–0.5% move)
- `Double stopLoss` — hard stop (~0.15–0.25% against entry)
- `Integer estimatedHoldMinutes` — expected scalp duration (1–15 min)

Backwards-compatible 3-arg constructor `(signal, confidence, reasoning)` retained.

### 23B — AIStrategistService

`buildPrompt()`: during active scalping sessions, extends JSON response schema to include 4 entry/exit fields. During MIDDAY/OFF_HOURS, omits entry/exit request (irrelevant).
`parseResponse()`: extracts new fields null-safely.
`fallback()`: returns null entry/exit for all signals (no price data available in fallback context).

### 23C — FinalDecision v8 extended

Four more v8 fields: `Double entryPrice`, `Double targetPrice`, `Double stopLoss`, `Integer estimatedHoldMinutes`.
v8 factory `of(...)` added with all 5 scalping fields. All prior factories (v7 down to legacy) updated to pass null for new fields.

### 23D — OrchestratorService

`buildDecision()` gains `TradingSession session` parameter. Passes all 5 v8 fields from `aiDecision` to `FinalDecision.of(...)`.

---

## Phase 24 — P&L Outcome Learning Loop ✅ COMPLETED 2026-02-27

**Goal:** Close the real learning loop. After ~5–10 min, resolve actual market outcome for each BUY/SELL decision. Re-score agents based on real P&L, not signal alignment.

### 24A — DecisionHistory schema additions (additive, all nullable)

v8 scalping fields: `tradingSession`, `entryPrice`, `targetPrice`, `stopLoss`, `estimatedHoldMinutes`.
Outcome fields: `outcomePercent`, `outcomeHoldMinutes`, `outcomeResolved`.

### 24B — Repository

New query `findUnresolvedSignals(symbol, since, limit)` — returns BUY/SELL decisions without resolved outcome within a time window.

### 24C — HistoryService

Three new methods:
- `getUnresolvedSignals(symbol, sinceMins)` — public read
- `recordOutcome(traceId, outcomePercent, holdMinutes)` — explicit outcome write
- `resolveOutcomes(symbol, currentPrice)` — **batch server-side resolver**: finds open decisions with `entryPrice`, computes P&L, marks resolved, then calls `rescoreAgentsByOutcome()` to update agent performance snapshots with real market truth (not signal alignment)

`rescoreAgentsByOutcome()`: parses `agents` JSON per decision, determines if each agent's call matched actual profitable direction, calls `snapshotRepository.upsertAgent()` with outcome-based win score. This is the real learning loop — agent weights now reflect whether they called profitable trades, not whether they agreed with consensus.

### 24D — HistoryController

Three new endpoints:
- `GET /api/v1/history/unresolved/{symbol}?sinceMins=10`
- `POST /api/v1/history/outcome/{traceId}` — body: `{outcomePercent, holdMinutes}`
- `POST /api/v1/history/resolve-outcomes/{symbol}?currentPrice={price}` — batch resolver

### 24E — OrchestratorService

`resolveOpenOutcomes(symbol, ctx)` called fire-and-forget in the market data `doOnNext` block.
Calls `POST /resolve-outcomes/{symbol}?currentPrice={price}` on history-service.
Zero latency impact — pure side-effect outside the decision pipeline.

---

## Phase 25 — Session-Aware Scheduler ✅ COMPLETED 2026-02-27

**Goal:** Concentrate 30-second sampling only during active scalping windows. Pause during off-hours. Slow down midday. Saves ~50% of Alpha Vantage API quota for moments that matter.

### 25A — AdaptiveTempoStrategy extended

New primary overload: `resolve(MarketRegime, Instant)` — classifies `TradingSession` from `Instant`, then applies session gate before regime interval:

| Session | Interval override |
|---|---|
| OFF_HOURS | 30 min pause |
| MIDDAY_CONSOLIDATION | 15 min fixed |
| OPENING_BURST | Regime-driven (30s–10min) |
| POWER_HOUR | Regime-driven (30s–10min) |

Backwards-compatible `resolve(MarketRegime)` overload retained.

### 25B — MarketDataScheduler

`scheduleNextCycle` subscribe success handler updated to call `AdaptiveTempoStrategy.resolve(regime, Instant.now())`.
Log updated: `ADAPTIVE_TEMPO_SELECTED symbol={} regime={} session={} nextIntervalSeconds={}`.

**API budget impact (1 symbol, free tier 25 calls/day):**
- Before Phase 25: ~39 calls/day in CALM, unlimited in VOLATILE (exceeds 25)
- After Phase 25: ~8 calls during active windows + ~4 during midday + ~2 during off-hours = ~14 calls/day → safely within 25

---

## Phase 26 — Observation Analytics Layer ✅ COMPLETED 2026-02-27

**Goal:** Surface the 6 edge-validation correlations + risk-adjusted outcome after 30–50 resolved trades. Single endpoint tells operator whether the system has real edge before deploying capital.

### 26A — EdgeReportDTO

New record in `history-service/dto/`: 20 fields covering all validation dimensions:
- Core: `winRate`, `avgGain`, `avgLoss`, `riskRewardRatio`, `expectancy`, `maxDrawdown`
- Session: `openingBurstWinRate`, `powerHourWinRate`, `middayWinRate`
- Stability: `stableAvgOutcome`, `driftingAvgOutcome`, `unstableAvgOutcome`
- Confidence: `highConfWinRate` (>0.75), `lowConfWinRate` (≤0.75)
- Frequency: `avgTradesPerSession`
- Verdict: `hasEdge` (boolean), `verdict` (string)

### 26B — HistoryService

`getEdgeReport(symbol)`: queries all resolved trades for symbol, computes all metrics in one pass.

Verdict logic:
- `expectancy > 0 AND riskReward ≥ 1.0` → `hasEdge = true`
- `expectancy > 0.1` → "STRONG EDGE — deploy with controlled sizing"
- `expectancy > 0` → "MARGINAL EDGE — continue observing"
- `expectancy ≤ 0` → "NO EDGE — tune system before trading"

### 26C — HistoryController

`GET /api/v1/history/analytics/edge-report?symbol={symbol}`

**Usage after 2–4 weeks observation:**
```bash
curl "http://localhost:8085/api/v1/history/analytics/edge-report?symbol=IBM"
# Returns full edge report with verdict
```

---

## Phase 27 — Performance-Weighted Consensus (Path 1) ✅ COMPLETED 2026-02-27

**Goal:** Consensus guardrail uses accumulated agent performance intelligence rather than equal weights. Divergence detection now compares AI against a historically-informed signal — a stronger safety assertion.

### 27A — common-lib consensus package

| Artifact | Change |
|---|---|
| `ConsensusEngine` | Added `default compute(List<AnalysisResult>, Map<String,Double>)` — backward-compatible; delegates to `compute(results)` |
| `PerformanceWeightedConsensusStrategy` | **New class** — implements both `compute` overloads. Uses caller-supplied adaptive weights; falls back to `1.0` per agent when no weights provided |
| `DefaultWeightedConsensusStrategy` | Unchanged — retained for reference |

### 27B — agent-orchestrator

| Artifact | Change |
|---|---|
| `ConsensusIntegrationGuard` | Added `resolve(results, engine, Map<String,Double> weights)` overload — calls `engine.compute(results, weights)` |
| `OrchestratorConfig` | Bean swap: `DefaultWeightedConsensusStrategy` → `PerformanceWeightedConsensusStrategy` |
| `OrchestratorService.buildDecision()` | Guard call updated: `resolve(results, consensusEngine, adaptiveWeights)` |

**Responsibility shift:** The consensus guardrail graduated from a fixed equal-weight vote to a performance-weighted signal. A divergence between AI and consensus now carries more information — the consensus reflects which agents have historically been accurate.

---

## Phase 28 — Strategy Memory for AI (Path 9) ✅ COMPLETED 2026-02-27

**Goal:** AI strategist receives its own last 3 decisions as context. Detects signal flip-flop and regime transitions across cycles without modifying `DecisionContext` or `FinalDecision`.

### 28A — history-service

| Artifact | Change |
|---|---|
| `DecisionHistoryRepository` | Added `findRecentBySymbol(symbol, limit)` — `SELECT … ORDER BY saved_at DESC LIMIT :limit` |
| `HistoryService` | Added `getRecentDecisions(symbol, limit)` — returns `Flux<DecisionHistory>`, capped at 10 |
| `HistoryController` | Added `GET /api/v1/history/recent/{symbol}?limit=3` |

### 28B — agent-orchestrator

| Artifact | Change |
|---|---|
| `OrchestratorService` | Added `fetchStrategyMemory(symbol)` — WebClient GET, `onErrorResume → List.of()` |
| `OrchestratorService` | Restructured AI call site: `fetchStrategyMemory().flatMap(memory → evaluate(ctx, mktCtx, memory))` |
| `AIStrategistService` | Added `evaluate(DecisionContext, Context, List<Map>)` overload |
| `AIStrategistService` | Added `buildMemorySection()` + `toDouble()` helpers |
| `AIStrategistService` | `buildPrompt` 6-arg overload — memory section inserted after session section |

**Prompt memory section format (injected when non-empty):**
```
Strategy Memory (last 3 decisions, most recent first — avoid flip-flop, do not blindly continue prior trend):
  - signal=BUY    confidence=0.82  regime=TRENDING      diverged=false
  - signal=HOLD   confidence=0.65  regime=TRENDING      diverged=true
  - signal=HOLD   confidence=0.55  regime=CALM          diverged=false
```

**Responsibility shift:** AI strategist gains cross-cycle temporal awareness. Pipeline latency impact is bounded by one non-blocking history-service read; failure is silently absorbed.

---

## Phase 29 — Divergence Safety Override (Path 12) ✅ COMPLETED 2026-02-27

**Goal:** Act on divergence, not just observe it. When consensus and AI persistently disagree, apply a deterministic guardrail — signal override or confidence dampening.

### 29A — common-lib guard package

New package: `com.agentplatform.common.guard`

| Class | Role |
|---|---|
| `DivergenceGuard` | Pure static evaluator — two rules, `OverrideResult` record |

**Rule 1 — ConsensusOverride:**
- Fires when: `divergenceFlag = true` AND `consensusConfidence ≥ 0.65`
- Effect: AI signal and confidence replaced with consensus values
- Rationale: strongly-confident consensus disagreeing with AI is a high-value safety signal

**Rule 2 — ConfidenceDampen:**
- Fires when: `divergenceFlag = true` AND `recentDivergenceStreak ≥ 2`
- Effect: AI signal kept; confidence multiplied by `0.80`
- Rationale: persistent disagreement suggests AI is pattern-locked; reduce certainty without silencing it

| Constant | Value |
|---|---|
| `OVERRIDE_CONSENSUS_THRESHOLD` | `0.65` |
| `STREAK_DAMPEN_THRESHOLD` | `2` |
| `CONFIDENCE_DAMPEN_FACTOR` | `0.80` |

### 29B — agent-orchestrator

| Artifact | Change |
|---|---|
| `OrchestratorService` | Added `computeDivergenceStreak(priorDecisions)` — counts consecutive `divergenceFlag=true` from front of memory list (zero extra I/O) |
| `OrchestratorService.buildDecision()` | Added `divergenceStreak` parameter; `DivergenceGuard.evaluate()` applied post-consensus |
| `OrchestratorService` | Import: `DivergenceGuard` |

**FinalDecision field behaviour under override:**

| Field | Before | After (when guard fires) |
|---|---|---|
| `finalSignal` | AI signal | Consensus signal (Rule 1) or unchanged (Rule 2) |
| `confidenceScore` | AI confidence | Consensus confidence (Rule 1) or dampened (Rule 2) |
| `aiReasoning` | AI text | AI text + `[OVERRIDE: ConsensusOverride ...]` or `[OVERRIDE: ConfidenceDampen ...]` |
| `divergenceFlag` | Raw AI≠consensus | **Unchanged** — still reflects pre-override disagreement |

**Responsibility shift:** The orchestrator gained an active self-correction layer. The system no longer just observes disagreement — it acts on it, proportionally.

---

## Phase 30 — DivergenceGuard Refinements + Strategy Memory DTO ✅ COMPLETED 2026-02-27

**Goal:** Two targeted quality improvements identified during architect review. No new logic, no schema changes, no pipeline restructuring.

### 30A — Confidence Floor (DivergenceGuard)

`CONFIDENCE_FLOOR = 0.50` constant added. Rule 2 dampening now applies `Math.max(CONFIDENCE_FLOOR, aiConfidence × CONFIDENCE_DAMPEN_FACTOR)`.

**Effect:** Prevents sub-neutral confidence values when AI was already near 0.50. Edge case: AI at 0.55 → `0.55 × 0.80 = 0.44` was previously possible; now floors to `0.50`.

### 30B — RecentDecisionMemoryDTO projection

The `GET /recent/{symbol}` endpoint previously returned `Flux<DecisionHistory>` — the full entity with 25+ fields including `agents` JSON array, `aiReasoning`, weight snapshots.

| Artifact | Change |
|---|---|
| **New** `RecentDecisionMemoryDTO` | 4-field record: `finalSignal`, `confidenceScore`, `divergenceFlag`, `marketRegime` |
| `HistoryService.getRecentDecisions()` | Maps `DecisionHistory` → `RecentDecisionMemoryDTO` in service layer |
| `HistoryController.recentDecisions()` | Return type: `Flux<DecisionHistory>` → `Flux<RecentDecisionMemoryDTO>` |
| `AIStrategistService` | No change — JSON field names identical; `Map<String,Object>` parsing unaffected |

**Payload reduction:** ~10KB (full entity × 3) → ~200 bytes (4 fields × 3).

**Architect note on override cooldown (proposed, deferred):** The proposed cooldown to suppress Rule 1 on the cycle after a ConsensusOverride was assessed and deferred — it could suppress correct consecutive overrides in sustained disagreement scenarios, and detection via `aiReasoning` substring parsing is fragile. Defer until real data shows cascading overrides are a problem.

---

*Review last updated: 2026-02-27. Phases 27–30 verified against codebase. 10 modules total. DivergenceGuard active. Strategy memory active. Performance-weighted consensus active. Next evolution is data-driven — run the system before adding more layers.*

---

## Phase 31 — Real P&L Feedback Loop ✅ COMPLETED 2026-02-27

**Goal:** Replace the self-referential AI-alignment win rate (did the agent agree with the final signal?) with a market-truth win rate derived from actual P&L outcomes.

**Core change:** `HistoryService.rescoreAgentsByOutcome()` is called after each `resolveOutcomes()` — after the exit candle is known. Agent win rate now means "did the agent's signal direction align with a profitable trade outcome?", not "did the agent agree with the orchestrator's final signal?".

**Threshold:** Profitable = `outcomePercent > 0.10` (above spread/commission noise). `getAgentFeedback()` requires ≥5 resolved outcomes per agent before replacing the 0.5 fallback, preventing noise on sparse data.

**New endpoints:**
- `GET /api/v1/history/feedback-loop-status` — per-agent win rate, sample size, weight source (`market-truth` vs `fallback`)

**Verification:** After 5+ resolved trades, `feedback-loop-status` should show `source: market-truth` for all agents with non-0.5 win rates.

---

## Phase 32 — Historical Replay Tuning Engine ✅ COMPLETED 2026-02-28

**Goal:** Eliminate the cold-start problem. Six months of real Nifty 5-min OHLCV candles are replayed through the **actual Java pipeline** — same agents, same AI strategist, same DivergenceGuard, same P&L feedback loop — at full speed. Agent weights are pre-trained and the EdgeReport is seeded with real outcomes before the first live trade.

**Why not Python backtest?** A Python backtest tunes Python replicas, not the real Java agents. Phase 32 tunes the real system.

**Data source:** Angel One SmartAPI (`/rest/secure/angelbroking/historical/v1/getCandleData`) with existing auth. Symbol: `99926000` (NIFTY 50), interval: `FIVE_MINUTE`, range: configurable (default ~60 days). Chunked in 30-day API calls.

**Architecture:**
```
[UI ReplayPanel]
    ↓ POST /api/v1/market-data/replay/fetch-history   → download candles → store in replay_candles
    ↓ POST /api/v1/market-data/replay/start           → begin replay loop
    ↑ GET  /api/v1/market-data/replay/status          → poll progress

[ReplayRunnerService] (market-data-service, profile: historical-replay)
    → loads candles from history-service into HistoricalReplayProvider
    → for each candle: POST /api/v1/orchestrate/trigger (real candle timestamp)
    → after 2s: fetch latest snapshot → resolve P&L → POST /api/v1/history/outcome/{traceId}

[HistoricalReplayProvider] (market-data-service, @Primary, profile: historical-replay)
    → cursor-based real OHLCV provider
    → builds sliding recentClosingPrices window from real historical prices
    → cursor set externally by ReplayRunnerService before each trigger

[history-service]
    → replay_candles table (UNIQUE symbol+candle_time)
    → ingest: POST /api/v1/history/replay-candles/ingest
    → fetch:  GET  /api/v1/history/replay-candles/{symbol}
    → delete: DELETE /api/v1/history/replay-candles/{symbol}
```

**Session simulation:** Candle timestamps are real UTC times. `TradingSessionClassifier.classify(event.triggeredAt())` already classifies OPENING_BURST/POWER_HOUR correctly from the candle time — no changes needed.

**P&L resolution (explicit, per-trade):**
1. `ReplayRunnerService` generates a `traceId` before each trigger
2. After 2s, fetches snapshot for symbol → gets `finalSignal`, `entryPrice`, `estimatedHoldMinutes`
3. `exitIdx = min(i + estimatedHoldMinutes/5, candles.size()-1)`
4. `outcome = (candles[exitIdx].close - entryPrice) / entryPrice × 100`
5. Posts to `POST /api/v1/history/outcome/{traceId}` → triggers `rescoreAgentsByOutcome()` immediately

**Profile activation:**
```yaml
SPRING_PROFILES_ACTIVE: historical-replay
```
`HistoricalReplayProvider` becomes `@Primary`, overriding live `MarketDataService`.

**Files changed:**

| Module | File | Change |
|---|---|---|
| history-service | `schema.sql` | Added `replay_candles` table + UNIQUE constraint + index |
| history-service | NEW `model/ReplayCandle.java` | R2DBC entity |
| history-service | NEW `dto/ReplayCandleDTO.java` | Transfer record |
| history-service | NEW `repository/ReplayCandleRepository.java` | Reactive repository |
| history-service | NEW `service/ReplayCandleService.java` | Ingest + fetch + delete |
| history-service | `controller/HistoryController.java` | 4 new endpoints under `/replay-candles/` |
| market-data-service | `client/MarketDataWebClient.java` | Added `fetchHistoricalCandles()` — 30-day chunked Angel One calls |
| market-data-service | NEW `replay/ReplayCandleDTO.java` | Local mirror DTO |
| market-data-service | NEW `replay/ReplayState.java` | Mutable progress state |
| market-data-service | NEW `replay/HistoricalReplayProvider.java` | `@Primary @Profile("historical-replay")` OHLCV provider |
| market-data-service | NEW `replay/ReplayRunnerService.java` | Replay loop driver |
| market-data-service | NEW `replay/ReplayController.java` | `/api/v1/market-data/replay/` REST API |
| market-data-service | NEW `application-historical-replay.yml` | Profile config |
| docker-compose.dev.yml | — | Added `HISTORY_URL` + `ORCHESTRATOR_URL` to market-data-service env |
| ui | NEW `src/components/ReplayPanel.jsx` | Operator control panel |
| ui | `src/App.jsx` | Added `<ReplayPanel />` to dashboard |

**Post-replay observable improvements:**

| Component | Before Replay | After 60-Day Replay |
|---|---|---|
| Agent weights | Equal (0.5 cold start) | Tuned to real Nifty 5-min intraday |
| Market-truth win rates | 0.5 neutral fallback | Real rates from thousands of decisions |
| EdgeReport | Empty | Win rate, expectancy, session breakdown |
| DivergenceGuard threshold | Unvalidated 0.65 | Empirically observable from replay data |
| Winning windows | Unknown | OPENING_BURST+TRENDING vs POWER_HOUR+CALM measurable |

**Technical debt introduced:** Replay runs sequentially (one candle per 2s+ settle time) — 8,400 candles takes ~5 hours. A future optimisation could reduce `PIPELINE_SETTLE_MS` to 500ms for faster replay once the AI rate limits are verified.

---

*Review last updated: 2026-02-28. Phases 27–32 verified against codebase. DivergenceGuard active. Strategy memory active. Real P&L feedback loop active. Historical replay tuning engine active.*
