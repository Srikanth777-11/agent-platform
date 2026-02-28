# Agent Platform — Hybrid Trading Intelligence System

> Multi-agent AI trading intelligence platform. Not financial advice.

---

## What This Is

A production-grade, reactive microservices platform that combines quantitative technical analysis agents with Claude AI reasoning to generate structured trading signals for NIFTY50 / Indian equity markets.

The system runs a continuous pipeline: market data → 4 specialist agents → AI synthesis → hard discipline gates → final signal → outcome tracking → agent learning.

---

## Architecture Overview

```
Angel One API / Yahoo Finance (replay)
         ↓
 market-data-service (8080)
    OHLCV + regime-aware cache
         ↓
 scheduler-service (8082)  ←──── regime-adaptive tempo (30s VOLATILE → 10min CALM)
         ↓
 agent-orchestrator (8081)
    ├── analysis-engine (8083)  → 4 agents (parallel reactive)
    │     ├── TrendAgent        SMA/EMA/MACD + 5-vote directional bias
    │     ├── RiskAgent         RSI / drawdown / volatility
    │     ├── PortfolioAgent    SMA crossovers / momentum / allocation
    │     └── DisciplineCoach   Claude Haiku — emotional/FOMO control
    │
    ├── AIStrategistService     Claude Sonnet/Haiku — primary decision authority
    ├── Phase-35 Hard Gates     SessionGate / BiasGate / DivergencePenalty / MultiFilter
    ├── Phase-36 EligibilityGuard  regime+bias hard gate
    └── FinalDecision (v9)      24 fields incl. tradeDirection + directionalBias
         ↓                ↓
history-service (8085)   notification-service (8084)
  PostgreSQL R2DBC         Slack webhook
  P&L outcome tracking
  Agent learning loop
  WinConditionRegistry (38a)
         ↓
trade-service (8086)
  Operator intelligence (read-only)
         ↓
    ui (5173)
  React 18 + Vite + Tailwind
  Live SSE signal cards
```

---

## Service Port Map

| Service               | Port | Role |
|-----------------------|------|------|
| market-data-service   | 8080 | OHLCV data, regime cache, historical replay |
| agent-orchestrator    | 8081 | Main pipeline — agents → AI → gates → FinalDecision |
| scheduler-service     | 8082 | Adaptive tempo scheduler per symbol |
| analysis-engine       | 8083 | 4 agent parallel dispatch |
| notification-service  | 8084 | Slack event dispatch |
| history-service       | 8085 | Persistence, P&L outcomes, agent learning |
| trade-service         | 8086 | Operator intelligence layer (read-only) |
| ui (Vite dev)         | 5173 | React dashboard |
| postgres              | 5432 | Single PostgreSQL instance (agent-postgres) |

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3 (WebFlux + R2DBC — fully reactive) |
| Database | PostgreSQL via R2DBC |
| AI | Anthropic Claude API (Sonnet 4.6 + Haiku 4.5) |
| UI | React 18 + Vite + TailwindCSS + Framer Motion |
| Infra | Docker Compose (single stack: `docker-compose.dev.yml`) |
| Market Data | Angel One API (live) / Yahoo Finance via Python (replay) |

---

## Quick Start

### Prerequisites

- Docker & Docker Compose
- Anthropic API key
- Angel One API credentials (for live mode; Yahoo Finance script works for replay)

### 1. Configure environment

```bash
cp .env.example .env
# Edit .env:
# ANTHROPIC_API_KEY=your_key_here
# ANGELONE_API_KEY=...
# ANGELONE_CLIENT_ID=...
# ANGELONE_PASSWORD=...
# ANGELONE_TOTP_SECRET=...   (same base32 secret as your TOTP authenticator)
# SLACK_ENABLED=false
# SLACK_WEBHOOK_URL=https://hooks.slack.com/services/...
```

### 2. Start all services

```bash
docker compose -f docker-compose.dev.yml up -d --build
```

