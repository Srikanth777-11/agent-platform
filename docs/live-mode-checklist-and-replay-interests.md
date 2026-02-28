# Live Mode Checklist & Replay Measurement Interests

---

## PART 1 — What We Disabled for Replay (Must Be Active in Live)

### What the replayMode flag controls (already safe via code)
All replay skips are gated behind `replayMode=true` which is only set when
`X-Replay-Mode: true` header is present. Live triggers never send this header.
So these are already safe — just confirming they exist and why they matter:

| What is skipped in replay | Why it matters in live | Where the flag lives |
|---|---|---|
| **DisciplineCoach** (Claude Haiku) | Emotional/discipline control — catches overtrading, FOMO entries. Critical safety net in live. | `AgentDispatchService.dispatchAll(replayMode)` |
| **AIStrategistService** (Claude Sonnet/Haiku) | Primary decision authority — final signal comes from Claude's full market reasoning. In replay, consensus takes over as proxy. | `OrchestratorService.orchestrate(replayMode)` |
| **fetchStrategyMemory** | AI needs prior decisions as context for each cycle. Skipped in replay for speed. | `OrchestratorService.orchestrate(replayMode)` |
| **divergenceStreak computation** | Streak detection catches AI-vs-consensus drift over time. Set to 0 in replay. | `OrchestratorService.orchestrate(replayMode)` |

### What is NOT skipped in replay (runs in both modes)
- TrendAgent, RiskAgent, PortfolioAgent — all run normally
- Session classification — runs normally
- Regime classification — runs normally
- P&L resolution — runs normally
- history-service persistence — runs normally (stamped with decision_mode)
- Agent performance weight computation — runs normally (but DisciplineCoach excluded from replay agent weights — correct, it will learn from live)

### Live mode restore verification checklist
Before going live, confirm:
- [ ] No `X-Replay-Mode` header in live scheduler trigger path
- [ ] `AgentDispatchService` dispatches all 4 agents (including DisciplineCoach)
- [ ] `OrchestratorService` calls `aiStrategistService.evaluate()` (not bypassed)
- [ ] `OrchestratorService` calls `fetchStrategyMemory()`
- [ ] `decision_mode` stamped as `LIVE_AI` on all live decisions
- [ ] Scheduler fires normally (no replay running to block it)
- [ ] DisciplineCoach agent weight in `agent_performance_snapshot` — starts from live data

### Important note on agent weights in live
Replay produces agent_performance_snapshot for TrendAgent, RiskAgent, PortfolioAgent
only (DisciplineCoach excluded). When live starts:
- The 3-agent weights carry over as a warm start
- DisciplineCoach starts at equal weight (no prior performance data)
- After ~20 live decisions, DisciplineCoach weight will be computed from real outcomes
- This is correct behaviour — do not manually seed DisciplineCoach weight

---

## PART 2 — Replay Measurement Interests (Per Phase)

After each phase implementation, run the 59-day Yahoo replay and measure these
specific metrics. Freeze them before moving to the next phase.

### Phase 34 Replay — Infrastructure Baseline
**Purpose:** Confirm infra is stable. No strategy changes yet.

Interests:
- [ ] Replay duration (target: < 30 min with DisciplineCoach skip)
- [ ] decision_mode = REPLAY_CONSENSUS_ONLY on all replay rows
- [ ] Scheduler log shows "Replay active — skipping live trigger"
- [ ] Agent performance snapshot updates (4 rows, 3 agents with data)
- [ ] Win rate (expect ~47–52% — no strategy change)
- [ ] MIDDAY trade count (expect still high — not fixed until Phase 35)
- [ ] Signal distribution: BUY %, SELL %, WATCH %
- [ ] Total trade count

---

### Phase 35 Replay — Authority Chain + Hard Gates
**Purpose:** Verify structural decision discipline is enforced.

