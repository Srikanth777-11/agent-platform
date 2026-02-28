# Codebase Reference
> How the code works. File map, endpoints, schemas, domain objects, patterns.
> Pair with MEMORY.md (architecture) and EVOLUTION-PATHS.md (history).

---

## Module → Port → Internal URL

Single stack (Phase 34.1). `docker-compose.dev.yml` is the **only active compose file**. `docker-compose.yml` is archive.

| Module | Host Port | Container URL (internal) |
|---|---|---|
| market-data-service | 8080 | `http://market-data-service:8080` |
| agent-orchestrator  | 8081 | `http://agent-orchestrator:8081` |
| scheduler-service   | 8082 | `http://scheduler-service:8082` |
| analysis-engine     | 8083 | `http://analysis-engine:8083` |
| notification-service| 8084 | `http://notification-service:8084` |
| history-service     | 8085 | `http://history-service:8085` |
| trade-service       | 8086 | `http://trade-service:8086` |
| ui (Vite dev)       | 5173 | — |
| postgres            | 5432 | `agent-postgres:5432` |

**Network:** `agent-net`  **Volume:** `pgdata`  **Previous dev data:** preserved in `agent-platform-dev_pgdata-dev` volume (reference only, not active)

---

## All API Endpoints

### market-data-service `/api/v1/market-data`
```
GET  /quote/{symbol}          → MarketDataQuote
GET  /health                  → "OK"
# profile=historical-replay only:
POST /replay/fetch-history?symbol=&fromDate=&toDate=  → {candlesIngested, symbol, ...}
POST /replay/start?symbol=    → ReplayState map
POST /replay/stop             → "Stop signal sent"
POST /replay/reset?symbol=    → "Reset complete"
GET  /replay/status           → ReplayState map
```

### analysis-engine `/api/v1/analyze`
```
POST /                        body: Context → List<AnalysisResult>
GET  /health                  → "OK"
```

### agent-orchestrator `/api/v1/orchestrate`
```
POST /trigger                 body: MarketDataEvent → List<AnalysisResult>
GET  /health                  → "OK"
```

### notification-service `/api/v1/notify`
```
POST /                        body: {traceId, symbol, results} → 200
POST /decision                body: FinalDecision → 200
GET  /health                  → "OK"
```

### history-service `/api/v1/history`
```
POST /save                    body: FinalDecision → 200
GET  /snapshot                → Flux<SnapshotDecisionDTO>
GET  /stream                  → SSE Flux<SnapshotDecisionDTO>  (event: "snapshot")
GET  /market-state            → Flux<MarketStateDTO>
GET  /agent-feedback          → Map<String,AgentFeedback>
GET  /agent-performance       → Map<String,AgentPerformanceModel>
GET  /agent-performance-snapshot → Flux<AgentPerformanceSnapshot>
GET  /latest-regime?symbol=   → String (regime name)
GET  /decision-metrics/{symbol} → DecisionMetricsDTO
GET  /feedback-loop-status    → Flux<FeedbackLoopStatusDTO>
GET  /recent/{symbol}?limit=3 → Flux<RecentDecisionMemoryDTO>
GET  /unresolved/{symbol}?sinceMins=10 → Flux<SnapshotDecisionDTO>
POST /outcome/{traceId}       body: {outcomePercent, holdMinutes} → 200
POST /resolve-outcomes/{symbol}?currentPrice= → 200
GET  /replay/{symbol}?limit=50 → Flux<SnapshotDecisionDTO>  (legacy)
GET  /analytics/edge-report?symbol= → EdgeReportDTO
# Phase-32 replay candles:
POST /replay-candles/ingest   body: List<ReplayCandleDTO> → Long (count ingested)
GET  /replay-candles/{symbol} → Flux<ReplayCandleDTO>
DELETE /replay-candles/{symbol} → 200
GET  /replay-candles/{symbol}/count → Long
GET  /health                  → "OK"
```

### trade-service `/api/v1/trade`
```
POST /start                   body: TradeStartRequest → TradeSession
POST /exit                    body: TradeExitRequest  → TradeSession
GET  /active/{symbol}         → ActiveTradeResponse
GET  /history                 → Flux<TradeSession>
GET  /reflection-stats        → Map<String,TradeReflectionStats>
GET  /health                  → "OK"
```

---

## Domain Records (common-lib)

### MarketDataEvent
```java
String symbol, Instant triggeredAt, String traceId
```

