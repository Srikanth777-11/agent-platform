"""
NIFTY 5-min candle loader — Yahoo Finance → replay_candles table.

Standalone script. Does NOT touch any Java service code.
Run this while waiting for Angel One credentials.

Usage:
    pip install yfinance psycopg2-binary pandas
    python scripts/load_nifty_candles.py

Yahoo Finance limitation: max 60 days of 5-min data.
That gives ~4,500 candles — enough to validate the replay pipeline.

When Angel One credentials arrive, the fetch-history API replaces this
and loads the full 6-month dataset directly via the market-data-service.
"""

import sys
import yfinance as yf
import pandas as pd
import psycopg2
from datetime import datetime, timedelta, timezone

# ── Config ────────────────────────────────────────────────────────────────────

SYMBOL       = "NIFTY50"          # name stored in replay_candles
YF_TICKER    = "^NSEI"            # Yahoo Finance ticker for NIFTY 50
INTERVAL     = "5m"               # 5-minute candles
DAYS_BACK    = 59                 # Yahoo allows max 60 days for 5m data

DB_HOST      = "localhost"
DB_PORT      = 5432
DB_NAME      = "agent_platform"
DB_USER      = "agent"
DB_PASSWORD  = "agent_secret"

# ── Fetch from Yahoo Finance ───────────────────────────────────────────────────

def fetch_candles():
    end   = datetime.now(timezone.utc)
    start = end - timedelta(days=DAYS_BACK)

    print(f"[Yahoo] Fetching {YF_TICKER} {INTERVAL} candles from {start.date()} to {end.date()} ...")
    df = yf.download(
        YF_TICKER,
        start=start.strftime("%Y-%m-%d"),
        end=end.strftime("%Y-%m-%d"),
        interval=INTERVAL,
        progress=False,
        auto_adjust=True
    )

    if df.empty:
        print("[ERROR] Yahoo Finance returned no data. Check ticker or internet connection.")
        sys.exit(1)

    # Flatten MultiIndex columns if present
    if isinstance(df.columns, pd.MultiIndex):
        df.columns = df.columns.get_level_values(0)

    df = df.reset_index()

    # Normalize datetime column name (Yahoo returns 'Datetime' for intraday)
    dt_col = "Datetime" if "Datetime" in df.columns else "Date"
    df = df.rename(columns={
        dt_col:    "candle_time",
        "Open":    "open",
        "High":    "high",
        "Low":     "low",
        "Close":   "close",
        "Volume":  "volume"
    })

    # Keep only needed columns
    df = df[["candle_time", "open", "high", "low", "close", "volume"]].copy()

    # Drop rows with NaN prices (pre-market / after-hours gaps)
    df = df.dropna(subset=["open", "high", "low", "close"])

    # Ensure timezone-aware UTC timestamps
    if df["candle_time"].dt.tz is None:
        df["candle_time"] = df["candle_time"].dt.tz_localize("Asia/Kolkata").dt.tz_convert("UTC")
    else:
        df["candle_time"] = df["candle_time"].dt.tz_convert("UTC")

    # Remove timezone info for PostgreSQL TIMESTAMP column
    df["candle_time"] = df["candle_time"].dt.tz_localize(None)

    # Cast volume to int (Yahoo sometimes returns float)
    df["volume"] = df["volume"].fillna(0).astype(int)

    print(f"[Yahoo] Fetched {len(df)} candles. Range: {df['candle_time'].min()} → {df['candle_time'].max()}")
    return df

# ── Insert into replay_candles ─────────────────────────────────────────────────

def load_to_db(df: pd.DataFrame):
    print(f"[DB] Connecting to {DB_HOST}:{DB_PORT}/{DB_NAME} ...")
    try:
        conn = psycopg2.connect(
            host=DB_HOST, port=DB_PORT,
            dbname=DB_NAME,
            user=DB_USER, password=DB_PASSWORD
        )
    except Exception as e:
        print(f"[ERROR] DB connection failed: {e}")
        print("       Is the dev stack running? docker compose -f docker-compose.dev.yml up -d")
        sys.exit(1)

    cur = conn.cursor()

    inserted = 0
    skipped  = 0
    BATCH    = 500

    rows = list(df.itertuples(index=False, name=None))

    for i in range(0, len(rows), BATCH):
        batch = rows[i : i + BATCH]
        for (candle_time, open_, high, low, close, volume) in batch:
            try:
                cur.execute("""
                    INSERT INTO replay_candles
                        (symbol, candle_time, open, high, low, close, volume)
                    VALUES (%s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT (symbol, candle_time) DO NOTHING
                """, (SYMBOL, candle_time, float(open_), float(high),
                      float(low), float(close), int(volume)))
                if cur.rowcount > 0:
                    inserted += 1
                else:
                    skipped += 1
            except Exception as e:
                print(f"[WARN] Skipped row {candle_time}: {e}")
                skipped += 1

        conn.commit()
        done = min(i + BATCH, len(rows))
        pct  = done / len(rows) * 100
        print(f"  Progress: {done}/{len(rows)} ({pct:.0f}%)  inserted={inserted}  skipped={skipped}")

    cur.close()
    conn.close()
    return inserted, skipped

# ── Verify ────────────────────────────────────────────────────────────────────

def verify():
    conn = psycopg2.connect(
        host=DB_HOST, port=DB_PORT,
        dbname=DB_NAME,
        user=DB_USER, password=DB_PASSWORD
    )
    cur = conn.cursor()
    cur.execute("""
        SELECT symbol, COUNT(*) AS candles,
               MIN(candle_time) AS earliest,
               MAX(candle_time) AS latest
        FROM replay_candles
        WHERE symbol = %s
        GROUP BY symbol
    """, (SYMBOL,))
    row = cur.fetchone()
    cur.close()
    conn.close()
    return row

# ── Main ──────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    print("=" * 60)
    print("  NIFTY Candle Loader — Yahoo Finance → replay_candles")
    print("=" * 60)

    df = fetch_candles()
    inserted, skipped = load_to_db(df)

    print()
    print("─" * 60)
    row = verify()
    if row:
        symbol, candles, earliest, latest = row
        print(f"  symbol   : {symbol}")
        print(f"  candles  : {candles}")
        print(f"  earliest : {earliest}")
        print(f"  latest   : {latest}")
    print("─" * 60)
    print(f"  inserted={inserted}  skipped(duplicates)={skipped}")
    print()
    print("  Ready to replay:")
    print("  curl -X POST http://localhost:9080/api/v1/market-data/replay/start")
    print("=" * 60)