### 3. Verify health

```bash
curl http://localhost:8080/api/v1/market-data/health
curl http://localhost:8081/api/v1/orchestrate/health
curl http://localhost:8083/api/v1/analyze/health
curl http://localhost:8085/api/v1/history/health
```

### 4. Open the UI

```
http://localhost:5173
```

### 5. Manually trigger a pipeline cycle

```bash
curl -X POST http://localhost:8081/api/v1/orchestrate/trigger \
  -H "Content-Type: application/json" \
  -d '{"symbol":"NIFTY50","triggeredAt":"2026-02-28T10:00:00Z","traceId":"manual-001"}'
```

---

## Historical Replay

Run the full 59-day NIFTY50 replay to validate gate logic and measure win rates:

```bash
# 1. Clear previous replay data
docker exec agent-postgres psql -U agent -d agent_platform \
  -c "TRUNCATE decision_history, agent_performance_snapshot, decision_metrics_projection, trade_sessions RESTART IDENTITY;"

# 2. Reset replay cursor
curl -X POST "http://localhost:8080/api/v1/market-data/replay/reset?symbol=NIFTY50"

# 3. Load Yahoo Finance candles (2,979 x 5-min candles, no Angel One needed)
python3 scripts/load_nifty_candles.py

# 4. Start market-data-service in replay mode
SPRING_PROFILES_ACTIVE=historical-replay \
  docker compose -f docker-compose.dev.yml up -d market-data-service

# 5. Start replay
curl -X POST "http://localhost:8080/api/v1/market-data/replay/start?symbol=NIFTY50"

# 6. Monitor progress (~25 minutes for 2,979 candles)
curl "http://localhost:8080/api/v1/market-data/replay/status?symbol=NIFTY50"
```

**Replay mode** skips AI Strategist and DisciplineCoach (consensus-only, fast). All replay rows stamped `decision_mode=REPLAY_CONSENSUS_ONLY` — excluded from agent learning feedback loop.

---

## Decision Signal Logic

### Trading Sessions (IST)
| Session | Window | Allowed Signals |
|---|---|---|
| OPENING_BURST | 09:15 – 10:00 | BUY / SELL (if all gates pass) |
| POWER_HOUR | 15:00 – 15:30 | BUY only (if all gates pass) |
| MIDDAY_CONSOLIDATION | 10:00 – 15:00 | WATCH / HOLD only |
| OFF_HOURS | Outside market hours | HOLD only |

### Hard Gates (Phase 35 + 36)
Every BUY signal must pass ALL of:
- Session ∈ {OPENING_BURST, POWER_HOUR}
- Regime ∈ {VOLATILE, TRENDING}
- DirectionalBias ∈ {BULLISH, STRONG_BULLISH}
- Confidence ≥ 0.65
- DivergenceFlag = false

Every SELL signal must pass ALL of:
- Session = OPENING_BURST
- Regime = VOLATILE
- DirectionalBias ∈ {BEARISH, STRONG_BEARISH}
- Confidence ≥ 0.65
- DivergenceFlag = false

Anything failing → forced to WATCH.

### Trade Direction
| Signal | Direction | Instrument |
|---|---|---|
| BUY | LONG | Buy Nifty futures or CE options |
| SELL | SHORT | Sell Nifty futures or buy PE options |
| WATCH/HOLD | FLAT | No new position |

---

## Agents

### TrendAgent
- SMA20/SMA50 crossovers, EMA12, MACD histogram
- **5-vote directional bias**: trend + MACD + price>SMA20 + price>EMA12 + 5-candle momentum
- Output: `DirectionalBias` (STRONG_BULLISH → STRONG_BEARISH)

### RiskAgent
- RSI(14): overbought >70, oversold <30
- 20-period drawdown from high, standard deviation
- Contrarian/safety signal

### PortfolioAgent
- SMA crossovers (10/20), SMA50 relationship
- Short-term 5-period momentum, volatility coefficient