### Context  (→ analysis-engine input)
```java
String symbol, Instant timestamp, Map<String,Object> marketData,
List<Double> prices, String traceId
```

### AnalysisResult  (→ per-agent output)
```java
String agentName, String summary,
String signal,          // BUY / SELL / HOLD / WATCH
double confidenceScore,
Map<String,Object> metadata
```

### FinalDecision  (24 fields, v9)
```java
// v1
String symbol, Instant timestamp, List<AnalysisResult> agents,
String finalSignal, double confidenceScore, Map<String,Object> metadata, String traceId
// v2
String decisionVersion, String orchestratorVersion, int agentCount, long decisionLatencyMs
// v3
double consensusScore, Map<String,Double> agentWeightSnapshot
// v4
Map<String,Double> adaptiveAgentWeights
// v5
MarketRegime marketRegime   // CALM/RANGING/TRENDING/VOLATILE/UNKNOWN
// v6
String aiReasoning
// v7
Boolean divergenceFlag
// v8
String tradingSession, Double entryPrice, Double targetPrice,
Double stopLoss, Integer estimatedHoldMinutes
// v9 (Phase 33)
TradeDirection tradeDirection,   // LONG/SHORT/FLAT
String directionalBias           // DirectionalBias.name() — e.g. "BULLISH", "STRONG_BEARISH"
```

### AIStrategyDecision  (8 fields)
```java
String finalSignal, double confidence, String reasoning,
Double entryPrice, Double targetPrice, Double stopLoss, Integer estimatedHoldMinutes,
TradeDirection tradeDirection   // Phase 33
```

### DecisionContext  (19 fields, immutable, copy-factories)
Key fields: symbol, traceId, timestamp, regime, agentResults, adaptiveWeights,
latestClose, tradingSession, aiDecision, consensusScore, divergenceFlag,
stabilityPressure, calmTrajectory, divergenceTrajectory,
reflectionState, calmMood, reflectionPersistence,
directionalBias   // Phase 33 — DirectionalBias enum

### MarketDataQuote
```java
String symbol, double latestClose, double open, double high, double low,
long volume, List<Double> recentClosingPrices,  // newest-first, max 50
Instant fetchedAt
```

### AgentFeedback
```java
String agentName, double winRate, double avgConfidence, double avgLatencyMs, long totalDecisions
```

### AgentPerformanceModel
```java
String agentName, double historicalAccuracyScore, double latencyWeight, long totalDecisions
```

---

## Database Schema (history-service, PostgreSQL)

### decision_history
```sql
id BIGSERIAL PK, symbol VARCHAR(20), timestamp TIMESTAMP, agents TEXT (JSON),
final_signal VARCHAR(10), confidence_score DOUBLE, metadata TEXT (JSON),
trace_id VARCHAR(100), saved_at TIMESTAMP,
-- v2
decision_version, orchestrator_version, agent_count INT, decision_latency_ms BIGINT,
-- v3
consensus_score DOUBLE, agent_weight_snapshot TEXT (JSON),
-- v4
adaptive_agent_weights TEXT (JSON),
-- v5
market_regime VARCHAR(20),
-- v6
ai_reasoning TEXT,
-- v7
divergence_flag BOOLEAN,
-- v8
trading_session VARCHAR(30), entry_price DOUBLE, target_price DOUBLE,
stop_loss DOUBLE, estimated_hold_minutes INT,
outcome_percent DOUBLE, outcome_hold_minutes INT, outcome_resolved BOOLEAN,
-- Phase 33
trade_direction VARCHAR(10),       -- LONG / SHORT / FLAT
directional_bias VARCHAR(20),      -- DirectionalBias enum name
-- Phase 34.4
decision_mode VARCHAR(30)          -- LIVE_AI (default) | REPLAY_CONSENSUS_ONLY
INDEX: (symbol, saved_at DESC)
```

**decision_mode values:**
- `LIVE_AI` — normal production decisions (AI Strategist + DivergenceGuard active). Default when field is NULL.
- `REPLAY_CONSENSUS_ONLY` — historical replay decisions (consensus path only; AI Strategist skipped).
- `findResolvedDecisions()` filters: `AND (decision_mode IS NULL OR decision_mode = 'LIVE_AI')` — replay rows excluded from agent win-rate computation.

