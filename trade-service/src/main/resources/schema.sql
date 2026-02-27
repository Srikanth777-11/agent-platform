-- trade-service schema — operator intelligence layer (no orchestrator linkage)

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

CREATE INDEX IF NOT EXISTS idx_trade_sessions_symbol
    ON trade_sessions(symbol, entry_time DESC);

CREATE TABLE IF NOT EXISTS risk_snapshots (
    id             BIGSERIAL        PRIMARY KEY,
    symbol         VARCHAR(20),
    position_size  DOUBLE PRECISION,
    unrealized_pnl DOUBLE PRECISION,
    drawdown_pct   DOUBLE PRECISION,
    exposure_pct   DOUBLE PRECISION,
    risk_level     VARCHAR(20),
    computed_at    TIMESTAMP        NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_risk_snapshots_symbol
    ON risk_snapshots(symbol, computed_at DESC);

CREATE TABLE IF NOT EXISTS portfolio_summary (
    id                   BIGSERIAL        PRIMARY KEY,
    open_positions       INT              NOT NULL DEFAULT 0,
    total_exposure       DOUBLE PRECISION,
    total_unrealized_pnl DOUBLE PRECISION,
    total_realized_pnl   DOUBLE PRECISION,
    max_drawdown_pct     DOUBLE PRECISION,
    overall_risk_level   VARCHAR(20),
    computed_at          TIMESTAMP        NOT NULL DEFAULT NOW()
);
