# Hybrid Trading Intelligence Agent Platform

> Engineering scaffold — backend platform only. No trading advice or signals.

---

## Architecture

```
Alpha Vantage API
       ↓
Scheduler Service  ──(cron every 5min)──►  Agent Orchestrator
                                                   ↓
                                         Market Data Service
                                        (fetches real OHLCV data)
                                                   ↓
                                          Analysis Engine
                                        ┌──────────────────┐
                                        │  PortfolioAgent  │  SMA crossovers, momentum
                                        │  TrendAgent      │  SMA/EMA/MACD
                                        │  RiskAgent       │  RSI, drawdown, volatility
                                        │  DisciplineCoach │  Claude AI discipline check
                                        └──────────────────┘
                                                   ↓
                                       Notification Service
                                          (Slack webhook)
```

---

## Service Port Map

| Service              | Port |
|----------------------|------|
| market-data-service  | 8080 |
| agent-orchestrator   | 8081 |
| scheduler-service    | 8082 |
| analysis-engine      | 8083 |
| notification-service | 8084 |

---

## Prerequisites

- Java 21
- Maven 3.9+
- Docker & Docker Compose
- Alpha Vantage API key (free at https://www.alphavantage.co/support/#api-key)
- Anthropic API key (optional, for Claude AI discipline coach)
- Slack incoming webhook URL (optional)

---

## Quick Start — Local Docker Compose

### 1. Configure environment

```bash
cp .env.example .env
# Edit .env and set your API keys
```

Required:
```
ALPHA_VANTAGE_API_KEY=your_key_here
```

Optional:
```
ANTHROPIC_API_KEY=your_key_here
SLACK_ENABLED=true
SLACK_WEBHOOK_URL=https://hooks.slack.com/services/...
WATCHED_SYMBOLS=IBM,AAPL,GOOGL
```

### 2. Generate Maven wrapper (first time only)

```bash
cd agent-platform
mvn wrapper:wrapper
```

### 3. Build and start all services

```bash
docker compose up --build
```

### 4. Verify services are healthy

```bash
curl http://localhost:8080/api/v1/market-data/health
curl http://localhost:8081/api/v1/orchestrate/health
curl http://localhost:8083/api/v1/analyze/health
curl http://localhost:8084/api/v1/notify/health
```

### 5. Manually trigger analysis (without waiting for cron)

```bash
curl -X POST http://localhost:8081/api/v1/orchestrate/trigger \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "IBM",
    "triggeredAt": "2025-01-01T10:00:00Z",
    "traceId": "manual-test-001"
  }'
```

---

## Building Locally (without Docker)

```bash
# Build all modules
mvn clean install -DskipTests

# Run individual service
cd agent-orchestrator
mvn spring-boot:run

# Or run the jar
java -jar target/agent-orchestrator-1.0.0-SNAPSHOT.jar \
  --MARKET_DATA_URL=http://localhost:8080 \
  --ANALYSIS_ENGINE_URL=http://localhost:8083 \
  --NOTIFICATION_URL=http://localhost:8084
```

---

## Kubernetes Deployment

```bash
# Create namespace
kubectl create namespace agent-platform

# Apply secrets (edit k8s/secrets.yaml first with real values)
kubectl apply -f k8s/secrets.yaml

# Apply config
kubectl apply -f k8s/configmap.yaml

# Deploy all services
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml

# Check status
kubectl get pods -n agent-platform
kubectl logs -f deployment/agent-orchestrator -n agent-platform
```

---

## Agent Logic Summary

### PortfolioAgent
- SMA crossovers (10/20), SMA50 price relationship
- Short-term momentum (5-period)
- Volatility coefficient
- Signal: `BUY / SELL / HOLD`

### TrendAgent
- SMA20, SMA50 trend direction
- EMA12 momentum confirmation
- MACD line signal alignment
- Signal: `BUY / SELL / HOLD`

### RiskAgent
- RSI(14): overbought >70, oversold <30
- 20-period drawdown from high
- Standard deviation / volatility
- Risk levels: `LOW / MEDIUM / HIGH`
- Signal: `BUY / SELL / HOLD / WATCH`

### DisciplineCoach (Claude AI)
- Sends context summary to Claude claude-haiku-4-5-20251001 model
- Asks for structured JSON: assessment + confidence + signal
- Falls back to rule-based check if API key absent or call fails
- Assessment: `PROCEED / CAUTION / PAUSE`

---

## Adding a New Agent

1. Create a class in `analysis-engine/.../agent/` implementing `AnalysisAgent`
2. Annotate with `@Component`
3. Spring auto-discovers and includes it in `AgentDispatchService`

```java
@Component
public class MyNewAgent implements AnalysisAgent {
    @Override public String agentName() { return "MyNewAgent"; }

    @Override
    public AnalysisResult analyze(Context context) {
        // your logic here
        return AnalysisResult.of(agentName(), "summary", "HOLD", 0.5, Map.of());
    }
}
```

---

## Observability

- All services expose `/actuator/health` and `/actuator/metrics`
- TraceId is propagated via `X-Trace-Id` HTTP header and MDC
- Structured log format: `timestamp [traceId] LEVEL logger - message`
- Plug Prometheus/Grafana into `/actuator/metrics` for production monitoring

---

## Roadmap (Future)

- [ ] Kafka event bus (replace sync REST calls)
- [ ] Redis caching for market data
- [ ] Spring Security / JWT auth on orchestrator
- [ ] Persistent storage for analysis history (PostgreSQL)
- [ ] Multi-symbol concurrent execution (parallel agent dispatch)
- [ ] CI/CD pipeline (GitHub Actions → Docker Hub → K8s)