### agent_performance_snapshot
```sql
agent_name VARCHAR(50) PK,
historical_accuracy_score DOUBLE DEFAULT 0.5,
latency_weight DOUBLE DEFAULT 0.0,
win_rate DOUBLE DEFAULT 0.0,
avg_confidence DOUBLE DEFAULT 0.0,
avg_latency_ms DOUBLE DEFAULT 0.0,
total_decisions BIGINT DEFAULT 0,
sum_confidence DOUBLE, sum_latency_ms DOUBLE, sum_wins DOUBLE,
regime_bias VARCHAR(20), last_updated TIMESTAMP
```

### decision_metrics_projection
```sql
symbol VARCHAR(20) PK,
last_confidence DOUBLE, confidence_slope_5 DOUBLE,
divergence_streak INT, momentum_streak INT, last_updated TIMESTAMP
```

### trade_sessions
```sql
id BIGSERIAL PK, symbol, entry_time, entry_price, entry_confidence,
entry_regime, entry_momentum, exit_time, exit_price, pnl, duration_ms
```

### replay_candles  (Phase-32)
```sql
id BIGSERIAL PK, symbol VARCHAR(20), candle_time TIMESTAMP,
open DOUBLE, high DOUBLE, low DOUBLE, close DOUBLE, volume BIGINT,
UNIQUE(symbol, candle_time)
INDEX: (symbol, candle_time ASC)
```

---

## Key File Map

### common-lib
```
model/
  FinalDecision.java         24-field record (v9), 8 backward-compat factories
  AnalysisResult.java        agentName, signal, confidenceScore, metadata
  Context.java               orchestrator→analysis input (prices, marketData)
  DecisionContext.java       immutable pipeline snapshot, 19 fields, copy-factories
                             withDirectionalBias(DirectionalBias) — Phase 33
  AIStrategyDecision.java    AI output: signal + entry/target/stop/hold + tradeDirection (8 fields)
  MarketRegime.java          enum: CALM RANGING TRENDING VOLATILE UNKNOWN
  TradeDirection.java        enum: LONG / SHORT / FLAT  (Phase 33)
  AgentFeedback.java         winRate, avgConfidence, avgLatencyMs
  AgentPerformanceModel.java historicalAccuracyScore, latencyWeight
  MarketState.java           BUILDING_MOMENTUM / COOLING_DOWN / etc.
event/
  MarketDataEvent.java       symbol, triggeredAt, traceId
cognition/
  TradingSession.java        enum: OPENING_BURST POWER_HOUR MIDDAY_CONSOLIDATION OFF_HOURS
  TradingSessionClassifier   .classify(Instant) → TradingSession (IST-aware)
  DirectionalBias.java       enum: STRONG_BULLISH BULLISH NEUTRAL BEARISH STRONG_BEARISH  (Phase 33)
  CalmTrajectory.java        ASCENDING / PLATEAU / DESCENDING
  DivergenceTrajectory.java  CONVERGING / STABLE / DIVERGING
  StabilityPressureCalculator
  ArchitectReflectionInterpreter  → ReflectionResult(state, mood, persistence)
consensus/
  AgentScoreCalculator       .compute(results, perf, feedback, regime) → Map<String,Double> weights
  ConsensusEngine            evaluates weighted signal votes
  DivergenceGuard            Rule1=OVERRIDE if |divergence|>0.65, Rule2=DAMPEN ×0.80, floor=0.50
classifier/
  MarketRegimeClassifier     .classify(Context) → MarketRegime
  MomentumStateCalculator    .classify(signals, confidences, divergences, regimes) → MarketState
guard/
  DivergenceGuard            CONFIDENCE_DAMPEN_FACTOR=0.80, CONFIDENCE_FLOOR=0.50
```

