CREATE TABLE IF NOT EXISTS decision_history (
    id               BIGSERIAL    PRIMARY KEY,
    symbol           VARCHAR(20)  NOT NULL,
    timestamp        TIMESTAMP    NOT NULL,
    agents           TEXT,
    final_signal     VARCHAR(10)  NOT NULL,
    confidence_score DOUBLE PRECISION NOT NULL,
    metadata         TEXT,
    trace_id         VARCHAR(100),
    saved_at         TIMESTAMP    NOT NULL,
    -- v2 observability fields (additive — nullable for backward compatibility)
    decision_version     VARCHAR(20),
    orchestrator_version VARCHAR(20),
    agent_count          INT,
    decision_latency_ms  BIGINT,
    -- v3 consensus fields (additive — nullable for backward compatibility)
    consensus_score        DOUBLE PRECISION,
    agent_weight_snapshot  TEXT,
    -- v4 adaptive performance fields (additive — nullable for backward compatibility)
    adaptive_agent_weights TEXT,
    -- v5 market regime field (additive — nullable for backward compatibility)
    market_regime          VARCHAR(20),
    -- v6 AI strategist reasoning (additive — nullable for backward compatibility)
    ai_reasoning           TEXT,
    -- v7 divergence awareness (additive — nullable for backward compatibility)
    divergence_flag        BOOLEAN,
    -- v8 scalping intelligence (additive — nullable for backward compatibility)
    trading_session        VARCHAR(30),
    entry_price            DOUBLE PRECISION,
    target_price           DOUBLE PRECISION,
    stop_loss              DOUBLE PRECISION,
    estimated_hold_minutes INT,
    -- v8 P&L outcome fields (populated ~5-10 min after decision by outcome tracker)
    outcome_percent        DOUBLE PRECISION,
    outcome_hold_minutes   INT,
    outcome_resolved       BOOLEAN,
    -- v9 directional bias fields (additive — nullable for backward compatibility)
    trade_direction        VARCHAR(10),
    directional_bias       VARCHAR(20)
);

-- v8 projection tables & index optimization

-- Composite index for snapshot + regime queries
CREATE INDEX IF NOT EXISTS idx_decision_history_symbol_savedat
    ON decision_history(symbol, saved_at DESC);

-- Pre-aggregated agent performance (replaces findAll() scans)
CREATE TABLE IF NOT EXISTS agent_performance_snapshot (
    agent_name                VARCHAR(50)      PRIMARY KEY,
    historical_accuracy_score DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    latency_weight            DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    win_rate                  DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    avg_confidence            DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    avg_latency_ms            DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    total_decisions           BIGINT           NOT NULL DEFAULT 0,
    sum_confidence            DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    sum_latency_ms            DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    sum_wins                  DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    regime_bias               VARCHAR(20),
    last_updated              TIMESTAMP        NOT NULL DEFAULT NOW()
);

-- Per-symbol trend metrics projection
CREATE TABLE IF NOT EXISTS decision_metrics_projection (
    symbol             VARCHAR(20)      PRIMARY KEY,
    last_confidence    DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    confidence_slope_5 DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    divergence_streak  INT              NOT NULL DEFAULT 0,
    momentum_streak    INT              NOT NULL DEFAULT 0,
    last_updated       TIMESTAMP        NOT NULL DEFAULT NOW()
);

-- Phase-32: Historical OHLCV candles for replay tuning
CREATE TABLE IF NOT EXISTS replay_candles (
    id          BIGSERIAL PRIMARY KEY,
    symbol      VARCHAR(20) NOT NULL,
    candle_time TIMESTAMP NOT NULL,
    open        DOUBLE PRECISION NOT NULL,
    high        DOUBLE PRECISION NOT NULL,
    low         DOUBLE PRECISION NOT NULL,
    close       DOUBLE PRECISION NOT NULL,
    volume      BIGINT NOT NULL,
    UNIQUE (symbol, candle_time)
);
CREATE INDEX IF NOT EXISTS idx_replay_candles_symbol_time ON replay_candles (symbol, candle_time ASC);

-- Operator trade awareness (no pipeline linkage)
CREATE TABLE IF NOT EXISTS trade_sessions (
    id               BIGSERIAL        PRIMARY KEY,
    symbol           VARCHAR(20),
    entry_time       TIMESTAMP,
    entry_price      DOUBLE PRECISION,
    entry_confidence DOUBLE PRECISION,
    entry_regime     VARCHAR(20),
    entry_momentum   VARCHAR(20),
    exit_time        TIMESTAMP,
    exit_price       DOUBLE PRECISION,
    pnl              DOUBLE PRECISION,
    duration_ms      BIGINT
);