### DisciplineCoach (Claude Haiku)
- Emotional/FOMO discipline check
- Falls back to rule-based vote if API unavailable
- Skipped in historical replay for speed

### AIStrategistService (Claude Sonnet/Haiku)
- Primary decision authority — synthesises all agent signals + directional bias + strategy memory
- VOLATILE regime → Haiku (fast/cheap), others → Sonnet (deeper reasoning)
- Skipped in historical replay (consensus takes over)

---

## Learning Loop

After every resolved trade (next-candle P&L):
1. P&L computed: `(exitPrice - entryPrice) / entryPrice × 100%`
2. Each agent re-scored: did their signal direction match the actual profitable outcome?
3. `agent_performance_snapshot` updated with rolling win rates
4. Adaptive weights updated in real time for next decision cycle
5. **Phase 38a**: `edge_conditions` table updated with (session, regime, bias, signal) outcome — passive statistical learning for Phase 38b gate

---

## Phase Roadmap

| Phase | Description | Status |
|---|---|---|
| 1–12 | Foundation: agents, consensus, AI, DB, UI | ✅ Complete |
| 13–25 | Intelligence: momentum, risk engine, scalping, P&L loop | ✅ Complete |
| 26–32 | Observability: edge reports, feedback loop, historical replay | ✅ Complete |
| 33 | Directional Bias Layer — bidirectional scalping (LONG/SHORT) | ✅ Complete |
| 34 | Infra consolidation — single stack, prod ports, replay isolation | ✅ Complete |
| 35 | Authority Chain + Hard Gates (session, bias, divergence, multi-filter) | ✅ Complete |
| 36 | TradeEligibilityGuard — regime+bias hard eligibility gate | ✅ Complete |
| 37 | AI prompt SELL bias fix — symmetric BUY/SELL instruction | ✅ Complete |
| 38a | WinConditionRegistry PASSIVE — edge_conditions data collection | ✅ Complete |
| 38b | WinConditionRegistry ACTIVE — statistical gate (≥20 samples, ≥52% WR) | ⏳ Blocked (needs 6-month Angel One data) |

---

## Key Files

| File | Purpose |
|---|---|
| `docker-compose.dev.yml` | Single active compose file — all services, prod ports |
| `scripts/load_nifty_candles.py` | Load Yahoo Finance NIFTY50 candles for replay |
| `docs/next-session-prompt.md` | Context prompt for AI-assisted development sessions |
| `docs/baseline-snapshot-phase*.md` | Frozen replay metrics per phase |
| `docs/live-mode-checklist-and-replay-interests.md` | Live mode safety + per-phase measurement targets |
| `CODEBASE-REFERENCE.md` | Full technical reference: files, endpoints, schema, patterns |
| `EVOLUTION-PATHS.md` | Architectural evolution history phase by phase |

---

## Observability

- `X-Trace-Id` propagated through all service calls and MDC
- `DecisionFlowLogger` logs 7 pipeline stages per cycle
- `GET /api/v1/history/feedback-loop-status` — live agent win rates vs fallback
- `GET /api/v1/history/analytics/edge-report?symbol=` — full edge validation report
- `GET /api/v1/history/stream` — SSE live signal stream (UI subscribes)
- All services expose `/actuator/health`

---

## Adding a New Agent

1. Create class in `analysis-engine/.../agent/` implementing `AnalysisAgent`
2. Annotate `@Component`
3. Spring auto-discovers and includes it in `AgentDispatchService`

```java
@Component
public class MyAgent implements AnalysisAgent {
    @Override public String agentName() { return "MyAgent"; }
    @Override
    public AnalysisResult analyze(Context context) {
        return AnalysisResult.of(agentName(), "summary", "HOLD", 0.5, Map.of());
    }
}
```

To skip in replay: add `"MyAgent"` to the filter list in `AgentDispatchService.dispatchAll()`.