### market-data-service
```
client/
  MarketDataWebClient.java   fetchQuote(symbol), fetchHistoricalCandles(symbol,from,to,interval)
  AngelOneAuthService.java   getToken(), refreshToken() — TOTP-based JWT cache
  MarketDataController.java  GET /quote/{symbol}
config/
  WebClientConfig.java       beans: marketDataWebClient (Alpha Vantage base), angelOneAuthClient
                             bean: MarketDataWebClient
provider/
  MarketDataProvider.java    interface: Mono<MarketDataQuote> getQuote(String symbol)
service/
  MarketDataService.java     @Service, implements MarketDataProvider (live, default)
                             regime-aware in-memory cache (CachedMarketData)
replay/  (profile: historical-replay)
  HistoricalReplayProvider   @Primary @Profile, cursor-based real OHLCV
  ReplayRunnerService        fetchAndStoreHistory(), startReplay(), stopReplay(), reset()
                             Phase 34: sends X-Replay-Mode: true header on each trigger POST
                             Phase 34: PIPELINE_SETTLE_MS = 500ms (reduced from 1500ms)
  ReplayController           REST /api/v1/market-data/replay/*
  ReplayState.java           IDLE/RUNNING/COMPLETE/ERROR, progress fields
  ReplayCandleDTO.java       local mirror of history-service DTO
  ReplayMarketProvider.java  @Primary @Profile("replay") — OLD synthetic replay from decisions
resources/
  application.yml            port 8080, angel-one config, symbol-tokens map
  application-replay.yml     profile=replay (legacy synthetic replay)
  application-historical-replay.yml  profile=historical-replay, sets HISTORY_URL + ORCHESTRATOR_URL
```

### analysis-engine
```
agent/
  AnalysisAgent.java         interface
  TrendAgent.java            SMA crossover, RSI, momentum signals
                             Phase 33: 5-vote directional bias (trend, MACD, price>SMA20,
                             price>EMA12, 5-candle momentum) → DirectionalBias enum name
                             stored in metadata["directionalBias"]
  RiskAgent.java             volatility, drawdown signals
  PortfolioAgent.java        allocation, diversification signals
  DisciplineCoach.java       @Profile("!test"), calls Claude Haiku via Anthropic API
                             runs on Schedulers.boundedElastic() (blocking)
                             NOTE: still runs during replay (analysis-engine receives no replay flag)
service/
  AgentDispatchService.java  parallel dispatch: Flux.merge(all 4 agents) → collectList()
controller/
  AnalysisController.java    POST /api/v1/analyze (Context → List<AnalysisResult>)
indicator/
  TechnicalIndicators.java   SMA, RSI, MACD helpers
```

### agent-orchestrator
```
service/
  OrchestratorService.java   main pipeline: MarketDataEvent → FinalDecision
                             flow: fetchMarketData → classifyRegime → classifySession
                               → analysisEngine → extractDirectionalBias (Phase 33)
                               → weights → feedback → [if !replayMode: fetchStrategyMemory → AI]
                               → [if replayMode: consensus direct]
                               → DivergenceGuard → buildDecision → publish
                               → resolveOpenOutcomes (fire-forget)
                             Phase 33: extracts metadata["directionalBias"] from TrendAgent result
                             Phase 34: orchestrate(event, replayMode) — replayMode skips
                               fetchStrategyMemory(), aiStrategistService.evaluate(),
                               sets divergenceStreak=0; uses ConsensusIntegrationGuard directly
ai/
  AIStrategistService.java   Claude API call (Sonnet or Haiku per ModelSelector)
                             builds prompt from DecisionContext + strategy memory
                             Phase 33: bias section in prompt; parseResponse() extracts tradeDirection
  ModelSelector.java         VOLATILE→Haiku, others→Sonnet
adapter/
  PerformanceWeightAdapter   GET /agent-performance from history-service
  AgentFeedbackAdapter       GET /agent-feedback from history-service
guard/
  ConsensusIntegrationGuard  validates AI vs consensus before persistence
                             used directly (bypassing AI) in replayMode
controller/
  OrchestratorController.java POST /api/v1/orchestrate/trigger
                             Phase 34: reads @RequestHeader X-Replay-Mode (default false)
                             passes boolean replayMode to OrchestratorService.orchestrate()
publisher/
  RestDecisionEventPublisher POST /save to history-service, POST /decision to notification-service
logger/
  DecisionFlowLogger.java    MDC stage logging
```

