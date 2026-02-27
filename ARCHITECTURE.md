# Agent Platform — Internal Architecture Document
**Classification:** Internal Engineering Reference
**Audience:** Senior Backend Engineers, Staff Engineers, Principal Architects
**Source of truth:** Codebase at `agent-platform/` (Spring Boot 3.2.4, Java 21, WebFlux)
**Last updated:** 2026-02-26

---

## Table of Contents

1. [System Purpose & Architectural Style](#1-system-purpose--architectural-style)
2. [Real Architecture Mapping](#2-real-architecture-mapping)
3. [Deep Layer Analysis](#3-deep-layer-analysis)
4. [End-to-End Execution Flow](#4-end-to-end-execution-flow)
5. [Concurrency & Thread Model](#5-concurrency--thread-model)
6. [Data & Transaction Boundaries](#6-data--transaction-boundaries)
7. [Observability & Logging Internals](#7-observability--logging-internals)
8. [Deployment & Runtime Architecture](#8-deployment--runtime-architecture)
9. [Hidden Design Decisions](#9-hidden-design-decisions)
10. [Developer Mental Model](#10-developer-mental-model)
11. [Intelligence Architecture Mapping](#11-intelligence-architecture-mapping)
12. [Runtime Sequence Diagram](#12-runtime-sequence-diagram)
13. [Momentum & Market State Architecture](#13-momentum--market-state-architecture)
14. [Trade Posture & Exit Awareness Architecture](#14-trade-posture--exit-awareness-architecture)
15. [Adaptive Risk Engine Architecture](#15-adaptive-risk-engine-architecture)
16. [Trade Service Architecture](#16-trade-service-architecture)

---

## 1. System Purpose & Architectural Style

### What the system does

This platform solves a specific problem: making repeatable, structured, multi-perspective stock market analysis decisions using heterogeneous signals — technical indicators, risk metrics, portfolio rules, and AI-generated insight — unified under an AI-primary decision architecture with a consensus safety guardrail. It does not trade. It does not connect to a broker. Its output is a `FinalDecision` record: a typed, versioned, fully-traced artifact that declares a signal (`BUY`, `SELL`, `HOLD`, `WATCH`) with a confidence score, agent breakdown, adaptive weights, AI reasoning, divergence awareness, and the market regime under which the decision was made.

Every decision is persisted and fed back into future decisions. Over time, agents whose signals align more often with the consensus accumulate higher adaptive weights. Agents that run faster get lower latency penalties. An AI strategist (Claude API) synthesises all agent signals, adaptive weights, and regime context into the primary recommendation. A consensus engine runs downstream as a safety guardrail, and divergence between AI and consensus signals is tracked and persisted. This creates a self-refining loop without any ML model training — pure statistical aggregation over stored outcomes, augmented by LLM intelligence.

The system exposes a React-based UI that consumes snapshot data from history-service, presenting the latest decision per symbol in a card-style layout with regime context, AI explainability badges, divergence indicators, and detailed AI reasoning modals. A separate **trade-service** provides operator-facing trade lifecycle management with momentum tracking, posture interpretation, adaptive risk envelopes, and exit awareness — all as informational intelligence, never automated execution.

### Architectural Pattern

The system is a **reactive microservices platform** with an **intelligence pipeline** at its center. Each service is independently deployable. Communication is exclusively synchronous HTTP (WebClient, non-blocking). There is no message broker, no shared database, no service mesh, and no API gateway in the current implementation.

The architecture exhibits four distinct sub-patterns layered together:

1. **Orchestrator pattern**: `agent-orchestrator` owns the full decision lifecycle. It coordinates all downstream calls, assembles the result, and dispatches side-effects. No other service calls another service (except `scheduler-service` which queries `history-service` for regime state).

2. **Pipeline pattern inside the orchestrator**: The `orchestrate()` method is a single, non-branching `Mono` chain. Each stage transforms the data type and passes it downstream. A unified `DecisionContext` snapshot travels through the pipeline, accumulating intelligence at each stage. The pipeline is assembled once and subscribed once per request.

3. **Adaptive feedback loop**: `history-service` stores every `FinalDecision` in PostgreSQL. On the next orchestration cycle, the orchestrator fetches aggregated agent performance and feedback metrics, which flow into `AgentScoreCalculator` to produce regime-aware, history-adjusted weights before the AI strategist evaluates.

4. **Regime-aware temporal control loop**: `scheduler-service` reads the latest market regime from `history-service` after each orchestration cycle and adjusts the next trigger interval accordingly. VOLATILE markets are polled every 30 seconds; CALM markets every 10 minutes. This creates a closed feedback loop: decisions influence future trigger frequency.

### Architectural Planes: Control Plane vs Decision Plane

The system can be understood as two distinct operational planes that collaborate without coupling directly to each other's internals.

**Decision Plane** — the cluster of services and logic responsible for producing, evaluating, and refining the market signal:

- `analysis-engine` — executes all registered `AnalysisAgent` beans and returns per-agent `AnalysisResult` records
- `AIStrategistService` — primary decision intelligence: synthesises agent signals, adaptive weights, and regime into a strategic recommendation via Claude API
- `DefaultWeightedConsensusStrategy` — aggregates agent signals into a `ConsensusResult` (safety guardrail, no longer the decision authority)
- `AgentScoreCalculator` — computes regime-aware, history-adjusted weights that refine how agent outputs should be valued
- `MarketRegimeClassifier` — classifies the prevailing market condition to modulate agent weight assignments and model selection
- `ModelSelector` — maps market regime to the optimal Claude model (Haiku for VOLATILE, Sonnet for all others)
- `HistoryService` aggregation — feeds historical performance and feedback back into the next scoring cycle
- `DecisionContext` — unified immutable snapshot that captures the full decision state at each pipeline stage

Everything in the Decision Plane is stateless, pure, and composable. Its contracts are defined in `common-lib`. Adding a new agent, a new classifier, or a new weight strategy requires no changes outside the Decision Plane's own boundaries.

**Control Plane** — the infrastructure layer responsible for timing, coordination, and pacing the system's execution:

- `scheduler-service` — governs *when* the orchestration cycle fires, for *which* symbols, and at what cadence. Implements adaptive tempo: regime-aware polling intervals from 30 seconds (VOLATILE) to 10 minutes (CALM)
- `agent-orchestrator` — governs *how* the pipeline executes: the ordering of calls, the assembly of `DecisionContext`, the sequencing of AI evaluation and consensus guardrail, and the dispatch of side-effects
- `MarketDataCache` — regime-aware in-memory cache in `market-data-service` that controls how often external API calls are made, with TTL driven by detected market regime

The Control Plane drives the Decision Plane by injecting `MarketDataEvent` triggers at adaptive intervals. It reads regime state from the Decision Plane (via `history-service`) to govern its own pacing. The scheduler has evolved from a fixed-cron trigger into a full **temporal control layer** with per-symbol adaptive scheduling.

### Key Frameworks and Dependencies

| Dependency | Version | Role |
|---|---|---|
| Spring Boot | 3.2.4 | Auto-configuration, dependency injection, lifecycle |
| Spring WebFlux | (Boot-managed) | Reactive HTTP server/client on Netty |
| Project Reactor | (Boot-managed) | `Mono`/`Flux` reactive types, context propagation |
| Spring Data R2DBC | (Boot-managed) | Non-blocking SQL access in history-service |
| PostgreSQL (R2DBC) | (Boot-managed) | Persistent relational store for decision history |
| Jackson | (Boot-managed) | JSON serialization; `JavaTimeModule` registered in each service |
| Lombok | 1.18.x | `@Data`, `@NoArgsConstructor` on mutable entities only |
| Anthropic Claude API | External | Regime-aware model selection: `claude-haiku-4-5-20251001` (VOLATILE) / `claude-sonnet-4-6` (others) |
| Alpha Vantage API | External | `TIME_SERIES_DAILY` endpoint in `market-data-service` |
| Docker / Compose | v5.0.2 | Container orchestration for local and deployment |
| React 18 + Vite | Frontend | Card-style decision dashboard with Framer Motion + TailwindCSS |

---

## 2. Real Architecture Mapping

### Module Inventory

```
agent-platform/
├── common-lib/               Shared types: models, interfaces, utilities, pure logic
│                              (momentum, posture, structure, risk, reflection)
├── market-data-service/      External market data ingestion + regime-aware cache (port 8080)
├── analysis-engine/          Multi-agent analysis execution (port 8083)
├── agent-orchestrator/       Central pipeline coordinator + AI strategist (port 8081)
├── history-service/          Decision persistence, aggregation, and snapshot API (port 8085)
├── notification-service/     Slack/webhook dispatch (port 8084)
├── scheduler-service/        Adaptive tempo trigger source (port 8082)
├── trade-service/            Operator trade lifecycle + risk awareness (port 8086)
└── ui/                       React dashboard (Vite dev server port 3000, proxies /api → 8085)
```

### Service Responsibilities

**`common-lib`** — Zero runtime behavior. Pure shared contract: records, enums, interfaces, and stateless utilities. Every other module depends on it. Changes here have blast radius across the entire system. Key inhabitants:
- Domain records: `Context`, `AnalysisResult`, `FinalDecision` (17 components, v7), `DecisionContext` (11 fields, two-phase lifecycle), `AIStrategyDecision`, `MarketRegime`, `AgentFeedback`, `AgentPerformanceModel`, `MarketDataEvent`
- Trade & risk records: `ActiveTradeContext`, `ExitAwareness`, `RiskEnvelope`, `AdaptiveRiskState`, `TradeReflectionStats`
- Enums: `MarketState` (CALM/BUILDING/CONFIRMED/WEAKENING), `TradePosture` (CONFIDENT_HOLD/WATCH_CLOSELY/DEFENSIVE_HOLD/EXIT_CANDIDATE), `StructureSignal` (CONTINUATION/STALLING/FATIGUED)
- Contracts: `ConsensusEngine`, `DecisionEventPublisher`, `AnalysisAgent`
- Stateless computation: `AgentScoreCalculator`, `MarketRegimeClassifier`, `TechnicalIndicators` (in analysis-engine)
- Cognitive interpreters: `MomentumStateCalculator` (4-dimensional momentum classification), `TradePostureInterpreter` (posture derivation with stability window), `StructureInterpreter` (momentum structure classification), `ReflectionInterpreter` (posture-level trade outcome statistics)
- Risk computation: `AdaptiveRiskEngine` (exit awareness, risk envelopes, adaptive smoothing)
- Posture state: `PostureStabilityState` (60s downgrade suppression)
- Reactive utilities: `TraceContextUtil`
- Custom exception: `AgentException`

**`market-data-service`** — Translates Alpha Vantage's time-series response into a `MarketDataQuote` record. Includes a **regime-aware in-memory cache** (`MarketDataCache`) that classifies the market regime from fetched price data and applies regime-specific TTL: VOLATILE → 2 min, TRENDING → 5 min, RANGING → 7 min, CALM → 10 min. Cache hits avoid redundant external API calls. Thread-safe via `ConcurrentHashMap`. It does not call any internal service.

**`analysis-engine`** — Executes all registered `AnalysisAgent` beans synchronously in `AgentDispatchService.dispatchAll()`. Returns `List<AnalysisResult>`. This service is stateless — it holds no session, no cache, no database. Each agent is a pure function over `Context`.

**`agent-orchestrator`** — The only service with a complex dependency graph. It holds WebClient beans for every upstream and downstream service. It assembles the full reactive pipeline, classifies market regime, fetches adaptive weight data, invokes `AgentScoreCalculator`, assembles a `DecisionContext` snapshot, selects the regime-appropriate Claude model via `ModelSelector`, delegates to `AIStrategistService` for the primary strategic recommendation, runs consensus as a safety guardrail, computes divergence between AI and consensus signals, enriches the `DecisionContext` with post-AI fields, builds the `FinalDecision`, and dispatches fire-and-forget side-effects to history and notification. All orchestration logic lives in `OrchestratorService.orchestrate()`.

**`history-service`** — Persistence and projection layer for `FinalDecision` using **PostgreSQL via R2DBC**. Exposes nine endpoints consumed by the orchestrator (performance, feedback), the scheduler (latest regime), the UI (snapshot, market-state, SSE stream), and trade-service (decision-metrics, agent-performance-snapshot). On each `save()`, triggers a non-fatal projection pipeline that updates pre-aggregated `agent_performance_snapshot` and `decision_metrics_projection` tables — eliminating full-table scans for the feedback loop. The snapshot endpoint returns the most recent decision per symbol as a lightweight `SnapshotDecisionDTO`. `getMarketState()` computes per-symbol `MarketState` using `MomentumStateCalculator` over recent decision history. A `GET /stream` SSE endpoint emits real-time snapshot events via `Sinks.Many`. Data survives container restarts — the feedback loop maintains its accumulated intelligence.

**`notification-service`** — Thin wrapper around Slack's incoming webhook API. Receives `FinalDecision` via HTTP POST, formats a message, fires it to Slack. When Slack is disabled (`SLACK_ENABLED=false`), it logs to stdout instead. Fully non-critical — failures here do not affect the orchestration result.

**`scheduler-service`** — Adaptive tempo trigger. Each symbol runs an independent reactive loop: `Mono.delay(interval) → triggerOrchestration() → fetchLatestRegime() → compute next interval → recurse`. Regime-to-interval mapping: VOLATILE → 30s, TRENDING → 2min, RANGING → 5min, CALM → 10min, UNKNOWN → 5min (fallback). The scheduler thread is never blocked — each orchestrator call is a fire-and-forget reactive subscription.

**`trade-service`** — Operator intelligence layer for trade lifecycle and risk awareness. Manages trade sessions (start/exit), computes exit awareness via `AdaptiveRiskEngine`, tracks per-symbol adaptive risk state and posture stability in memory. Reads decision metrics and market state projections from `history-service` (read-only). Maintains posture-level trade reflection statistics via `ReflectionInterpreter`. No orchestrator linkage — purely operator-facing. Key endpoints: `POST /trade/start`, `POST /trade/exit`, `GET /trade/active/{symbol}`.

**`ui/`** — React 18 single-page application built with Vite. TailwindCSS for styling, Framer Motion for animations. Fetches `GET /api/v1/history/snapshot` and renders one card per symbol showing: regime header strip, AI intent badge (diverging/aligned), model label (haiku-fast/sonnet-deep), signal with confidence bar, decision strength label, guardrail awareness indicator, and AI reasoning modal. Includes `MomentumBanner` (per-symbol momentum state pills: CALM/BUILDING/CONFIRMED/WEAKENING) and `TacticalPostureHeader` (active trade posture, status chips for momentum/structure/duration, confidence trend sparkline). Dev server on port 3000 proxies `/api` requests to history-service on port 8085.

### Entry Points

| Service | Entry Point | Type |
|---|---|---|
| scheduler-service | `MarketDataScheduler.startAdaptiveScheduling()` | `@PostConstruct` adaptive loop |
| agent-orchestrator | `OrchestratorController.trigger()` | `POST /api/v1/orchestrate/trigger` |
| market-data-service | `MarketDataController.getQuote()` | `GET /api/v1/market-data/quote/{symbol}` |
| analysis-engine | `AnalysisController.analyze()` | `POST /api/v1/analyze` |
| history-service | `HistoryController.save()` | `POST /api/v1/history/save` |
| history-service | `HistoryController.snapshot()` | `GET /api/v1/history/snapshot` |
| history-service | `HistoryController.latestRegime()` | `GET /api/v1/history/latest-regime?symbol={symbol}` |
| history-service | `HistoryController.marketState()` | `GET /api/v1/history/market-state` |
| history-service | `HistoryController.decisionMetrics()` | `GET /api/v1/history/decision-metrics/{symbol}` |
| history-service | `HistoryController.agentPerformanceSnapshot()` | `GET /api/v1/history/agent-performance-snapshot` |
| trade-service | `TradeController.startTrade()` | `POST /api/v1/trade/start` |
| trade-service | `TradeController.exitTrade()` | `POST /api/v1/trade/exit` |
| trade-service | `TradeController.getActiveTrade()` | `GET /api/v1/trade/active/{symbol}` |
| notification-service | `NotificationController.notifyDecision()` | `POST /api/v1/notify/decision` |

### Hidden Architectural Patterns

**Versioned record evolution**: `FinalDecision` has 17 record components across 7 named version groups (v1–v7). Eight factory overloads exist for backward compatibility. New fields are always nullable, always additive, and the schema adds them as nullable SQL columns. This is an explicit schema evolution strategy without migration tooling.

**Two-phase immutable snapshot**: `DecisionContext` is assembled pre-AI via `DecisionContext.assemble()` and enriched post-AI via `DecisionContext.withAIDecision()`. The record is never mutated. Defensive copies via `List.copyOf()` and `Map.copyOf()` prevent external modification. This pattern decouples pipeline stages from parameter-passing complexity.

**Guard pattern at consensus boundary**: `ConsensusIntegrationGuard.resolve()` is a static method that wraps the `ConsensusEngine` call. It intercepts null or empty result lists and returns a safe fallback (`HOLD, 0.0, emptyMap`) before the engine is ever invoked. This prevents `DefaultWeightedConsensusStrategy` from ever dividing by zero or processing empty collections.

**Degradable adapter pattern**: Both `PerformanceWeightAdapter` and `AgentFeedbackAdapter` have identical failure semantics: on any error, they return `Mono.just(Map.of())`. The pipeline downstream treats an empty map as "no data available" and falls back to default weights. The orchestrator never knows whether history-service was unreachable or simply had no stored data.

**Agent auto-discovery**: `AgentDispatchService` is constructor-injected with `List<AnalysisAgent>`. Spring collects all `@Component` beans implementing `AnalysisAgent` at startup. Adding a new agent is purely additive — register a bean, no other wiring change required.

**Regime-aware model selection**: `ModelSelector` maps `MarketRegime` → Claude model deterministically. VOLATILE markets use `claude-haiku-4-5-20251001` (cheap, fast — decisions are short-lived) while all other regimes use `claude-sonnet-4-6` (deeper reasoning justifies cost when decision intervals are longer). `ModelSelector.resolveLabel()` provides human-readable labels (`haiku-fast` / `sonnet-deep`) for observability and UI.

**Regime-aware caching**: `MarketDataCache` classifies the market regime at cache-put time using `MarketRegimeClassifier` and selects TTL accordingly. This reuses the existing classifier via a minimal `Context` constructed from the `MarketDataQuote` — no logic duplication.

---

## 3. Deep Layer Analysis

### API / Controller Layer

All controllers use `@RestController` and return either `Mono<ResponseEntity<T>>` (WebFlux reactive), `Flux<T>` (streaming), or `ResponseEntity<T>` (synchronous, used in `analysis-engine` and `notification-service`).

**`OrchestratorController`** (`com.agentplatform.orchestrator.controller`):
- Accepts `MarketDataEvent` as `@RequestBody`
- Delegates entirely to `OrchestratorService.orchestrate(event)`
- Returns `Mono<ResponseEntity<List<AnalysisResult>>>`
- The return type is deliberately `List<AnalysisResult>` not `FinalDecision` — the REST contract exposes agent outputs, not the internal decision structure

**`HistoryController`** (`com.agentplatform.history.controller`):
- Ten endpoints, all reactive
- `POST /save` calls `historyService.save()`, chains `.then(Mono.just(ok))` — the entity itself is discarded from the response. Save triggers projection pipeline (agent snapshots + decision metrics) as a non-fatal side-effect
- `GET /agent-performance` and `GET /agent-feedback` now read from pre-aggregated `agent_performance_snapshot` table instead of full-table scans
- `GET /snapshot` returns `Flux<SnapshotDecisionDTO>` — one DTO per symbol, most recent decision. Designed for the card-style UI
- `GET /market-state` returns `Flux<MarketStateDTO>` — per-symbol momentum state (CALM/BUILDING/CONFIRMED/WEAKENING) computed by `MomentumStateCalculator` over recent decision windows
- `GET /latest-regime?symbol={symbol}` returns the regime string for the scheduler's adaptive tempo
- `GET /agent-performance-snapshot` returns `Flux<AgentPerformanceSnapshot>` — pre-aggregated agent metrics from the projection table
- `GET /decision-metrics/{symbol}` returns `Mono<DecisionMetricsDTO>` — per-symbol trend metrics (confidence slope, divergence streak, momentum streak) from the projection table
- `GET /stream` returns SSE `Flux<ServerSentEvent<SnapshotDecisionDTO>>` — real-time push updates on new decisions
- `GET /health` for Docker Compose health checks

**`AnalysisController`** (`com.agentplatform.analysis.controller`):
- Synchronous `ResponseEntity<List<AnalysisResult>> analyze(@RequestBody Context context)`
- The analysis engine is the only service that does not return a reactive type from its controller. WebFlux handles this via its `Mono.just()` wrapping internally — the handler method returns synchronously, which is acceptable because the underlying work (agent computation) is CPU-bound and fast

**`MarketDataController`** (`com.agentplatform.marketdata.controller`):
- Returns `Mono<ResponseEntity<MarketDataQuote>>`
- Error mapped to 500 via `.onErrorResume` — the raw exception is never exposed to callers

**`TradeController`** (`com.agentplatform.trade.controller`):
- `POST /start` accepts `TradeStartRequest`, returns `Mono<TradeSession>` — creates a new trade session with entry fields (price, confidence, regime, momentum)
- `POST /exit` accepts `TradeExitRequest`, returns `Mono<TradeSession>` — closes the most recent open trade, calculates PnL and duration, updates reflection stats
- `GET /active/{symbol}` returns `Mono<ActiveTradeResponse>` — fetches decision metrics and market state from history-service, computes exit awareness and risk envelope via `AdaptiveRiskEngine`, returns the full `ActiveTradeContext` + `ExitAwareness` + `RiskEnvelope` bundle
- `GET /history` returns `Flux<TradeSession>` — all closed trades ordered by exit time descending
- `GET /reflection-stats` returns `Mono<Map<String, TradeReflectionStats>>` — posture-level trade outcome statistics

### Business Service Layer

**`OrchestratorService`** (`com.agentplatform.orchestrator.service`):

The most complex class in the system. Its `orchestrate(MarketDataEvent)` method returns `Mono<List<AnalysisResult>>` assembled inside a `Mono.defer()`. The defer is critical — it ensures `startTime` and `regime[]` are captured fresh on each subscription, not at assembly time.

The `regime` capture uses a one-element `MarketRegime[]` array. This is the idiomatic Java pattern for capturing a mutable reference from a lambda in a reactive pipeline without introducing an `AtomicReference`. It works safely here because the pipeline has single-subscriber semantics (enforced by `Mono.defer`).

The orchestrator now manages a `DecisionContext` snapshot through two lifecycle phases:
1. **Pre-AI assembly**: `DecisionContext.assemble(symbol, timestamp, traceId, regime, results, adaptiveWeights, latestClose)` — captures all known state before AI evaluation
2. **Post-AI enrichment**: `decisionCtx.withAIDecision(aiDecision, consensusScore, divergenceFlag, modelLabel)` — immutable copy with AI-evaluated fields populated

**`AIStrategistService`** (`com.agentplatform.orchestrator.ai`):

Primary decision intelligence. Calls the Anthropic Claude API reactively (no `.block()`) to synthesise agent signals, adaptive weights, and market regime into a strategic recommendation. Accepts both the original four-argument signature and a `DecisionContext`-based overload. Returns `AIStrategyDecision(finalSignal, confidence, reasoning)`.

Regime-aware prompt behavior: during VOLATILE regimes, the prompt instructs the model to "Respond with ONLY one short sentence explaining reasoning" — minimizing token cost and latency for short-lived decisions. During all other regimes, it requests "concise strategic rationale."

On failure or missing API key, falls back to a rule-based majority vote over agent signals. The fallback is immediate and deterministic — the orchestration pipeline never stalls.

**`AgentDispatchService`** (`com.agentplatform.analysis.service`):
- Holds `List<AnalysisAgent>` injected by Spring
- Iterates sequentially — no parallel agent execution
- Each agent's `analyze()` is wrapped in try/catch; on exception, a degraded `AnalysisResult` with `HOLD` signal and error metadata is returned rather than propagating the error

**`HistoryService`** (`com.agentplatform.history.service`):
- `save()` uses `Mono.fromCallable(() -> toEntity(decision))` to wrap the synchronous Jackson serialization in a reactive type before flatMapping to `repository::save`. After persistence, triggers `updateProjections()` as a non-fatal side-effect chain
- `updateProjections()` runs two projection updates sequentially: `updateAgentSnapshots()` (upserts per-agent running averages into `agent_performance_snapshot`) and `updateDecisionMetrics()` (upserts per-symbol trend metrics into `decision_metrics_projection`). Both are wrapped in `onErrorResume` — projection failure never blocks persistence
- `getAgentPerformance()` and `getAgentFeedback()` now read from the pre-aggregated `agent_performance_snapshot` table instead of scanning the full `decision_history` table. This eliminates the O(N) memory and CPU cost that previously grew linearly with uptime
- `getDecisionMetrics(String symbol)` reads from the `decision_metrics_projection` table — returns confidence slope (5-point linear regression), divergence streak count, and momentum streak count
- `getMarketState()` computes per-symbol `MarketState` by reading the last N decisions (window size 8), extracting signals/confidences/divergence flags/regimes, and delegating to `MomentumStateCalculator.classify()`
- `getLatestSnapshot()` uses a custom `@Query` with `GROUP BY symbol + MAX(saved_at)` join to return one row per symbol efficiently. Benefits from composite index `idx_decision_history_symbol_savedat`
- `getLatestRegime(String symbol)` returns the regime from the most recent decision for a symbol, defaulting to `"UNKNOWN"`
- `streamSnapshots()` exposes a `Sinks.Many<SnapshotDecisionDTO>` for SSE — emits the new snapshot on each save
- `toEntity(DecisionContext)` provides an alternative mapping path from the unified `DecisionContext` — not yet wired into the save pipeline but ready for future adoption

**`MarketDataService`** (`com.agentplatform.marketdata.service`):
- Now integrated with `MarketDataCache`. `getQuote()` uses `Mono.defer()` to check the cache first — on hit, returns `Mono.just(cached.data())` immediately. On miss, delegates to `MarketDataWebClient`, then stores the result via `cache.put(symbol, quote)` in a `doOnSuccess` callback.

### Domain / Entity Models

All domain models in `common-lib` are Java 16+ **records** — immutable, value-based, with compiler-generated accessors, `equals`, `hashCode`, and `toString`. Records are appropriate here because domain objects are pure data containers passed between services.

The single exception is `DecisionHistory` in `history-service`, which is a mutable class annotated with Lombok `@Data` and `@NoArgsConstructor`. R2DBC requires a no-arg constructor and mutable setters for entity mapping. This is a persistence-layer necessity, not a domain modeling choice.

**`FinalDecision`** — 17 record components across 7 version groups:
- v1 (7 fields): symbol, timestamp, agents, finalSignal, confidenceScore, metadata, traceId
- v2 (4 fields): decisionVersion, orchestratorVersion, agentCount, decisionLatencyMs
- v3 (2 fields): consensusScore, agentWeightSnapshot
- v4 (1 field): adaptiveAgentWeights
- v5 (1 field): marketRegime
- v6 (1 field): aiReasoning
- v7 (1 field): divergenceFlag (Boolean — true when AI signal differs from consensus signal)

Eight factory overloads cascade nulls for backward compatibility. The v7 factory is the only one used by the active orchestration pipeline.

**`DecisionContext`** — 11 fields in a single record with two-phase lifecycle:
- Pre-AI fields (7): symbol, timestamp, traceId, regime, agentResults, adaptiveWeights, latestClose
- Post-AI fields (4, nullable): consensusScore, aiDecision, divergenceFlag, modelLabel
- `assemble()` — pre-AI snapshot with defensive copies via `List.copyOf()` / `Map.copyOf()`
- `withAIDecision()` — enrichment copy-factory, returns new instance with AI fields populated

**`SnapshotDecisionDTO`** — Lightweight projection for the UI: symbol, finalSignal, confidence, marketRegime, divergenceFlag, aiReasoning, savedAt.

### Repository / Data Access Layer

Two services have databases: `history-service` and `trade-service`, both using **PostgreSQL via R2DBC** against the same `agent_platform` database.

**`DecisionHistoryRepository`** extends `ReactiveCrudRepository<DecisionHistory, Long>`:

```
public interface DecisionHistoryRepository
    extends ReactiveCrudRepository<DecisionHistory, Long> {
    Flux<DecisionHistory> findBySymbol(String symbol);
    Flux<DecisionHistory> findBySymbolOrderBySavedAtDesc(String symbol);
    Flux<DecisionHistory> findByTraceId(String traceId);

    @Query("SELECT d.* FROM decision_history d INNER JOIN (...) latest ON ...")
    Flux<DecisionHistory> findLatestPerSymbol();
}
```

`findLatestPerSymbol()` uses a custom `@Query` with a correlated subquery. Optimized by composite index `idx_decision_history_symbol_savedat ON decision_history(symbol, saved_at DESC)`.

**`AgentPerformanceSnapshotRepository`** extends `ReactiveCrudRepository<AgentPerformanceSnapshot, String>`:
- `upsertAgent(agentName, confidence, latencyMs, win, regimeBias)` — custom `@Modifying @Query` that inserts or updates running averages using inline calculations (`SET total_decisions = total_decisions + 1, sum_confidence = sum_confidence + :confidence, ...`)
- `normalizeLatencyWeights(maxAvgLatency)` — normalizes all `latency_weight` values to [0.0, 1.0] range

**`DecisionMetricsProjectionRepository`** extends `ReactiveCrudRepository<DecisionMetricsProjection, String>`:
- `upsertMetrics(symbol, lastConfidence, slope5, divStreak, momStreak)` — custom `@Modifying @Query` that inserts or updates per-symbol trend metrics

**`TradeSessionRepository`** (in trade-service) extends `ReactiveCrudRepository<TradeSession, Long>`:
- `findBySymbolOrderByEntryTimeDesc(symbol)`, `findByExitTimeIsNullOrderByEntryTimeDesc()`, `findBySymbolAndExitTimeIsNull(symbol)`, `findByExitTimeIsNotNullOrderByExitTimeDesc()`

Spring Data R2DBC generates the SQL implementation at startup from method name conventions for the non-`@Query` methods. R2DBC maps Java field names to SQL column names using snake_case convention (`finalSignal → final_signal`, `savedAt → saved_at`). The `@Table` annotation on each entity declares the table name.

PostgreSQL schema initialization: `spring.sql.init.mode: always` and `spring.sql.init.schema-locations: classpath:schema.sql` in `application.yml`. Spring runs `schema.sql` at startup. The DDL uses `CREATE TABLE IF NOT EXISTS` with PostgreSQL-specific types (`BIGSERIAL`, `DOUBLE PRECISION`).

### Messaging / Event Layer

There is no message broker. The `DecisionEventPublisher` interface and its `KafkaDecisionPublisher` TODO comment are the only evidence of an intended Kafka integration. The current implementation is `RestDecisionEventPublisher`, which POSTs to `notification-service` synchronously (over reactive WebClient) in a fire-and-forget pattern.

The "event" semantics exist in the domain model (`MarketDataEvent`) and the interface contract (`DecisionEventPublisher`) but the transport is HTTP. This is by design — the codebase is explicitly marked as "event-ready, Kafka not now."

### Config / Infrastructure Classes

**`OrchestratorConfig`** (`com.agentplatform.orchestrator.config`):
- Declares four `WebClient` beans, one per downstream service
- Each uses `WebClient.Builder` (Spring-managed, inherits base configuration)
- `baseUrl` comes from `@Value`-injected properties resolved from environment variables
- Declares `ConsensusEngine` bean: `new DefaultWeightedConsensusStrategy()`
- Declares `ObjectMapper` bean with `JavaTimeModule`

**`WebClientConfig`** in `market-data-service`:
- Configures the Alpha Vantage client with connection/response timeouts
- Applies a retry filter using Reactor's `retryWhen(Retry.backoff(3, Duration.ofSeconds(2)).maxBackoff(Duration.ofSeconds(10)))` on 5xx responses
- Applies a logging filter that strips the `apikey` query parameter from logged URLs

**`JacksonConfig`** in `history-service` and `analysis-engine`:
- Standalone `@Configuration` classes registering `JavaTimeModule` on an `ObjectMapper` bean
- Required because `Instant` serialization is not enabled by default

---

## 4. End-to-End Execution Flow

This section traces a single symbol trigger from scheduler fire to persisted `FinalDecision`. All class names are real.

---

### Step 0 — Scheduler fires (scheduler-service)

```
Thread: scheduling-1 (Spring @Scheduled thread pool)
Class:  MarketDataScheduler.startAdaptiveScheduling()
```

`@PostConstruct` launches an independent reactive loop per symbol. Each loop uses `Mono.delay(interval)` on Reactor's `Schedulers.parallel()` timer. When the delay completes:

1. Generates `traceId = UUID.randomUUID().toString()`
2. Creates `new MarketDataEvent(symbol, Instant.now(), traceId)`
3. POSTs to `agent-orchestrator` via fire-and-forget subscription
4. After orchestration completes (or fails), fetches latest regime from `history-service` via `GET /api/v1/history/latest-regime?symbol={symbol}`
5. Resolves next interval via `AdaptiveTempoStrategy.resolve(regime)`:
   - VOLATILE → 30 seconds
   - TRENDING → 2 minutes
   - RANGING → 5 minutes
   - CALM → 10 minutes
   - UNKNOWN → 5 minutes (fallback)
6. Recursively schedules the next cycle with the computed interval

The scheduler thread is never blocked — each orchestrator call is a reactive subscription.

---

### Step 1 — Orchestrator receives trigger (agent-orchestrator)

```
Thread:  reactor-http-nio-N (Netty I/O)
Class:   OrchestratorController.trigger()
```

Spring WebFlux deserializes the JSON body into `MarketDataEvent` using Jackson. The controller calls `orchestratorService.orchestrate(event)` which returns a `Mono<List<AnalysisResult>>`. The framework subscribes to this Mono and writes the response when it completes.

```
Class:   OrchestratorService.orchestrate(MarketDataEvent event)
```

The method enters `Mono.defer(() -> { ... })`. This is the assembly phase — no I/O happens yet. Inside defer:

1. `startTime = System.currentTimeMillis()` — captured in closure
2. `MDC.put("traceId", event.traceId())` — ThreadLocal on the calling thread (transient, overwritten by context-aware logging as the chain executes)
3. `MarketRegime[] regime = {MarketRegime.UNKNOWN}` — mutable capture array
4. `Context[] capturedCtx = {null}` — mutable capture for market data context
5. The pipeline is assembled as a chain of operators — no subscription yet
6. `TraceContextUtil.<List<AnalysisResult>>withTraceId(pipeline, event.traceId())` wraps the pipeline in `contextWrite(ctx -> ctx.put("traceId", event.traceId()))` — this stores traceId in the Reactor Context, accessible to all downstream operators regardless of which thread they run on

The return of `orchestrate()` is this Reactor-Context-enriched `Mono`. The WebFlux framework subscribes to it.

---

### Step 2 — Market data fetch

```
Thread:   reactor-http-nio-N (Netty I/O, may switch between calls)
Operator: .flatMap(this::fetchMarketDataAndBuildContext)
Class:    OrchestratorService.fetchMarketDataAndBuildContext()
```

`marketDataClient.get().uri("/api/v1/market-data/quote/" + symbol).header("X-Trace-Id", traceId).retrieve().bodyToMono(String.class)`

This emits an HTTP GET to `market-data-service:8080`. The market-data-service checks its `MarketDataCache` first — if a valid (non-expired) entry exists for this symbol, it returns immediately without calling Alpha Vantage. On cache miss, it calls Alpha Vantage, stores the result in the cache (with regime-aware TTL), and returns.

The response is received as a raw JSON `String`. Inside `.map()`:

1. Jackson parses the JSON into a `JsonNode`
2. Individual fields are extracted: `latestClose`, `open`, `high`, `low`, `volume`
3. `recentClosingPrices` array is iterated to build `List<Double> prices`
4. `Context.of(symbol, triggeredAt, marketData, prices, traceId)` is constructed and returned

```
Thread continues on Netty I/O thread after HTTP response received
Operator: .doOnNext(ctx -> { ... })
```

The `doOnNext` runs synchronously on the same I/O thread that received the response:

1. `capturedCtx[0] = ctx` — captured for AI strategist prompt construction
2. `log.info("Market data fetched. pricePoints={}", ctx.prices().size())`
3. `regime[0] = MarketRegimeClassifier.classify(ctx)` — pure computation, no I/O:
   - Computes standard deviation of price list
   - If `stdDev > 7.0` → `VOLATILE`
   - Else computes SMA20 and SMA50 from latest N prices in the list
   - If `latestClose > SMA50 && latestClose > SMA20` → `TRENDING`
   - If `stdDev < 3.0` → `CALM`
   - Else → `RANGING`
4. `log.info("Market regime classified. regime={}", regime[0])`

```
Operator: .doOnEach(decisionFlowLogger.stage(MARKET_DATA_FETCHED))
Class:    DecisionFlowLogger.stage()
```

The `Consumer<Signal<T>>` returned by `stage()` executes. It reads traceId from `signal.getContextView()` (this is Reactor Context, not ThreadLocal). Inside `TraceContextUtil.withMdc(traceId, () -> log.info(...))`, MDC is set only for the duration of the log call, then removed.

---

### Step 3 — Analysis engine call

```
Operator: .flatMap(this::callAnalysisEngine)
Class:    OrchestratorService.callAnalysisEngine()
```

`analysisEngineClient.post().uri("/api/v1/analyze").header("X-Trace-Id", traceId).bodyValue(context).retrieve().bodyToMono(new ParameterizedTypeReference<List<AnalysisResult>>() {})`

This emits HTTP POST to `analysis-engine:8083` with the `Context` serialized as JSON. Response deserialized to `List<AnalysisResult>`.

```
[Inside analysis-engine]
Thread:  reactor-http-nio-M (Netty I/O in analysis-engine process)
Class:   AnalysisController.analyze()
→       AgentDispatchService.dispatchAll(context)
```

`dispatchAll` iterates `List<AnalysisAgent>` (4 beans: `TrendAgent`, `PortfolioAgent`, `RiskAgent`, `DisciplineCoach`). Each `agent.analyze(context)` is called sequentially:

- **TrendAgent**: calls `TechnicalIndicators.sma()`, `.ema()`, `.macd()`, `.stdDev()` on `context.prices()`. Returns `AnalysisResult` with signal determined by trend direction and MACD alignment.
- **RiskAgent**: calls `TechnicalIndicators.rsi()`, `.stdDev()`, `.sma()`. Computes drawdown from price list. Returns signal based on risk level and RSI zone.
- **PortfolioAgent**: calls multiple `TechnicalIndicators.sma()` for 10/20/50 periods. Evaluates golden/death cross, momentum direction, price-vs-SMA50 position. Returns BUY/SELL/HOLD based on bullish vs bearish signal count.
- **DisciplineCoach**: calls the **Anthropic API** directly via its own `WebClient`. It sends a structured prompt containing symbol, 5-day price change, and market context. If `ANTHROPIC_API_KEY` is empty or the call fails, it executes the fallback rule (5-day change thresholds). Returns `AnalysisResult` with `assessment` field in metadata.

All 4 `AnalysisResult` objects are collected into `List<AnalysisResult>` and returned synchronously from `analyze()`. The response is serialized to JSON and sent back to the orchestrator.

---

### Step 4 — Adaptive weight computation and DecisionContext assembly

```
Operator: .doOnEach(decisionFlowLogger.stage(AGENTS_COMPLETED))
Operator: .flatMap(results -> { ... })
```

Inside the terminal `flatMap`:

```
log.info("Analysis complete. agentsRan={}", results.size())
```

**Performance weights fetch**:
```java
performanceWeightAdapter.fetchPerformanceWeights(event.traceId())
```
HTTP GET to `history-service:8085/api/v1/history/agent-performance`. If history is empty (first run), returns `{}`. If history-service is unreachable, `onErrorResume` returns `Mono.just(Map.of())`. Either way, the pipeline continues.

**Feedback fetch** (chained via `.flatMap`):
```java
agentFeedbackAdapter.fetchFeedback(event.traceId())
```
HTTP GET to `history-service:8085/api/v1/history/agent-feedback`. Same fallback semantics.

**Weight computation**:
```java
AgentScoreCalculator.compute(results, perf, feedback, regime[0])
```

For each `AnalysisResult` in `results`:
1. Look up `AgentPerformanceModel` from `perf` map by `agentName`
2. Base weight: `(accuracy × 0.5) - (latency × 0.2)`, clamped to `[0.1, ∞)`
3. If no performance data: `weight = 1.0` (fallback)
4. Look up `AgentFeedback` from `feedback` map by `agentName`
5. Feedback boost: `(winRate × 0.4) + (avgConfidence × 0.3) - (normalizedLatency × 0.2)`
6. Regime boost (from `regime[0]` captured at Step 2):
   - `TRENDING` and `agentName.toLowerCase().contains("trend")` → `+0.20`
   - `VOLATILE` and `agentName.toLowerCase().contains("risk")` → `+0.20`
   - `RANGING` and `agentName.toLowerCase().contains("portfolio")` → `+0.15`
   - Otherwise → `+0.00`
7. Final weight: `clamp(base + feedbackBoost + regimeBoost, 0.1, 2.0)`
8. All four agents get an entry in `Map<String, Double> adaptiveWeights`

**DecisionContext assembly (pre-AI snapshot)**:
```java
DecisionContext decisionCtx = DecisionContext.assemble(
    event.symbol(), event.triggeredAt(), event.traceId(),
    regime[0], results, adaptiveWeights, latestClose);
```

This captures all known state before AI evaluation. AI-dependent fields (`aiDecision`, `consensusScore`, `divergenceFlag`, `modelLabel`) are `null`.

---

### Step 5 — AI Strategist evaluation and model selection

```
Class:   AIStrategistService.evaluate(context, results, regime, adaptiveWeights)
Operator: .flatMap → Mono<AIStrategyDecision>
```

1. `ModelSelector.selectModel(regime)` — VOLATILE → `claude-haiku-4-5-20251001`, others → `claude-sonnet-4-6`
2. `buildPrompt()` constructs the prompt with symbol, regime, latest close, and per-agent signals with adaptive weights. During VOLATILE regime, reasoning instruction is shortened: "Respond with ONLY one short sentence explaining reasoning."
3. `callAnthropicApi(prompt, selectedModel)` — reactive WebClient POST to `https://api.anthropic.com/v1/messages`. No `.block()`.
4. `parseResponse()` extracts `{finalSignal, confidence, reasoning}` from the Claude response JSON.
5. Returns `AIStrategyDecision(finalSignal, confidence, reasoning)`.

On failure: `onErrorResume` returns a rule-based fallback — majority vote over agent signals with average confidence.

```
decisionFlowLogger.logWithTraceId(AI_STRATEGY_EVALUATED, traceId)
```

---

### Step 6 — Consensus guardrail, divergence detection, and FinalDecision construction

```
Class: OrchestratorService.buildDecision(event, results, latencyMs, adaptiveWeights, regime, aiDecision)
```

```java
ConsensusResult consensus = ConsensusIntegrationGuard.resolve(results, consensusEngine);
```

`ConsensusIntegrationGuard` checks: `results == null || results.isEmpty()` → return fallback. Otherwise calls `consensusEngine.compute(results)`.

Inside `DefaultWeightedConsensusStrategy.compute()`:
1. Each agent assigned weight `1.0` (equal weights — the adaptive weights are stored but not consumed by consensus)
2. Signal scores mapped: `BUY=+1.0`, `SELL=-1.0`, `HOLD=0.0`, `WATCH=+0.5`
3. `rawScore = Σ(signalScore × 1.0) / agentCount`
4. `normalizedConfidence = (rawScore + 1.0) / 2.0`
5. Signal from thresholds: `rawScore > 0.3` → BUY, `< -0.3` → SELL, `> 0.0` → WATCH, else HOLD

**Divergence detection (v7)**:
```java
boolean divergenceFlag = !aiDecision.finalSignal().equals(consensus.finalSignal());
```

When the AI strategist disagrees with the consensus, `divergenceFlag = true`. This is persisted in `FinalDecision` and `decision_history` for observability and trend analysis.

**DecisionContext enrichment (post-AI snapshot)**:
```java
String modelLabel = ModelSelector.resolveLabel(regime[0]);
DecisionContext enriched = decisionCtx.withAIDecision(
    aiDecision, consensus.normalizedConfidence(), divergenceFlag, modelLabel);
decisionFlowLogger.logDecisionContext(enriched, traceId);
```

The enriched `DecisionContext` is logged with: symbol, regime, divergenceFlag, modelLabel, aiSignal.

`FinalDecision.of(17 args)` constructs the v7 record:
- `aiDecision.finalSignal()` → `FinalDecision.finalSignal` (AI is the primary signal)
- `aiDecision.confidence()` → `FinalDecision.confidenceScore` (AI confidence)
- `consensus.normalizedConfidence()` → `FinalDecision.consensusScore` (guardrail metric)
- `aiDecision.reasoning()` → `FinalDecision.aiReasoning`
- `divergenceFlag` → `FinalDecision.divergenceFlag`

---

### Step 7 — Fire-and-forget side effects

```
Class:    RestDecisionEventPublisher.publish(decision)
Method:   notificationClient.post().uri("/api/v1/notify/decision")...subscribe(...)
```

Non-blocking POST to `notification-service:8084`. `.subscribe(success, error)` — the orchestrator's pipeline does not wait for this. If notification-service is down, the error is logged and discarded.

```
Class:    OrchestratorService.saveToHistory(decision)
Method:   historyClient.post().uri("/api/v1/history/save")...subscribe(...)
```

Non-blocking POST to `history-service:8085`. Same fire-and-forget semantics.

```
[Inside history-service]
Class:    HistoryController.save() → HistoryService.save(decision)
```

`Mono.fromCallable(() -> toEntity(decision))` — runs Jackson serialization synchronously inside the callable. `.flatMap(repository::save)` — R2DBC executes `INSERT INTO decision_history (...)` asynchronously using the R2DBC connection pool to PostgreSQL. No explicit transaction — each insert is auto-committed.

---

### Step 8 — Response returned to scheduler

Back in the orchestrator's `flatMap`:
```java
return decision.agents();  // List<AnalysisResult>
```

The `Mono` completes with `List<AnalysisResult>`. `OrchestratorController` wraps it in `ResponseEntity.ok()`. Spring WebFlux serializes the list to JSON and writes it to the HTTP response.

Back in the scheduler's reactive loop: after the orchestration response (or error), the scheduler fetches the latest regime and computes the next interval for this symbol, then recursively schedules via `Mono.delay()`.

Total latency for this cycle: typically 1–4 seconds depending on Alpha Vantage (if cache miss) and Anthropic API latency.

---

## 5. Concurrency & Thread Model

### WebFlux / Reactor Execution Model

All HTTP-serving services run on Netty's NIO event loop. Netty spawns a fixed pool of `reactor-http-nio-N` threads (default: `2 × CPU cores`). These threads handle both I/O events (accepting connections, reading/writing bytes) and operator execution in the reactive chain.

There is **no blocking I/O** on these threads. Every `.flatMap()` that performs HTTP (via WebClient) registers a non-blocking I/O request and returns immediately. When the response arrives on the NIO socket, Netty resumes the operator chain on one of the NIO threads.

### Thread Lifecycle Through Orchestration

```
1. Netty I/O thread receives POST /trigger
   → Runs until first I/O-bound flatMap (market data fetch)
   → Suspends (no thread blocked)

2. Market-data-service responds on a Netty I/O thread
   → Cache check in MarketDataService (pure CPU, fast)
   → If cache miss: Alpha Vantage responds on a Netty I/O thread
   → doOnNext (MarketRegimeClassifier.classify) runs here — pure CPU, fast
   → Runs until analysis-engine fetch
   → Suspends

3. Analysis engine responds on a Netty I/O thread (same or different)
   → Runs until history-service performance/feedback fetches
   → These are chained flatMaps — each suspends between them

4. History responses arrive
   → AgentScoreCalculator.compute() runs here (pure CPU)
   → DecisionContext.assemble() runs here (pure CPU)
   → AIStrategistService.evaluate() → Claude API call → Suspends
   → Claude API responds
   → buildDecision() runs here (pure CPU, includes divergence check)
   → DecisionContext.withAIDecision() → enrichment (pure CPU)
   → publish() and saveToHistory() called (fire-and-forget subscriptions)
   → Mono completes with decision.agents()
   → Netty writes HTTP response
```

The key property: **no thread is ever blocked waiting for I/O** (except `DisciplineCoach`, noted in §9). A single Netty thread services hundreds of concurrent requests interleaved.

### Scheduler Thread Behavior

`MarketDataScheduler` uses `@PostConstruct` to start adaptive scheduling loops. `Mono.delay()` uses Reactor's `Schedulers.parallel()` timer — hashed wheel timer, O(1) memory per delay. Each orchestrator trigger is a fire-and-forget subscription. The scheduler never blocks — it recursively schedules the next cycle after both orchestration and regime-fetch complete (or fail, via `onErrorResume`).

### Analysis Engine — Synchronous Agents

The analysis engine's `AgentDispatchService.dispatchAll()` is entirely synchronous. It iterates agents sequentially on the Netty I/O thread that handled the incoming HTTP request. The four agent `analyze()` calls are CPU-bound (indicator math). `DisciplineCoach` is the exception: it calls Anthropic's API via WebClient, which is reactive. However, because `AnalysisController.analyze()` returns `ResponseEntity<T>` (not `Mono`), WebFlux wraps it in a thread that can handle blocking — this is a subtle risk area covered in §9.

---

## 6. Data & Transaction Boundaries

### No Distributed Transactions

There are no distributed transactions in this system. The `FinalDecision` is:
1. Built in memory in `agent-orchestrator`
2. POSTed to `history-service` (fire-and-forget)
3. POSTed to `notification-service` (fire-and-forget)

If `history-service` fails to persist, the decision is lost. There is no retry, no dead-letter queue, no idempotency key check. This is an accepted tradeoff — the system is analytical, not transactional.

### Local Transaction Scope (history-service)

R2DBC with PostgreSQL in auto-commit mode. Each `repository.save()` is a single-row insert. No `@Transactional` annotation is used anywhere in the codebase. There is no explicit transaction manager configured.

### Database Schema

PostgreSQL DDL — 4 tables, v8 schema:

```sql
-- Primary decision table: 19 columns (v7)
CREATE TABLE IF NOT EXISTS decision_history (
    id                     BIGSERIAL PRIMARY KEY,
    symbol                 VARCHAR(20) NOT NULL,
    timestamp              TIMESTAMP NOT NULL,
    agents                 TEXT,                    -- JSON: List<AnalysisResult>
    final_signal           VARCHAR(10) NOT NULL,
    confidence_score       DOUBLE PRECISION NOT NULL,
    metadata               TEXT,                    -- JSON: Map<String, Object>
    trace_id               VARCHAR(100),
    saved_at               TIMESTAMP NOT NULL,
    decision_version       VARCHAR(20),             -- v2
    orchestrator_version   VARCHAR(20),             -- v2
    agent_count            INT,                     -- v2
    decision_latency_ms    BIGINT,                  -- v2
    consensus_score        DOUBLE PRECISION,        -- v3
    agent_weight_snapshot  TEXT,                     -- v3, JSON
    adaptive_agent_weights TEXT,                     -- v4, JSON
    market_regime          VARCHAR(20),              -- v5
    ai_reasoning           TEXT,                     -- v6
    divergence_flag        BOOLEAN                   -- v7
);

CREATE INDEX idx_decision_history_symbol_savedat
    ON decision_history(symbol, saved_at DESC);     -- v8

-- Pre-aggregated agent performance (v8 — eliminates full-table scans)
CREATE TABLE IF NOT EXISTS agent_performance_snapshot (
    agent_name             VARCHAR(50) PRIMARY KEY,
    historical_accuracy_score DOUBLE PRECISION DEFAULT 0.5,
    latency_weight         DOUBLE PRECISION DEFAULT 0.0,
    win_rate               DOUBLE PRECISION DEFAULT 0.0,
    avg_confidence         DOUBLE PRECISION DEFAULT 0.0,
    avg_latency_ms         DOUBLE PRECISION DEFAULT 0.0,
    total_decisions        BIGINT DEFAULT 0,
    sum_confidence         DOUBLE PRECISION DEFAULT 0.0,
    sum_latency_ms         DOUBLE PRECISION DEFAULT 0.0,
    sum_wins               DOUBLE PRECISION DEFAULT 0.0,
    regime_bias            VARCHAR(20),
    last_updated           TIMESTAMP DEFAULT NOW()
);

-- Per-symbol trend metrics projection (v8)
CREATE TABLE IF NOT EXISTS decision_metrics_projection (
    symbol                 VARCHAR(20) PRIMARY KEY,
    last_confidence        DOUBLE PRECISION DEFAULT 0.0,
    confidence_slope_5     DOUBLE PRECISION DEFAULT 0.0,
    divergence_streak      INT DEFAULT 0,
    momentum_streak        INT DEFAULT 0,
    last_updated           TIMESTAMP DEFAULT NOW()
);

-- Trade session lifecycle (trade-service)
CREATE TABLE IF NOT EXISTS trade_sessions (
    id                     BIGSERIAL PRIMARY KEY,
    symbol                 VARCHAR(20),
    entry_time             TIMESTAMP,
    entry_price            DOUBLE PRECISION,
    entry_confidence       DOUBLE PRECISION,
    entry_regime           VARCHAR(20),
    entry_momentum         VARCHAR(20),
    exit_time              TIMESTAMP,
    exit_price             DOUBLE PRECISION,
    pnl                    DOUBLE PRECISION,
    duration_ms            BIGINT
);
```

### Retry Logic

**market-data-service**: `WebClientConfig` configures a retry filter on the Alpha Vantage WebClient: `Retry.backoff(3, Duration.ofSeconds(2)).maxBackoff(Duration.ofSeconds(10))` on 5xx responses. This is the only retry logic in the system.

**All other WebClient calls**: No retry. Failures either propagate as errors (analysis engine call in orchestrator — this terminates the pipeline) or are silently swallowed (adapters, fire-and-forget publishers).

### Idempotency

No idempotency mechanism exists. The `traceId` is stored in `decision_history` and the repository declares `findByTraceId(String)`, suggesting idempotency checking was considered but not implemented. Duplicate traceIds could produce duplicate rows.

### Consistency Strategy

The system is **eventually consistent by design**. Adaptive weights (used on cycle N+1) reflect decisions from all prior cycles. There is no read-your-own-write guarantee: a decision persisted to history at the end of cycle N is available for aggregation at cycle N+1 only if the `getAgentPerformance()` and `getAgentFeedback()` calls happen after the insert completes. Since these calls are sequential in the Reactor chain (not concurrent with the save), and saves are fire-and-forget, a race condition exists: cycle N's save and cycle N+1's performance fetch could overlap.

---

## 7. Observability & Logging Internals

### Logging Configuration

Each service configures the same pattern in `application.yml`:
```yaml
logging.pattern.console: "%d{yyyy-MM-dd HH:mm:ss} [%X{traceId}] %-5level %logger{36} - %msg%n"
```

`%X{traceId}` is an MDC placeholder. When MDC contains `traceId`, it appears in the log line. When it doesn't, the brackets appear empty: `[]`. This is how logs from reactive threads appear — without special handling, MDC is always empty on Netty threads because they never set it.

### The MDC-Reactor Context Duality

MDC is ThreadLocal. Reactor chains execute on Netty threads that are shared across many concurrent requests. A `MDC.put()` on thread A could be read by thread B if the chain switches threads between operators. This is a well-known reactive logging problem.

The solution implemented here is `TraceContextUtil`:

```java
// Store traceId in Reactor Context (immutable, request-scoped):
public static <T> Mono<T> withTraceId(Mono<T> mono, String traceId) {
    return mono.contextWrite(ctx -> ctx.put(TRACE_ID_KEY, traceId));
}

// Retrieve from Reactor Context (used in operators):
public static String getTraceId(ContextView ctx) {
    return ctx.getOrDefault(TRACE_ID_KEY, "unknown");
}

// Bridge to MDC only for the duration of one log call:
public static void withMdc(String traceId, Runnable logAction) {
    MDC.put(TRACE_ID_KEY, traceId);
    try { logAction.run(); } finally { MDC.remove(TRACE_ID_KEY); }
}
```

Reactor Context is immutable and propagates backwards through the operator chain — `contextWrite()` at the end of the chain makes the value available to all upstream operators. `signal.getContextView()` inside `doOnEach` gives access to this context on any thread.

### DecisionFlowLogger — Lifecycle Tracing

`DecisionFlowLogger` implements structured stage-based tracing for the orchestration pipeline with **eight lifecycle stages**:

```java
// Used with doOnEach — receives Signal<T> which carries ContextView:
public <T> Consumer<Signal<T>> stage(String stageName) {
    return signal -> {
        if (!signal.isOnNext()) return;  // ignore error/complete signals
        String traceId = TraceContextUtil.getTraceId(signal.getContextView());
        TraceContextUtil.withMdc(traceId, () ->
            log.info("[DecisionFlow] stage={} traceId={}", stageName, traceId));
    };
}

// Used with doOnNext — direct access to domain object:
public void logWithTraceId(String stageName, String traceId) { ... }

// Compact DecisionContext summary logging:
public void logDecisionContext(DecisionContext ctx, String traceId) { ... }
```

The eight stages logged per orchestration:
1. `TRIGGER_RECEIVED` — event enters pipeline
2. `MARKET_DATA_FETCHED` — Context constructed, regime classified
3. `AGENTS_COMPLETED` — `List<AnalysisResult>` received
4. `AI_MODEL_SELECTED` — regime-appropriate Claude model chosen
5. `AI_STRATEGY_EVALUATED` — AI strategist returned recommendation
6. `DECISION_CONTEXT_ASSEMBLED` — enriched `DecisionContext` snapshot logged with symbol, regime, divergenceFlag, modelLabel, aiSignal
7. `FINAL_DECISION_CREATED` — `FinalDecision` built
8. `EVENTS_DISPATCHED` — side effects fired

### Structured Log Fields

Key log statements in `OrchestratorService`:
```
Orchestration started. symbol=AAPL traceId=abc-123
Market data fetched. pricePoints=20
Market regime classified. regime=RANGING traceId=abc-123
Analysis complete. agentsRan=4
Decision built. finalSignal=HOLD aiSignal=HOLD consensusScore=0.5 aiConfidence=0.72
    divergenceFlag=false latencyMs=1423 regime=RANGING adaptiveAgents=4
    feedbackAgents=4 traceId=abc-123
History saved. traceId=abc-123 status=200 OK
```

Key log statement in `DecisionFlowLogger`:
```
[DecisionFlow] stage=DECISION_CONTEXT_ASSEMBLED symbol=AAPL regime=RANGING
    divergenceFlag=false modelLabel=sonnet-deep aiSignal=HOLD traceId=abc-123
```

Key log statement in `MarketDataCache`:
```
CACHE_REFRESH symbol=AAPL regime=RANGING ttlSeconds=420
```

These are structured enough to be parsed by log aggregation tools despite not using a structured logging framework (e.g., Logstash encoder).

---

## 8. Deployment & Runtime Architecture

### Docker Compose Service Graph

```
market-data-service:8080   (no deps)
analysis-engine:8083        (no deps)
notification-service:8084   (no deps)
postgres:5432               (no deps — PostgreSQL database, shared by history-service + trade-service)
history-service:8085        (depends_on: postgres — condition: service_healthy)
agent-orchestrator:8081     (depends_on: market-data, analysis-engine,
                             notification, history — all condition: service_healthy)
scheduler-service:8082      (depends_on: agent-orchestrator, history — condition: service_healthy)
trade-service:8086          (depends_on: history — condition: service_healthy)
```

`depends_on` with `service_healthy` means Docker waits for the healthcheck command to succeed before starting the dependent container. Health checks use `wget -qO-` to hit each service's `/health` endpoint.

### Dockerfile — Multi-Stage Build

```dockerfile
# Stage 1: eclipse-temurin:21-jdk-alpine
ARG SERVICE
WORKDIR /build

# Layer 1: POMs only (cached until any POM changes)
COPY pom.xml .
COPY common-lib/pom.xml  common-lib/pom.xml
COPY [all module pom.xmls...]

# Layer 2: Maven wrapper (cached)
COPY .mvn .mvn
COPY mvnw .
RUN ./mvnw dependency:go-offline -q

# Layer 3: Source (invalidated on any source change)
COPY common-lib/src  common-lib/src
COPY [all module sources...]

# Build only target service and its declared dependencies (-am):
RUN ./mvnw -pl ${SERVICE} -am package -DskipTests -q

# Stage 2: eclipse-temurin:21-jre-alpine (JRE only, smaller image)
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser
COPY --from=builder /build/${SERVICE}/target/*.jar app.jar
ENTRYPOINT ["java",
  "-XX:+UseContainerSupport",   # JVM reads cgroup memory limits
  "-XX:MaxRAMPercentage=75.0",  # heap = 75% of container memory limit
  "-XX:+UseG1GC",
  "-Djava.security.egd=file:/dev/./urandom",  # faster SecureRandom on Linux
  "-jar", "app.jar"]
```

Each service is built from the same Dockerfile using `--build-arg SERVICE=<module>`. The `-am` Maven flag builds the service's module dependency tree — `common-lib` is always built first.

### Environment Configuration

All runtime secrets and service URLs are injected via Docker Compose environment variables, sourced from `.env`:

```
ALPHA_VANTAGE_API_KEY=<key>     # Required, market-data-service
ANTHROPIC_API_KEY=<key>         # Optional, analysis-engine + agent-orchestrator
SLACK_WEBHOOK_URL=<url>         # Optional, notification-service
SLACK_ENABLED=false             # Optional, default false
WATCHED_SYMBOLS=IBM,AAPL        # Comma-separated, scheduler-service
MARKET_DATA_URL=http://market-data-service:8080
ANALYSIS_ENGINE_URL=http://analysis-engine:8083
NOTIFICATION_URL=http://notification-service:8084
HISTORY_URL=http://history-service:8085
ORCHESTRATOR_URL=http://agent-orchestrator:8081
POSTGRES_HOST=postgres          # PostgreSQL host for history-service
POSTGRES_PORT=5432
POSTGRES_DB=agent_platform
POSTGRES_USER=<user>
POSTGRES_PASSWORD=<password>
HISTORY_SERVICE_URL=http://history-service:8085  # trade-service → history-service
```

Service-to-service URLs use Docker Compose service names as hostnames. These are resolved by Docker's internal DNS. No service discovery mechanism (Eureka, Consul) is needed within the Compose network.

### Service Startup Flow

1. PostgreSQL container starts first; R2DBC connects on first request; `schema.sql` DDL runs via `ConnectionFactoryInitializer`
2. Spring beans instantiate: WebClient beans receive `baseUrl` from environment
3. `ConsensusEngine` bean (`DefaultWeightedConsensusStrategy`) instantiates
4. `AIStrategistService` configures its own `WebClient` to `api.anthropic.com`
5. `MarketDataCache` initializes with empty `ConcurrentHashMap`
6. `AnalysisAgent` list injected into `AgentDispatchService`
7. `MarketDataScheduler.startAdaptiveScheduling()` fires via `@PostConstruct` — launches per-symbol reactive loops
8. Actuator endpoints (`/actuator/health`, `/actuator/info`, `/actuator/metrics`) exposed
9. Service reports healthy; Docker Compose health check passes

---

## 9. Hidden Design Decisions

### AI Strategist as Primary Decision Authority (Phase 6+)

Before Phase 6, `DefaultWeightedConsensusStrategy` determined `FinalDecision.finalSignal`. After Phase 6, `AIStrategistService` is the primary decision authority. The consensus engine runs as a **safety guardrail** only — its `normalizedConfidence` is stored as `consensusScore` for observability and divergence tracking, but its `finalSignal` is no longer used as the output signal.

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

### Regime-Aware Model Selection via ModelSelector

`ModelSelector` is a pure static utility — no Spring dependencies, no configuration, no I/O. It maps `MarketRegime` → Claude model deterministically:

- `VOLATILE` → `claude-haiku-4-5-20251001` (cheap/fast — high-frequency re-evaluation, decisions are short-lived)
- All others → `claude-sonnet-4-6` (deeper reasoning, worth the cost when decision intervals are longer)

`ModelSelector.resolveLabel()` provides human-readable labels (`haiku-fast` / `sonnet-deep`) for the UI and observability logging. These are constants, not configurable, to avoid accidental misconfiguration in production.

### Divergence Awareness (Phase 7)

When the AI strategist's signal differs from the consensus signal, `divergenceFlag = true`. This is computed as a simple equality check: `!aiDecision.finalSignal().equals(consensus.finalSignal())`. The flag is:
- Stored in `FinalDecision.divergenceFlag` (Boolean, nullable for legacy records)
- Persisted in `decision_history.divergence_flag` (BOOLEAN column)
- Included in the `DecisionContext` enrichment snapshot
- Surfaced in the UI as an "AI Diverging" badge with guardrail awareness indicator

Divergence is tracked but does not trigger any automatic behavior — no safety override, no fallback to consensus. It is pure observability, enabling future evolution toward divergence-triggered guardrails.

### DecisionContext as Unified Intelligence Snapshot

`DecisionContext` consolidates scattered method parameters into a single self-describing domain object. Its two-phase lifecycle mirrors the pipeline's natural structure:

1. **Pre-AI**: assembled after agents complete and weights are computed. AI-dependent fields are null.
2. **Post-AI**: enriched via `withAIDecision()` copy-factory after the AI strategist returns and consensus guardrail runs.

The record is never mutated. `List.copyOf()` and `Map.copyOf()` in `assemble()` create defensive copies. This prevents downstream consumers from inadvertently modifying shared state.

`DecisionContext` is additive — it does not replace `FinalDecision` (persistence/API contract), `Context` (market data carrier), or `AIStrategyDecision` (AI output). It composes them. `HistoryService.toEntity(DecisionContext)` exists as an alternative mapping path but is not yet wired into the save pipeline — provided for future adoption without schema migration.

### MarketDataCache — Regime-Aware TTL Strategy

`MarketDataCache` uses `ConcurrentHashMap` for thread-safe, non-blocking access. It classifies the market regime at cache-put time by constructing a minimal `Context` from the `MarketDataQuote` and delegating to `MarketRegimeClassifier` — no logic duplication.

TTL is regime-driven:
- VOLATILE → 2 min (fast refresh for rapidly changing data)
- TRENDING → 5 min
- RANGING → 7 min
- CALM → 10 min (long cache for stable markets)

This ensures the system respects Alpha Vantage rate limits while keeping VOLATILE-regime data fresh enough for 30-second polling intervals.

### Adaptive Weights Are Stored but Not Used in Consensus

The `DefaultWeightedConsensusStrategy` assigns every agent weight `1.0`. The `adaptiveAgentWeights` computed by `AgentScoreCalculator` are stored in `FinalDecision.adaptiveAgentWeights` and persisted to history — but they do not influence the actual consensus computation in this cycle. They are, however, included in the AI strategist's prompt — the LLM sees the adaptive weights and factors them into its recommendation.

### `analysis-engine` Uses Synchronous Controller

`AnalysisController.analyze()` returns `ResponseEntity<List<AnalysisResult>>` — not a `Mono`. When WebFlux handles a synchronous return type, it wraps it in `Mono.just()`. `DisciplineCoach` calls Anthropic via WebClient and uses `.block()` internally to bridge from reactive to synchronous. This means `DisciplineCoach.analyze()` does block the Netty I/O thread. This is the only blocking call in the system and is a latent stability risk under high concurrency.

> **Architectural Note — Reactive Boundary Violation**
>
> `DisciplineCoach` bridges a reactive WebClient call (Anthropic API) into synchronous execution by calling `.block()` inside an `AnalysisAgent.analyze()` implementation. Under concurrent load, if multiple symbols are processed simultaneously and each invokes `DisciplineCoach`, multiple Netty I/O threads can be blocked waiting on external HTTP responses. Netty's NIO event loop is a fixed-size thread pool (`2 × CPU cores`). Exhausting it stalls the entire service.
>
> The rest of the platform maintains strict reactive purity. Every other I/O operation uses non-blocking WebClient chains with `flatMap`. `DisciplineCoach` is the sole exception.

### Immutable Reactor Context vs Mutable MDC

The system maintains a strict separation: Reactor Context carries `traceId` for the pipeline, MDC carries it only during a log call. This means the log line format `[%X{traceId}]` will show the traceId only when explicitly bridged via `withMdc()`. Any log statement that does not use the bridge shows empty brackets.

This is a correctness tradeoff: avoiding MDC propagation bugs (where traceId from request A leaks into logs for request B on the same Netty thread) at the cost of needing explicit bridging everywhere.

### `DecisionEventPublisher` as Seam for Kafka

The interface:
```java
public interface DecisionEventPublisher {
    void publish(FinalDecision decision);
}
```
...exists entirely to allow replacing `RestDecisionEventPublisher` with a `KafkaDecisionPublisher` by changing a single `@Bean` in `OrchestratorConfig`. The orchestrator service never imports `RestDecisionEventPublisher` directly — only the interface. This is textbook dependency inversion applied specifically to defer the Kafka decision without accumulating technical debt.

### Agent Name Matching for Regime Boost is Positional

`AgentScoreCalculator.regimeBoost()` uses `agentName.toLowerCase().contains("trend")`. This couples the regime boost logic to naming conventions rather than explicit configuration. A new agent named `ShortTermTrendScalper` would receive the TRENDING boost. A new agent named `TrendFollower` renamed to `MomentumFollower` would silently lose it. This is a fragility that would need addressing before adding more regime-sensitive agents.

---

## 10. Developer Mental Model

### Core Execution Loop

```
Scheduler (adaptive tempo: 30s–10min per symbol based on regime)
  → fires MarketDataEvent per symbol
    → OrchestratorService.orchestrate() assembles Mono pipeline
      → fetch Context from market-data-service (cache-first, Alpha Vantage on miss)
      → classify MarketRegime (pure, synchronous)
      → send Context to analysis-engine → get List<AnalysisResult>
      → fetch AgentPerformanceModel map from history-service (or empty)
      → fetch AgentFeedback map from history-service (or empty)
      → compute Map<agentName, adaptiveWeight> via AgentScoreCalculator
      → assemble DecisionContext (pre-AI snapshot)
      → select regime-appropriate Claude model via ModelSelector
      → evaluate via AIStrategistService → get AIStrategyDecision (primary signal)
      → run DefaultWeightedConsensusStrategy on results → ConsensusResult (guardrail)
      → detect divergence: AI signal ≠ consensus signal → divergenceFlag
      → enrich DecisionContext (post-AI snapshot with aiDecision, divergence, modelLabel)
      → assemble FinalDecision (17 fields, v7)
      → fire-and-forget: save to history-service (PostgreSQL)
      → fire-and-forget: notify via notification-service
      → return List<AnalysisResult> to scheduler caller
    → scheduler fetches latest regime from history-service
    → scheduler computes next interval via AdaptiveTempoStrategy
    → Mono.delay(nextInterval) → recurse
```

### Where the Intelligence Lives

| Concern | Class | Package |
|---|---|---|
| Price pattern detection | `TrendAgent` | `analysis-engine/agent` |
| Risk scoring | `RiskAgent` | `analysis-engine/agent` |
| Multi-timeframe alignment | `PortfolioAgent` | `analysis-engine/agent` |
| AI-augmented sanity check | `DisciplineCoach` | `analysis-engine/agent` |
| Technical indicator math | `TechnicalIndicators` | `analysis-engine/util` |
| **Primary decision intelligence** | `AIStrategistService` | `agent-orchestrator/ai` |
| Regime-aware model selection | `ModelSelector` | `agent-orchestrator/ai` |
| Consensus aggregation (guardrail) | `DefaultWeightedConsensusStrategy` | `common-lib/consensus` |
| Adaptive weight calculation | `AgentScoreCalculator` | `common-lib/consensus` |
| Market regime detection | `MarketRegimeClassifier` | `common-lib/classifier` |
| Feedback aggregation | `HistoryService.aggregateFeedback()` | `history-service/service` |
| Market data caching | `MarketDataCache` | `market-data-service/cache` |
| Unified intelligence snapshot | `DecisionContext` | `common-lib/model` |
| Momentum state classification | `MomentumStateCalculator` | `common-lib/momentum` |
| Trade posture derivation | `TradePostureInterpreter` | `common-lib/posture` |
| Momentum structure classification | `StructureInterpreter` | `common-lib/structure` |
| Exit awareness & risk envelopes | `AdaptiveRiskEngine` | `common-lib/risk` |
| Trade outcome reflection | `ReflectionInterpreter` | `common-lib/reflection` |
| Trade lifecycle management | `TradeService` | `trade-service/service` |

### Where the Orchestration Lives

Everything in `OrchestratorService.orchestrate()` — a single method in a single class. This is the system's main executable. If you need to understand what the system does end-to-end, this method is the entry point. The constructor injection list has 10 dependencies, reflecting the orchestrator's role as the sole pipeline coordinator.

### Where the Contracts Live

Everything in `common-lib`. If a record field changes, a method signature changes, or a new model is introduced, it starts here. This is the most change-sensitive module. Key types: `FinalDecision` (17 components), `DecisionContext` (11 fields), `AIStrategyDecision`, `AnalysisResult`, `Context`, `MarketRegime`, `ConsensusEngine`, `AnalysisAgent`.

### What is Safe to Change Independently

- Any single `AnalysisAgent` implementation — no other service knows about it
- `DefaultWeightedConsensusStrategy` — swappable via `@Bean` in `OrchestratorConfig`
- `RestDecisionEventPublisher` — swappable via `@Bean` in `OrchestratorConfig`
- Slack formatting in `SlackWebhookSender` — no downstream dependency
- `schema.sql` — additive columns only, nullable
- `ModelSelector` model constants — change model names without touching any other class
- `MarketDataCache` TTL values — purely internal to market-data-service
- `AdaptiveTempoStrategy` interval values — purely internal to scheduler-service
- UI components — no backend coupling beyond the snapshot, market-state, trade, and SSE API contracts
- `AdaptiveRiskEngine` constants (base stops, smoothing factors) — purely internal to common-lib/trade-service
- `TradePostureInterpreter` stability window — purely internal to common-lib
- `MomentumStateCalculator` thresholds — purely internal to common-lib
- `trade-service` endpoints — no upstream dependency, operator-facing only

### What Requires Cross-Module Coordination

- Adding a field to `FinalDecision` — requires updating: `FinalDecision`, `OrchestratorService.buildDecision()`, `HistoryService.toEntity()`, `DecisionHistory`, `schema.sql`. Optionally: `DecisionContext`, `SnapshotDecisionDTO`, UI components
- Changing `AnalysisAgent` interface — requires updating all four agent implementations and `AgentDispatchService`
- Changing `ConsensusEngine` interface — requires updating `DefaultWeightedConsensusStrategy` and `ConsensusIntegrationGuard`
- Adding fields to `DecisionContext` — requires updating `assemble()` and/or `withAIDecision()` factories, consumers in orchestrator and logger

---

## 11. Intelligence Architecture Mapping

### The Intelligence Pipeline Is a Composition of Eleven Stateless Concerns

Each concern is a pure function, strategy, or AI service. They are composed in `OrchestratorService` but implemented independently. None of them hold mutable state (except `MarketDataCache`, which is infrastructure, not intelligence).

#### Concern 1: Technical Signal Generation

Four `AnalysisAgent` implementations in `analysis-engine`. Each receives a `Context` and independently computes a `(signal, confidenceScore, metadata)` triple. They share `TechnicalIndicators` as a stateless utility. No agent knows about any other agent.

The agents are wired through `AgentDispatchService` via Spring's `List<AnalysisAgent>` injection. Registration requires only `@Component` and interface implementation — no explicit bean wiring, no factory.

#### Concern 2: Market Regime Classification

`MarketRegimeClassifier.classify(Context)` runs synchronously in the `doOnNext` after market data is fetched, before agents execute. It produces a `MarketRegime` enum value captured in `regime[0]`.

The classification uses prices from `Context.prices()` (the same list that agents use) and `latestClose` from `Context.marketData()`. It runs in O(N) time with N = length of price list.

The regime value influences: agent weighting (Concern 4), AI model selection (Concern 6), cache TTL (`MarketDataCache`), and scheduler tempo (`AdaptiveTempoStrategy`). It does not influence agent execution (Concern 1). Agents always run — the regime only modulates how their results are valued and processed.

#### Concern 3: Equal-Weight Consensus (Safety Guardrail)

`DefaultWeightedConsensusStrategy` computes a linear combination of agent signals using uniform weights. The `agentWeights` in the returned `ConsensusResult` are all `1.0`. The `normalizedConfidence` is the fraction of bullish signal strength in the total signal energy.

**Since Phase 6, consensus is no longer the decision authority.** Its `finalSignal` is used only for divergence detection against the AI strategist's recommendation. Its `normalizedConfidence` is stored as `FinalDecision.consensusScore` — a guardrail metric for observability.

#### Concern 4: Adaptive Weight Computation

`AgentScoreCalculator.compute(results, perf, feedback, regime)` computes a `Map<agentName, Double>` stored as `FinalDecision.adaptiveAgentWeights`. This is a three-layer calculation:

1. **Performance layer**: accuracy × 0.5 − latency × 0.2 (from historical confidence scores and normalized decision latency)
2. **Feedback layer**: win rate × 0.4 + avg confidence × 0.3 − normalized latency × 0.2 (from historical signal-vs-consensus alignment)
3. **Regime layer**: additive boost to specific agent types based on detected market condition

The computed weights are **persisted** in `FinalDecision` and stored in `decision_history.adaptive_agent_weights`. They are **passed to the AI strategist** in the prompt — the LLM sees each agent's adaptive weight and factors it into its recommendation. They are **not used** by the consensus engine. The weights represent the system's accumulated intelligence about agent reliability in context.

#### Concern 5: Feedback Loop Aggregation

`HistoryService` maintains pre-aggregated agent metrics in the `agent_performance_snapshot` table. On each `save()`, the projection pipeline upserts running counters (total decisions, sum confidence, sum latency, sum wins) per agent via `AgentPerformanceSnapshotRepository.upsertAgent()`. `getAgentPerformance()` and `getAgentFeedback()` read directly from this projection table — no full-table scan.

For `winRate`: an agent "wins" when its `signal` matches `FinalDecision.finalSignal`. Since Phase 6, this means alignment with the **AI strategist's signal**, not the consensus signal. This creates a richer feedback dynamic — agents are rewarded for anticipating what the AI strategist decides, which is itself a synthesis of all agents' signals weighted by their adaptive scores.

Per-symbol trend metrics are maintained in `decision_metrics_projection`: confidence slope (5-point linear regression), divergence streak (consecutive divergence flags), and momentum streak (consecutive same signals). These are consumed by `trade-service` for risk awareness.

#### Concern 6: AI Strategy Evaluation (Primary Intelligence)

`AIStrategistService` is the primary decision intelligence. It receives the full picture — agent signals, adaptive weights, market regime — and synthesizes a strategic recommendation via the Claude API.

**Regime-aware model selection**: `ModelSelector` chooses the Claude model based on market regime. VOLATILE → Haiku (cheap, fast, decisions are short-lived). Others → Sonnet (deeper reasoning, worth the cost).

**Regime-aware prompt behavior**: During VOLATILE regimes, the prompt instructs the model to respond with only one short sentence. This minimizes token cost and response latency for decisions that will be superseded in 30 seconds.

**Fallback behavior**: On API failure or missing key, returns a rule-based majority vote over agent signals. The pipeline never stalls.

#### Concern 7: Divergence Detection

After both the AI strategist and consensus engine have produced their signals, a simple equality check detects divergence: `!aiDecision.finalSignal().equals(consensus.finalSignal())`. The `divergenceFlag` is:
- Stored in `FinalDecision` and persisted to `decision_history`
- Logged in the `DecisionContext` summary
- Surfaced in the UI as visual indicators (AI Diverging badge, guardrail warning)
- Available for future evolution toward divergence-triggered safety overrides

#### Concern 8: Momentum State Classification

`MomentumStateCalculator.classify()` evaluates four dimensions of recent decision history — signal alignment, confidence trend, divergence pressure, and regime stability — to produce a `MarketState` enum. This is consumed by `trade-service` for operator awareness, not by the orchestration pipeline.

#### Concern 9: Structure Interpretation

`StructureInterpreter.evaluate()` classifies momentum structure from metrics (divergence streak, confidence slope) into `CONTINUATION`, `STALLING`, or `FATIGUED`. Pure function over `MetricsInput` and `MomentumInput`.

#### Concern 10: Trade Posture Derivation

`TradePostureInterpreter.evaluateStable()` combines momentum state, structure signal, and exit awareness into a single `TradePosture` label with 60-second stability window suppression. Pure logic with posture state tracking.

#### Concern 11: Adaptive Risk Computation

`AdaptiveRiskEngine.evaluateAdaptive()` computes exit awareness and risk envelopes with exponential smoothing. Adjusts base stops based on momentum weakness, confidence drift, divergence streaks, and confidence slope. Pure logic with state smoothing.

### Intelligence-Driven with Consensus Guardrail

**The platform is intelligence-driven since Phase 6.** The AI strategist's signal is the authoritative output. The consensus engine runs as a safety guardrail — its signal enables divergence tracking but does not determine the output. Adaptive weights computed by `AgentScoreCalculator` are included in the AI strategist's prompt context, creating a loop where historical performance data shapes the AI's evaluation of each agent's contribution.

### Lifecycle of a FinalDecision

```
1.  CONTEXT_ASSEMBLED:  DecisionContext.assemble() — pre-AI snapshot
2.  AI_EVALUATED:       AIStrategistService.evaluate() → AIStrategyDecision
3.  CONSENSUS_CHECKED:  ConsensusIntegrationGuard.resolve() → ConsensusResult (guardrail)
4.  DIVERGENCE_DETECTED: AI signal ≠ consensus signal → divergenceFlag
5.  CONTEXT_ENRICHED:   DecisionContext.withAIDecision() — post-AI snapshot logged
6.  ASSEMBLED:          FinalDecision.of(17 args) — v7 record construction
7.  RETURNED:           OrchestratorController → REST response (List<AnalysisResult>, not FinalDecision)
8.  PERSISTED:          HistoryService.save() → PostgreSQL insert into decision_history
9.  PROJECTED:          updateProjections() → upsert agent_performance_snapshot + decision_metrics_projection
10. STREAMED:           Sinks.Many emits SnapshotDecisionDTO → SSE subscribers (UI)
11. NOTIFIED:           SlackWebhookSender.sendDecision() → Slack webhook POST
12. CONSUMED:           Next cycle reads from pre-aggregated projection tables
13. INFLUENCES:         Adaptive weights computed for cycle N+1 reflect cycle N's outcomes
14. REGIME_READ:        Scheduler reads persisted regime → adjusts next trigger interval
```

The `FinalDecision` is never returned to external callers — the REST API exposes `List<AnalysisResult>` (the per-agent outputs). The full decision structure is an internal artifact used for persistence, feedback, and UI snapshot rendering.

---

## 12. Runtime Sequence Diagram

```
scheduler-service          agent-orchestrator         market-data-service
      |                           |                           |
      | POST /trigger             |                           |
      | {symbol, traceId}         |                           |
      |-------------------------->|                           |
      |                           |                           |
      |                    Mono.defer() starts                |
      |                    startTime captured                 |
      |                    regime[] = {UNKNOWN}               |
      |                    capturedCtx[] = {null}             |
      |                    contextWrite(traceId)              |
      |                           |                           |
      |                           | GET /quote/{symbol}       |
      |                           | X-Trace-Id: {traceId}     |
      |                           |-------------------------->|
      |                           |                           | check MarketDataCache
      |                           |                           | HIT: return cached
      |                           |                           | MISS: call Alpha Vantage
      |                           |                           |---> [Alpha Vantage API]
      |                           |                           |<---
      |                           |                           | cache.put(symbol, quote)
      |                           |                           |
      |                           |  MarketDataQuote JSON     |
      |                           |<--------------------------|
      |                           |                           |
      |              parse JSON → Context                     |
      |              MarketRegimeClassifier.classify(ctx)     |
      |              regime[0] = RANGING (e.g.)               |
      |              log: MARKET_DATA_FETCHED                 |

      analysis-engine             history-service        notification-service
            |                           |                      |
            |  POST /analyze            |                      |
            |  {Context JSON}           |                      |
<-----------|                           |                      |
            |                           |                      |
   TrendAgent.analyze(ctx)              |                      |
   RiskAgent.analyze(ctx)               |                      |
   PortfolioAgent.analyze(ctx)          |                      |
   DisciplineCoach.analyze(ctx)         |                      |
     ↳ POST api.anthropic.com           |                      |
       (or rule-based fallback)         |                      |
            |                           |                      |
  [List<AnalysisResult>]                |                      |
----------->|                           |                      |
            |                           |                      |
   log: AGENTS_COMPLETED                |                      |
            |                           |                      |
            |   GET /agent-performance  |                      |
            |   X-Trace-Id: {traceId}   |                      |
            |-------------------------->|                      |
            |                           | repo.findAll()       |
            |                           | → aggregate          |
            |  Map<name,PerfModel>       |                      |
            |<--------------------------|                      |
            |                           |                      |
            |   GET /agent-feedback     |                      |
            |   X-Trace-Id: {traceId}   |                      |
            |-------------------------->|                      |
            |                           | repo.findAll()       |
            |                           | → aggregate          |
            |  Map<name,Feedback>        |                      |
            |<--------------------------|                      |
            |                           |                      |
  AgentScoreCalculator.compute(         |                      |
    results, perf, feedback, RANGING)   |                      |
  → Map<name, adaptiveWeight>           |                      |
            |                           |                      |
  DecisionContext.assemble(             |                      |
    symbol, timestamp, traceId,         |                      |
    regime, results, weights, close)    |                      |
            |                           |                      |
  ModelSelector.selectModel(RANGING)    |                      |
  → claude-sonnet-4-6                   |                      |
            |                           |                      |
  AIStrategistService.evaluate(         |                      |
    ctx, results, regime, weights)      |                      |
            |---> [Anthropic Claude API]|                      |
            |<---                       |                      |
  → AIStrategyDecision(HOLD, 0.72,     |                      |
    "Balanced signals, low volatility") |                      |
  log: AI_STRATEGY_EVALUATED            |                      |
            |                           |                      |
  ConsensusIntegrationGuard.resolve()   |                      |
  → DefaultWeightedConsensusStrategy    |                      |
  → ConsensusResult(HOLD, 0.5, weights) |                      |
            |                           |                      |
  divergenceFlag = (HOLD ≠ HOLD) = false|                      |
            |                           |                      |
  DecisionContext.withAIDecision(       |                      |
    aiDecision, 0.5, false,             |                      |
    "sonnet-deep")                      |                      |
  log: DECISION_CONTEXT_ASSEMBLED       |                      |
            |                           |                      |
  buildDecision(event, results,         |                      |
    latencyMs, weights, RANGING,        |                      |
    aiDecision)                         |                      |
  → FinalDecision (v7, 17 fields)       |                      |
            |                           |                      |
  log: FINAL_DECISION_CREATED           |                      |
            |                           |                      |
            |   POST /history/save      |                      |
            |   {FinalDecision JSON}    |                      |
            |~~~~~~~~~~~~~~~~~~~~~~~~~~~>  (fire-and-forget)   |
            |                           |                      |
            |   POST /notify/decision   |                      |
            |   {FinalDecision JSON}    |                      |
            |~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~>
            |                           | INSERT INTO          |
            |                           | decision_history     |
            |                           | (PostgreSQL)         |
            |                           |                      | format Slack msg
            |                           |                      | POST webhook
  log: EVENTS_DISPATCHED                |                      |
            |                           |                      |

  return decision.agents()  [List<AnalysisResult>]
----------->|
            |
  HTTP 200 [List<AnalysisResult> JSON]
----------->scheduler-service
            |
            |   GET /latest-regime?symbol=AAPL
            |-----------------------------> history-service
            |   "RANGING"                  |
            |<-----------------------------|
            |
  AdaptiveTempoStrategy.resolve(RANGING) = 5 min
  Mono.delay(5 min) → recurse
            |

Legend:
  ---->  synchronous HTTP (blocking in reactive chain, resumed on response)
  ~~~>   fire-and-forget (subscribe(), pipeline does not wait)
  [   ]  external system
```

---

## 13. Momentum & Market State Architecture

### What It Does

`MomentumStateCalculator` is a pure stateless classifier in `common-lib` that interprets recent decision history to classify overall market momentum stability. It does not generate trading signals, modify state, or participate in the orchestration pipeline. It is consumed by `trade-service` to provide operator awareness.

### Four-Dimensional Analysis

The calculator evaluates four independent dimensions from recent decision history:

**Dimension 1 — Signal Alignment**: Fraction of recent signals matching the dominant signal. Computed by `computeSignalAlignment(List<String> signals)`. Range: 0.0–1.0.

**Dimension 2 — Confidence Trend**: Linear slope of recent confidence scores. Computed by `computeConfidenceTrend(List<Double> confidences)` using least-squares regression. Positive = rising, negative = declining.

**Dimension 3 — Divergence Pressure**: Fraction of recent decisions where AI diverged from consensus. Computed by `computeDivergenceRatio(List<Boolean> divergenceFlags)`. Null flags treated as false.

**Dimension 4 — Regime Stability**: Whether all recent regimes match. Computed by `isRegimeStable(List<String> regimes)`. Returns boolean.

### State Resolution

`resolveState(alignment, confidenceTrend, divergenceRatio, regimeStable)` applies a decision tree (first match wins):

| State | Conditions |
|---|---|
| **CONFIRMED** | alignment ≥ 0.80 AND trend not declining AND divergence < 0.40 AND regime stable |
| **WEAKENING** | alignment ≥ 0.65 AND (declining OR high divergence) |
| **BUILDING** | alignment ≥ 0.65 AND trend rising (> 0.02) AND low divergence |
| **BUILDING** (softer) | alignment ≥ 0.65 AND not declining AND low divergence |
| **CALM** | default fallback |

### Key Constants

| Constant | Value | Purpose |
|---|---|---|
| `MIN_WINDOW` | 3 | Minimum decisions for meaningful classification |
| `ALIGNMENT_THRESHOLD` | 0.65 | Moderate signal alignment |
| `STRONG_ALIGNMENT_THRESHOLD` | 0.80 | Strong alignment for CONFIRMED |
| `DIVERGENCE_PRESSURE_THRESHOLD` | 0.40 | Max divergence ratio for "low divergence" |
| `TREND_RISING_THRESHOLD` | 0.02 | Confidence slope for rising trend |
| `TREND_DECLINING_THRESHOLD` | -0.03 | Confidence slope for declining trend |

### MarketState Enum

```java
public enum MarketState {
    CALM,       // Mixed or weak signals. No clear direction.
    BUILDING,   // Confidence rising. Signal alignment improving. Not yet confirmed.
    CONFIRMED,  // Consecutive aligned signals. Low divergence. Stable/rising confidence.
    WEAKENING   // Momentum losing stability. Confidence declining or divergence increasing.
}
```

---

## 14. Trade Posture & Exit Awareness Architecture

### Trade Posture

`TradePostureInterpreter` is a pure interpreter in `common-lib` that derives a single `TradePosture` label from momentum, structure, and exit awareness signals. It includes a **stability window** to prevent rapid posture flapping.

#### TradePosture Enum

```java
public enum TradePosture {
    CONFIDENT_HOLD,   // momentum confirmed, structure continuing, trade fresh
    WATCH_CLOSELY,    // default cautious stance
    DEFENSIVE_HOLD,   // fatigued structure or extended duration
    EXIT_CANDIDATE    // weakening momentum with confidence decline
}
```

#### Posture Decision Logic

`computeTarget(momentum, structure, awareness)` — first match wins:

1. `CONFIRMED` momentum + `CONTINUATION` structure + `FRESH` duration → **CONFIDENT_HOLD**
2. `STALLING` structure → **WATCH_CLOSELY**
3. `FATIGUED` structure OR `EXTENDED` duration → **DEFENSIVE_HOLD**
4. `WEAKENING` momentum AND confidence drift < -0.1 → **EXIT_CANDIDATE**
5. Default → **WATCH_CLOSELY**

#### Stability Window

`evaluateStable()` suppresses posture downgrades within a 60-second window (`STABILITY_WINDOW_SECONDS = 60`). If the new target posture has lower severity than the previous posture and less than 60 seconds have elapsed, the previous posture is held. This prevents noisy oscillation between states.

Returns `StabilityResult(posture, PostureStabilityState)` where `PostureStabilityState` tracks `lastPosture` and `lastUpdated` timestamp.

### Structure Interpretation

`StructureInterpreter` is a pure classifier in `common-lib` that evaluates momentum structure from metrics and state:

```java
public enum StructureSignal {
    CONTINUATION,  // structure intact, momentum continuing
    STALLING,      // losing momentum, confidence nearly flat
    FATIGUED       // exhausted momentum, high divergence, declining confidence
}
```

**Decision logic** (`evaluate(metrics, momentum)`):

1. `divergenceStreak ≥ 2` AND `confidenceSlope5 < -0.02` → **FATIGUED**
2. `confidenceSlope5` in range [-0.02, 0.02] (nearly flat) → **STALLING**
3. Default → **CONTINUATION**

### Exit Awareness

`ExitAwareness` is a record capturing cognitive exit-condition awareness:

```java
public record ExitAwareness(
    boolean momentumShift,           // TRUE if momentum shifted to WEAKENING
    double confidenceDrift,          // confidence change since trade entry
    boolean divergenceGrowing,       // TRUE if divergence streak >= 2
    String durationSignal,           // FRESH | AGING | EXTENDED
    StructureSignal structureSignal, // CONTINUATION | STALLING | FATIGUED
    TradePosture tradePosture        // operational stance label
)
```

**Duration classification:**

| Duration | Signal |
|---|---|
| < 3 minutes | FRESH |
| 3–8 minutes | AGING |
| > 8 minutes | EXTENDED |

---

## 15. Adaptive Risk Engine Architecture

### What It Does

`AdaptiveRiskEngine` is a pure logic class in `common-lib` that computes exit awareness and risk envelopes from trade context, decision metrics, and momentum state. No WebClient, no repositories, no logging, no reactive types. Consumed by `trade-service`.

### Risk Envelope

```java
public record RiskEnvelope(
    double softStopPercent,         // e.g., -0.6 (base)
    double hardInvalidationPercent, // e.g., -1.1 (base)
    String reasoning                // explanation of stop level adjustments
)
```

**Base stops:** `softStop = -0.6%`, `hardStop = -1.1%`

**Adjustments (cumulative):**

| Condition | Adjustment |
|---|---|
| `marketState == WEAKENING` | `softStop *= 0.6` (tighter) |
| `confidenceDrift < -0.1` | `softStop *= 0.8`, `hardStop *= 0.85` |
| `divergenceStreak ≥ 2` | reasoning note added |
| `confidenceSlope5 < -0.05` | `softStop *= 0.9` (additional tightening) |

### Adaptive Smoothing

`evaluateAdaptive()` provides moderately responsive stop level transitions. Rather than jumping directly to target stops, it applies exponential smoothing:

```
newSoft = previousSoft + (targetSoft - previousSoft) × 0.35
newHard = previousHard + (targetHard - previousHard) × 0.25
```

**Exceptions:** First evaluation (no previous state) and `WEAKENING` momentum bypass smoothing — stops jump directly to target values.

### Adaptive Risk State

```java
public record AdaptiveRiskState(
    String symbol,
    double currentSoftStop,
    double currentHardStop,
    LocalDateTime lastUpdated
)
```

Maintained per-symbol in `trade-service` memory. Updated on each `getActiveTrade()` call.

### Trade Reflection

`ReflectionInterpreter` maintains posture-level trade outcome statistics:

```java
public record TradeReflectionStats(
    TradePosture posture,
    long totalTrades,
    long wins,
    long losses,
    double avgPnl    // rolling average
)
```

`updateStats(current, posture, pnl)` — increments counts, computes rolling average PnL. Called when a trade is closed via `TradeService.exitTrade()`.

---

## 16. Trade Service Architecture

### What It Does

`trade-service` is an operator intelligence layer for trade lifecycle management. It reads projections from `history-service` (read-only), computes risk awareness via pure logic in `common-lib`, and provides REST endpoints for starting/exiting trades and querying active trade context with risk assessment. It has **no linkage to the orchestrator** — it does not influence decision-making.

### Service Responsibilities

**`TradeService`** owns:

1. Trade session lifecycle (start/exit) via `TradeSessionRepository`
2. Active trade context retrieval with adaptive risk evaluation
3. Integration with `history-service` for decision metrics and market state projections
4. Risk envelope computation using `AdaptiveRiskEngine`
5. Posture stability tracking (per-symbol `PostureStabilityState`)
6. Trade reflection statistics (per-posture `TradeReflectionStats`)
7. Per-symbol in-memory state maps: `riskStateMap`, `postureStabilityMap`, `postureMap`, `reflectionMap`

### Active Trade Flow

When `GET /trade/active/{symbol}` is called:

```
1. Find most recent open TradeSession (exitTime IS NULL)
2. Fetch DecisionMetricsResponse from history-service
3. Fetch MarketStateResponse from history-service
4. Construct ActiveTradeContext from trade session entry fields
5. Construct MetricsInput (lastConfidence, confidenceSlope5, divergenceStreak, momentumStreak)
6. Construct MomentumInput (marketState, confidenceTrend, divergenceRatio)
7. Call riskEngine.evaluateAdaptive(context, metrics, momentum, previousState, previousPosture)
   → Returns: ExitAwareness + RiskEnvelope + updated AdaptiveRiskState + PostureStabilityState
8. Update in-memory maps with new state
9. Return ActiveTradeResponse
```

### Data Models

```java
public record ActiveTradeContext(
    String symbol, double entryPrice, LocalDateTime entryTime,
    double entryConfidence, String entryRegime, String entryMomentum
)

@Table("trade_sessions")
public class TradeSession {
    Long id, String symbol, LocalDateTime entryTime,
    Double entryPrice, Double entryConfidence, String entryRegime, String entryMomentum,
    LocalDateTime exitTime, Double exitPrice, Double pnl, Long durationMs
}
```

### REST API

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/v1/trade/start` | POST | Start new trade session |
| `/api/v1/trade/exit` | POST | Close active trade with exit price |
| `/api/v1/trade/active/{symbol}` | GET | Active trade context with risk assessment |
| `/api/v1/trade/health` | GET | Liveness probe |

### UI Integration

**`TacticalPostureHeader`** — Displays when an active trade exists:
- Posture pill (color-coded: green/blue/orange/red)
- Status chips for Momentum (Stable/Weakening), Structure (Continuation/Stalling/Fatigued), Duration (Fresh/Aging/Extended)
- Confidence trend mini-sparkline (7-point SVG)
- EXIT_CANDIDATE triggers a `calm-pulse` animation

**`MomentumBanner`** — Always visible, center-aligned:
- Per-symbol momentum pills showing market state
- State-specific indicators: gray dot (CALM), amber triangle (BUILDING), green circle with glow (CONFIRMED), blue inverted triangle (WEAKENING)
- Dominant signal and confidence trend arrows for CONFIRMED state

### Architectural Principles

1. **Read-only intelligence** — reads from history-service, never writes to orchestrator
2. **Operator awareness only** — all signals inform the operator, never automate decisions
3. **Pure logic in common-lib** — all cognitive computation is stateless and testable
4. **Stability suppression** — 60-second window prevents posture flapping
5. **Adaptive smoothing** — risk stops transition gradually unless momentum is WEAKENING
6. **No I/O in pure logic** — `AdaptiveRiskEngine`, `TradePostureInterpreter`, `StructureInterpreter`, `MomentumStateCalculator`, `ReflectionInterpreter` have zero dependencies

---

*Document generated from codebase analysis. All class names, method signatures, and data flows verified against source. 9 modules (8 Java + 1 React UI), 70+ Java files, 20+ REST endpoints, 17-component FinalDecision (v7), 11-field DecisionContext, 4 analysis agents, 2 AI integration points (DisciplineCoach + AIStrategistService), regime-aware model selection, regime-aware cache, 1 adaptive scheduler, momentum tracking, trade posture interpretation, adaptive risk engine, PostgreSQL persistence. Last updated: 2026-02-26.*
