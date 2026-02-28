# Next Session Prompt — Agent Platform

## AGENT PLATFORM — SESSION CONTEXT

### DO NOT search codebase. Read only these 4 files first:
1. MEMORY.md — ports, infra, key facts, roadmap
2. docs/baseline-snapshot-phase36.md — last quantitative replay baseline
3. docs/live-mode-checklist-and-replay-interests.md — Phase 38b measurement targets
4. CODEBASE-REFERENCE.md — exact file paths, schema, domain objects

---

### Current State
- **Phase 38a COMPLETE** — WinConditionRegistry live in history-service
- `edge_conditions` table created, populates from LIVE_AI resolved trades only
- REPLAY_CONSENSUS_ONLY rows excluded from registry (decision_mode filter in WinConditionRegistryService)
- Single stack: docker-compose.dev.yml, prod ports 8080-8086, postgres 5432
- Roadmap: 34✅ → 35✅ → 36✅ → 37✅ → 38a✅ → **38b NEXT (BLOCKED — needs Angel One data)**

---

### Phase 38b — BLOCKED until Angel One data available
Phase 38b activates the WinConditionRegistry as a gate: only trade conditions with ≥20 samples and win_rate ≥ 52%.

**Prerequisites before starting 38b:**
1. Angel One API credentials configured
2. 6-month historical data loaded into the system
3. Check `edge_conditions` table: `SELECT * FROM edge_conditions ORDER BY total_count DESC;`
4. At least a few conditions must have total_count ≥ 20 before enabling the gate

**When Angel One is ready:**
- Configure credentials in application.properties / environment
- Load 6-month NIFTY50 data (replace Yahoo Finance script with Angel One API)
- Run replay on 6-month dataset
- Check edge_conditions for conditions with ≥20 samples
- Only then implement Phase 38b gate in OrchestratorService.buildDecision()

---

### Phase 38b Gate Logic (when ready)
**Placement:** in `OrchestratorService.buildDecision()`, after Phase-36 EligibilityGuard, before FinalDecision.

**Rules:**
- If condition (session + regime + bias + signal) has total_count < 20 → WATCH (insufficient data)
- If condition has win_rate < 0.52 → WATCH (no proven edge)
- Otherwise → allow trade through

**Registry lookup:** Call history-service API to check edge_conditions before allowing BUY/SELL.

**Key files when ready:**
- Gate location: `agent-orchestrator/.../service/OrchestratorService.java` buildDecision()
- Registry endpoint: add `GET /api/v1/history/edge-conditions/{session}/{regime}/{bias}/{signal}` to history-service
- `history-service/.../repository/EdgeConditionRepository.java` — add findByKey() query

---

### Hard Rules
- AI overrides downward only (WATCH/HOLD), never upward
- No features outside roadmap, no phase skipping
- REPLAY_CONSENSUS_ONLY rows NEVER enter WinConditionRegistry
- Phase 38b gate only after ≥20 samples per condition — no exceptions
- Paper trade minimum 2 weeks after Phase 38b before live capital