### history-service
```
model/
  DecisionHistory.java       @Table("decision_history"), 27+ fields with Lombok @Data
                             Phase 33: +tradeDirection, +directionalBias
                             Phase 34: +decisionMode (LIVE_AI | REPLAY_CONSENSUS_ONLY)
  AgentPerformanceSnapshot   @Table("agent_performance_snapshot")
  DecisionMetricsProjection  @Table("decision_metrics_projection")
  TradeSession.java          @Table("trade_sessions")
  ReplayCandle.java          @Table("replay_candles") — Phase-32
repository/
  DecisionHistoryRepository  findLatestPerSymbol(), findReplayCandles(), findUnresolvedSignals()
                             findRecentBySymbol()
                             findResolvedDecisions(limit) — Phase 34: filters
                               AND (decision_mode IS NULL OR decision_mode = 'LIVE_AI')
  AgentPerformanceSnapshotRepository  upsertAgent(), normalizeLatencyWeights()
  DecisionMetricsProjectionRepository upsertMetrics()
  ReplayCandleRepository     findBySymbolOrderByCandleTimeAsc(), deleteBySymbol(), countBySymbol()
service/
  HistoryService.java        save(), getAgentFeedback(), getAgentPerformance(),
                             getLatestSnapshot(), streamSnapshots() (Sinks.Many SSE),
                             getEdgeReport(), getFeedbackLoopStatus(), getRecentDecisions(),
                             recordOutcome(), resolveOutcomes(), rescoreAgentsByOutcome()
                             Phase 33: maps tradeDirection + directionalBias from FinalDecision
                             Phase 34: maps metadata["decision_mode"] → entity.decisionMode;
                               defaults to "LIVE_AI" when not present
  ReplayCandleService.java   ingest(), getCandles(), getCount(), deleteBySymbol()
controller/
  HistoryController.java     all /api/v1/history/* endpoints
dto/
  SnapshotDecisionDTO        symbol, finalSignal, confidence, marketRegime, divergenceFlag,
                             aiReasoning, savedAt, tradingSession, entryPrice, targetPrice,
                             stopLoss, estimatedHoldMinutes,
                             tradeDirection, directionalBias  (Phase 33)
  EdgeReportDTO              n, winRate, avgGain, avgLoss, rr, expectancy, maxDD,
                             session win rates (OB/PH/MD), stability rates, confidence rates
  FeedbackLoopStatusDTO      agentName, winRate, sampleSize, accuracyScore, source
  RecentDecisionMemoryDTO    finalSignal, confidenceScore, divergenceFlag, marketRegime
  ReplayCandleDTO            symbol, candleTime, open, high, low, close, volume
resources/
  schema.sql                 Phase 33: trade_direction VARCHAR(10), directional_bias VARCHAR(20)
                             Phase 34: decision_mode VARCHAR(30)
```

### scheduler-service
```
job/
  MarketDataScheduler.java   @PostConstruct → per-symbol adaptive loop
                             loop: delay → [isReplayRunning? skip : POST /orchestrate/trigger]
                               → GET /latest-regime → repeat
                             Phase 34: isReplayRunning() — GET /api/v1/market-data/replay/status
                               → if RUNNING, skips live trigger for that cycle
strategy/
  AdaptiveTempoStrategy.java OFF_HOURS=30min, MIDDAY=15min
                             CALM=10min, RANGING=5min, TRENDING=2min, VOLATILE=30s
client/
  HistoryClient.java         GET /api/v1/history/latest-regime?symbol=
                             Phase 34: @Qualifier("historyWebClient") on constructor injection
config/
  SchedulerConfig.java       Phase 34: @Bean("marketDataClient"), @Bean("orchestratorClient"),
                             @Bean("historyWebClient") with @Qualifier to resolve WebClient
                             bean ambiguity in Spring context
resources/
  application.yml            Phase 34: services.market-data.base-url added for replay status endpoint
config: scheduler.symbols=${WATCHED_SYMBOLS:-NIFTYBEES.BSE}
```

### trade-service
```
service/
  TradeService.java          startTrade(), exitTrade(), getActiveTrade(), getTradeHistory()
                             in-memory state: Map<String,TradeSession> activeTrades
                             in-memory: riskStateMap, portfolioSummaryMap — LOST ON RESTART
controller/
  TradeController.java       /api/v1/trade/*
```

### notification-service
```
sender/
  SlackWebhookSender.java    send(traceId, symbol, results), sendDecision(FinalDecision)
                             SLACK_ENABLED env var gate
controller/
  NotificationController.java POST /api/v1/notify, POST /api/v1/notify/decision
```

### ui  (React 18 + Vite + TailwindCSS + Framer Motion)
```
App.jsx                      main layout: header + panels + modals
                             SSE: EventSource('/api/v1/history/stream') → live snapshot updates
                             auto-refresh: 30s countdown
components/
  SnapshotCard.jsx           per-symbol signal card
                             Phase 33: DirectionBadge (↑ LONG / ↓ SHORT / — FLAT) + bias label
  DetailModal.jsx            expanded view + trade start/exit
                             Phase 33: tradeDirection row + directionalBias row
  MomentumBanner.jsx         market state banner
  TacticalPostureHeader.jsx  active trade awareness
  TradeReflectionPanel.jsx   collapsible trade history
  FeedbackLoopPanel.jsx      collapsible agent win-rate table
  ReplayPanel.jsx            Phase-32 replay controls + progress + post-replay weights
vite.config.js               Phase 34.1: proxy targets updated 9085→8085, 9086→8086
```