Interests:
- [ ] **MIDDAY trade count = 0** (critical — session gate must block all MIDDAY entries)
- [ ] **OFF_HOURS trade count = 0**
- [ ] Total trade count reduction vs Phase 34 baseline
- [ ] SELL signal % increases toward 25%+ (bias gate allows SELL in BEARISH)
- [ ] Win rate target: 52–55%
- [ ] Divergence % decreases (penalty applied correctly)
- [ ] No BUY signals when directionalBias = STRONG_BEARISH
- [ ] No SELL signals when directionalBias = STRONG_BULLISH
- [ ] Confidence distribution — confirm low-confidence trades filtered out
- [ ] Divergence streak ≥ 2 → WATCH forced (check in logs)

---

### Phase 36 Replay — TradeEligibilityGuard
**Purpose:** Verify hard eligibility gate reduces overtrading.

Interests:
- [ ] **Total trade count: 150–200** (down from 1,000+ baseline)
- [ ] All BUY trades: session ∈ {OPENING_BURST, POWER_HOUR}, regime ∈ {VOLATILE, TRENDING}, bias ∈ {BULLISH, STRONG_BULLISH}
- [ ] All SELL trades: session = OPENING_BURST, regime = VOLATILE, bias ∈ {BEARISH, STRONG_BEARISH}
- [ ] Win rate target: 55–58%
- [ ] Profit factor > 1.0 (first time system is net-positive)
- [ ] Max drawdown reduces vs Phase 35
- [ ] No MIDDAY/OFF_HOURS trades at all
- [ ] WATCH % increases (most candles correctly filtered)

---

### Phase 37 Replay — AI Prompt Adjustment
**Purpose:** Correct implicit BUY bias in AI prompt.

Interests:
- [ ] SELL signal % in BEARISH sessions increases
- [ ] BUY signals no longer appear in STRONG_BEARISH conditions
- [ ] Signal distribution more balanced (target: SELL ≥ 30% of active trades)
- [ ] Win rate stable or improving vs Phase 36
- [ ] Prompt no longer contains session gating logic (single source of truth = EligibilityGuard)
- [ ] AI reasoning log — check for SELL reasoning quality

---

### Phase 38a Replay — WinConditionRegistry (Passive)
**Purpose:** Confirm registry is populating with correct data.

Interests:
- [ ] edge_conditions table populating after each resolved trade
- [ ] REPLAY_CONSENSUS_ONLY rows excluded from registry (decision_mode filter works)
- [ ] Sample counts growing per (session, regime, bias, signal) combination
- [ ] Win rates per condition emerging (some conditions may show 0 trades yet — OK)
- [ ] No decision blocking (passive mode — trades flow through normally)
- [ ] After 6-month Angel One data: which conditions have ≥ 20 samples?

---

### Phase 38b Replay — WinConditionRegistry (Active Gate)
**Purpose:** Only statistically proven edges allowed.

Interests:
- [ ] Win rate ≥ 58%
- [ ] Profit factor > 1.2
- [ ] Conditions with < 20 samples → WATCH (confirm in logs)
- [ ] Conditions with win_rate < 0.52 → WATCH (confirm in logs)
- [ ] Trade count further reduced (only proven edges)
- [ ] Drawdown clusters controlled
- [ ] OPENING_BURST + VOLATILE + BULLISH + BUY — should be highest sample count

---

## PART 3 — Permanent Rules for All Replays

1. Always clear decision_history before each phase replay (fresh baseline per phase)
2. Always reload candles fresh via Python script before each replay
3. Never mix infrastructure changes + strategy changes in the same replay cycle
4. Freeze metrics snapshot in docs/baseline-snapshot-phaseXX.md before next phase
5. Compare every replay against the previous phase's frozen baseline
6. DisciplineCoach skipped in ALL replays — this is correct and intentional
7. AI Strategist skipped in ALL replays — consensus-only for speed
8. After Phase 38b active gate is live: paper trade for minimum 2 weeks before live capital