---

## Spring Profiles

| Profile | What activates |
|---|---|
| *(none)* | MarketDataService (live Alpha Vantage / Angel One) |
| `replay` | ReplayMarketProvider (@Primary) — synthetic replay from decision history |
| `historical-replay` | HistoricalReplayProvider (@Primary) + ReplayRunnerService + ReplayController |

Activate via `SPRING_PROFILES_ACTIVE=historical-replay` in docker-compose or env.

---

## Inter-Service Wiring (env vars)

### agent-orchestrator
```
MARKET_DATA_URL     → market-data-service  (GET /quote, GET /market-data/...)
ANALYSIS_ENGINE_URL → analysis-engine      (POST /analyze)
NOTIFICATION_URL    → notification-service (POST /notify, POST /notify/decision)
HISTORY_URL         → history-service      (POST /save, GET /agent-*, GET /recent/*, POST /resolve-outcomes/*)
ANTHROPIC_API_KEY   → Claude API
```

### scheduler-service
```
ORCHESTRATOR_URL    → agent-orchestrator   (POST /orchestrate/trigger)
HISTORY_URL         → history-service      (GET /latest-regime)
WATCHED_SYMBOLS     → default NIFTYBEES.BSE
# Phase 34 addition:
services.market-data.base-url → market-data-service (GET /replay/status — replay gate check)
```

### trade-service
```
HISTORY_SERVICE_URL → history-service      (GET /market-state, GET /decision-metrics/*)
POSTGRES_R2DBC_URL  → postgres
```

### history-service
```
POSTGRES_R2DBC_URL  → postgres (R2DBC reactive)
```

### market-data-service (historical-replay profile)
```
HISTORY_URL         → history-service      (POST /replay-candles/ingest, GET /replay-candles/*, GET /snapshot)
ORCHESTRATOR_URL    → agent-orchestrator   (POST /orchestrate/trigger, with X-Replay-Mode: true header)
ANGELONE_API_KEY, ANGELONE_CLIENT_ID, ANGELONE_PASSWORD, ANGELONE_TOTP_SECRET
```

---

## Scripts

### scripts/load_nifty_candles.py
Loads Yahoo Finance NIFTY50 5-minute candles directly into the `replay_candles` table (bypassing Angel One). Useful for seeding replay data without Angel One API credentials.

```
DB_PORT = 5432   (Phase 34.1: updated from 9432)
Target table: replay_candles
Symbol: configurable (default NIFTY50 / ^NSEI)
Interval: 5m
```

Usage: `python scripts/load_nifty_candles.py`

---

## Key Patterns

### Orchestrator pipeline (OrchestratorService.orchestrate)

**Live mode** (`X-Replay-Mode` header absent or `false`):
```
MarketDataEvent
  → fetchMarketDataAndBuildContext()         // GET /quote → Context
  → MarketRegimeClassifier.classify()        // CALM/RANGING/TRENDING/VOLATILE
  → TradingSessionClassifier.classify()      // OPENING_BURST/POWER_HOUR/MIDDAY/OFF_HOURS
  → resolveOpenOutcomes() [fire-forget]      // POST /resolve-outcomes/{symbol}
  → callAnalysisEngine()                     // POST /analyze → List<AnalysisResult>
  → extractDirectionalBias()                 // from TrendAgent metadata["directionalBias"] (Phase 33)
  → PerformanceWeightAdapter.fetchPerformanceWeights()  // GET /agent-performance
  → AgentFeedbackAdapter.fetchFeedback()               // GET /agent-feedback
  → AgentScoreCalculator.compute()           // adaptive weights
  → DecisionContext.assemble() + withCalmOmega() + withReflection() + withDirectionalBias() (Phase 33)
  → fetchStrategyMemory()                    // GET /recent/{symbol}?limit=3
  → AIStrategistService.evaluate()           // Claude API → AIStrategyDecision (incl. tradeDirection, Phase 33)
  → DivergenceGuard.evaluate()               // may override signal or dampen confidence
  → buildDecision()                          // FinalDecision v9 (Phase 33: +tradeDirection, +directionalBias)
  → decisionEventPublisher.publish()         // POST /save (decision_mode=LIVE_AI) + POST /notify/decision
```

**Replay mode** (`X-Replay-Mode: true` header present — Phase 34):
```
MarketDataEvent (from ReplayRunnerService trigger)
  → fetchMarketDataAndBuildContext()         // HistoricalReplayProvider (cursor-based OHLCV)
  → MarketRegimeClassifier.classify()
  → TradingSessionClassifier.classify()
  → resolveOpenOutcomes() [fire-forget]
  → callAnalysisEngine()                     // POST /analyze → List<AnalysisResult>
                                             // (DisciplineCoach still runs in analysis-engine)
  → extractDirectionalBias()
  → PerformanceWeightAdapter + AgentFeedbackAdapter + AgentScoreCalculator
  → DecisionContext.assemble() + enrichments
  → [SKIP fetchStrategyMemory()]             // no strategy memory in replay
  → [SKIP AIStrategistService.evaluate()]    // no Claude API call in replay; divergenceStreak=0
  → ConsensusIntegrationGuard directly       // consensus-only decision
  → buildDecision()                          // FinalDecision v9
  → decisionEventPublisher.publish()         // POST /save (decision_mode=REPLAY_CONSENSUS_ONLY)
```

### Agent win rate / feedback loop
- On each `resolveOutcomes()`: per-agent `win = 1.0` if agent signal direction matched profitable outcome
- `snapshotRepository.upsertAgent(agentName, confidence, latencyMs, win, regime)` — rolling averages
- `getAgentFeedback()` → market-truth win rate if ≥5 resolved outcomes, else 0.5 fallback
- `getFeedbackLoopStatus()` → shows `source: market-truth` vs `source: fallback` per agent

### TradingSession gate (in AI prompt + session field)
```
OPENING_BURST (9:15–10:00 IST): active scalping — BUY/SELL allowed
POWER_HOUR    (15:00–15:30 IST): active scalping — BUY/SELL allowed
MIDDAY_CONSOLIDATION (10:00–15:00 IST): WATCH/HOLD only
OFF_HOURS: HOLD only
```
Implemented in `TradingSessionClassifier.classify(Instant)` — UTC-aware, converts to IST internally.

### Phase-32/34 Replay loop (ReplayRunnerService)

Phase 34 changes: `PIPELINE_SETTLE_MS` reduced from 1500ms → 500ms; `X-Replay-Mode: true` header sent on each trigger; live scheduler skips triggers while replay RUNNING.

```
loadCandlesFromHistory(symbol)          // GET /replay-candles/{symbol}
provider.loadCandles(symbol, candles)   // into HistoricalReplayProvider memory
for each candle[i]:
  provider.setCursor(symbol, i)         // set before trigger
  POST /orchestrate/trigger             // with header: X-Replay-Mode: true
    { symbol, candle[i].candleTime as triggeredAt, uuid traceId }
  Thread.sleep(500)                     // PIPELINE_SETTLE_MS = 500ms (Phase 34)
  GET /history/snapshot → filter symbol → finalSignal, entryPrice, estimatedHoldMinutes
  if BUY/SELL:
    exitIdx = min(i + holdMinutes/5, candles.size()-1)
    outcome = (candles[exitIdx].close - entryPrice) / entryPrice * 100
    if SELL: outcome = -outcome
    POST /history/outcome/{traceId} { outcomePercent, holdMinutes }
    → triggers rescoreAgentsByOutcome() → adaptive weights update immediately
    → decision_history row tagged decision_mode = 'REPLAY_CONSENSUS_ONLY'
    → excluded from findResolvedDecisions() agent win-rate computation (Phase 34.4)
```

**Effective throughput:** ~2s per candle (500ms settle + ~1.5s analysis-engine parallel dispatch).
**8,400 candles (60 days):** ~4.5–5 hours wall-clock time.

### Angel One auth (AngelOneAuthService)
- Cached JWT token (refreshed on expiry or 401)
- TOTP generated programmatically from `ANGELONE_TOTP_SECRET` (same base32 secret as authenticator app)
- Login path: `POST /rest/auth/angelbroking/user/v1/loginByPassword`
- Historical data: `POST /rest/secure/angelbroking/historical/v1/getCandleData`
- Request body: `{exchange, symboltoken, interval, fromdate, todate}` (format: `"yyyy-MM-dd HH:mm"`)
- Symbol tokens in application.yml: `NIFTY.NSE=26000`, `NIFTYBEES.NSE=1594`, `NIFTYBEES.BSE=1594`

### DivergenceGuard rules
```
divergenceStreak from strategy memory (consecutive divergenceFlag=true decisions)
Rule 1 (OVERRIDE):  streak >= threshold(0.65) AND confidence gap large → use consensus signal
Rule 2 (DAMPEN):    moderate divergence → aiConfidence × 0.80, floor 0.50
aiReasoning gets "[OVERRIDE: ...]" or "[OVERRIDE: ConfidenceDampen ...]" appended
divergenceFlag = pre-override disagreement (never changed by guard)
```

### Directional Bias Architecture (Phase 33)

TrendAgent computes a 5-vote majority to produce a `DirectionalBias`. The bias flows through the pipeline and enforces LONG/SHORT gating in the AI prompt.

**5-vote computation (TrendAgent):**

| Vote | Condition | Bullish |
|---|---|---|
| Trend | Recent price slope positive | Yes |
| MACD histogram | Histogram > 0 | Yes |
| Price vs SMA20 | `latestClose > SMA20` | Yes |
| Price vs EMA12 | `latestClose > EMA12` | Yes |
| 5-candle momentum | Last 5 closes trending up | Yes |

Vote count → DirectionalBias: 5=STRONG_BULLISH, 4=BULLISH, 2-3=NEUTRAL, 1=BEARISH, 0=STRONG_BEARISH.

**Pipeline flow:**
```
TrendAgent → metadata["directionalBias"] = "BULLISH"
  → OrchestratorService.extractDirectionalBias() → DirectionalBias.valueOf(...)
  → DecisionContext.withDirectionalBias(bias)
  → AIStrategistService.buildPrompt() injects bias section:
      "Only generate BUY/LONG when bias is BULLISH or STRONG_BULLISH.
       Only generate SELL/SHORT when bias is BEARISH or STRONG_BEARISH.
       NEUTRAL → prefer HOLD or WATCH."
  → AI returns tradeDirection (LONG/SHORT/FLAT) in JSON response
  → FinalDecision v9: tradeDirection + directionalBias persisted
  → SnapshotDecisionDTO: both fields surfaced to UI
```

**UI display:**

| tradeDirection | Badge |
|---|---|
| LONG | ↑ LONG (green) |
| SHORT | ↓ SHORT (red) |
| FLAT | — FLAT (neutral) |

### Replay Mode Architecture (Phase 34)

Replay mode isolates historical replay runs from live production concerns:

| Concern | Mechanism |
|---|---|
| Scheduler silence | `MarketDataScheduler.isReplayRunning()` — polls `GET /replay/status`; skips live trigger if `RUNNING` |
| AI cost + rate limits | `X-Replay-Mode: true` header → `OrchestratorService` skips `fetchStrategyMemory()` + `AIStrategistService.evaluate()`. DisciplineCoach still runs (analysis-engine is not replay-aware). |
| Data isolation | `decision_mode = 'REPLAY_CONSENSUS_ONLY'` tagged on all replay saves. `findResolvedDecisions()` WHERE clause excludes these rows from agent win-rate computation. |
| Throughput | 500ms settle + ~1.5s analysis-engine dispatch ≈ 2s/candle. 8,400 candles ≈ 4.5–5 hours. |

**Replay decision path (simplified):**
```
ReplayRunnerService → POST /trigger + X-Replay-Mode:true
  → OrchestratorService (replayMode=true):
      analysis-engine runs (4 agents including DisciplineCoach)
      consensus computed directly (no AI)
      FinalDecision built with decision_mode=REPLAY_CONSENSUS_ONLY in metadata
  → HistoryService.save() tags decision_mode=REPLAY_CONSENSUS_ONLY
```

**Live decision path after replay:**
- `findResolvedDecisions()` only returns rows where `decision_mode IS NULL OR decision_mode = 'LIVE_AI'`
- Agent win rates and EdgeReport reflect only real AI-driven production decisions

### SSE push (history-service)
```java
Sinks.Many<SnapshotDecisionDTO> snapshotSink = Sinks.many().multicast().onBackpressureBuffer(64)
// On each save(): snapshotSink.tryEmitNext(event)
// GET /stream → snapshotSink.asFlux() → ServerSentEvent with event:"snapshot"
// UI: new EventSource('/api/v1/history/stream') → es.addEventListener('snapshot', handler)
```
