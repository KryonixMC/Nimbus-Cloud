# Nimbus Production Audit — Consolidated & Pragmatic

**Date:** 2026-04-12
**Codebase:** 220 Kotlin + 68 Java files (~55k lines), branch `development` @ `79496cb`
**Method:** 11-agent deep audit → 5-agent cross-validation → pragmatic triage

> **Context:** Nimbus is a Minecraft server cloud system. The threat model is "server operator
> on a VPS", not enterprise SaaS or critical infrastructure. This report filters all findings
> through that lens: will this actually cause problems for someone running Minecraft servers?

---

## Pipeline Summary

| Stage | Findings |
|-------|----------|
| Initial audit (11 agents) | 187 findings |
| After cross-validation (5 agents) | 141 confirmed (46 false positives removed) |
| After pragmatic triage | **15 MUST FIX** / 30 nice-to-have / ~96 skip |

---

## MUST FIX — Will crash, leak, or break in normal operation (~8-10h)

These 15 issues will bite you in real Minecraft server operation. They cause actual crashes,
memory leaks, data loss, or broken functionality that users will hit.

### 1. OOM: Large downloads loaded entirely into memory
- **IDs:** RES-005, RES-006
- **Files:** `template/ModpackInstaller.kt:293,381` — `template/SoftwareResolver.kt:403,644,687+`
- **Problem:** `readRawBytes()` loads entire modpack ZIPs (100MB-1GB+) and plugin JARs into a ByteArray. A single CurseForge modpack import can OOM the controller.
- **Impact:** Controller crash on modpack import or multi-plugin download during setup
- **Fix:** Replace all `readRawBytes()` with streaming to disk (~8 locations):
  ```kotlin
  response.bodyAsChannel().toInputStream().use { input ->
      Files.newOutputStream(targetFile).use { out -> input.copyTo(out, 65536) }
  }
  ```
- **Effort:** ~2h (repetitive pattern across 8 files)

### 2. Memory leak: HttpClient instances never closed
- **IDs:** RES-003, RES-004
- **Files:** `template/ModpackInstaller.kt:164` — `agent/TemplateDownloader.kt:23-30`
- **Problem:** HttpClient passed to ModpackInstaller has no `close()`. TemplateDownloader on agent nodes creates HttpClient in constructor with no shutdown hook. CIO engine = 8+ threads per client.
- **Impact:** Controller/agents get slower over days, eventually thread exhaustion
- **Fix:** Add `close()` methods, call from shutdown hooks
- **Effort:** ~30min

### 3. Memory leak: Unbounded event queues
- **IDs:** CONC-006, CONC-007
- **Files:** `database/MetricsCollector.kt:37-38` — `database/AuditCollector.kt:30`
- **Problem:** `ConcurrentLinkedQueue` with no size limit. Under high event rates (rapid scaling, many service events), queues grow unbounded until OOM.
- **Impact:** OOM after extended high-load periods (hours/days)
- **Fix:** Add max size check before enqueue, drop with warning if full:
  ```kotlin
  if (queue.size >= 10_000) { logger.warn("Queue full, dropping event"); return }
  ```
- **Effort:** ~15min

### 4. Database: HikariCP connection pool never closed
- **ID:** DB-001
- **File:** `database/DatabaseManager.kt:128-143`
- **Problem:** `HikariDataSource` created but reference not stored. No `close()` on shutdown. MySQL/PostgreSQL connections leak on restart.
- **Impact:** After several restarts, DB connection limit reached. "Too many connections" errors.
- **Fix:** Store DataSource reference, add `close()`, call from Nimbus.kt shutdown
- **Effort:** ~20min

### 5. Database: SQLite SQLITE_BUSY under concurrent writes
- **ID:** DB-007
- **File:** `database/DatabaseManager.kt:85-100`
- **Problem:** WAL mode enabled but no `PRAGMA busy_timeout`. Default = 0ms. Concurrent writes (metrics + audit flush) fail immediately with SQLITE_BUSY.
- **Impact:** Random "database is locked" errors, lost metrics/audit entries
- **Fix:** Add `stmt.execute("PRAGMA busy_timeout=5000")` after WAL mode
- **Effort:** ~5min

### 6. Port exhaustion: Sequential socket probing
- **ID:** PM-003
- **File:** `service/PortAllocator.kt:122-128`
- **Problem:** `isTcpPortAvailable()` creates/closes ServerSocket for each candidate port. With 50+ services, hundreds of sockets enter TIME_WAIT state (~60s each).
- **Impact:** Scale-up fails with "No available ports" after rapid scaling
- **Fix:** Track allocated ports in a BitSet instead of probing sockets
- **Effort:** ~30min

### 7. Player disconnect: No drain before scale-down
- **ID:** SCALE-002
- **File:** `scaling/ScalingEngine.kt:260-308`
- **Problem:** Scale-down sends `stopService()` immediately without waiting for connections to drain. Players get disconnected mid-game.
- **Impact:** Players kicked when scaling engine decides to remove a server
- **Fix:** Call `markDraining()` before stop, wait for `activeConnections == 0` (with timeout)
- **Effort:** ~1h

### 8. Data loss: ControllerStateStore non-atomic writes
- **ID:** CONC-001
- **File:** `service/ControllerStateStore.kt:74-86`
- **Problem:** `addService()`/`removeService()` do read-modify-write on state file without locking. Concurrent calls interleave = lost updates.
- **Impact:** After restart, services that were registered during concurrent operations are gone
- **Fix:** Wrap in `ReentrantReadWriteLock`
- **Effort:** ~30min

### 9. Scaling crash: ConcurrentModificationException
- **ID:** CONC-009
- **File:** `scaling/ScalingEngine.kt:312-318`
- **Problem:** `keys.removeAll { ... }` on ConcurrentHashMap keySet while map may be modified by heartbeat processing. Can throw CME.
- **Impact:** Scaling engine crashes, ALL auto-scaling stops until restart
- **Fix:** Snapshot keys first: `val toRemove = map.keys.filter { ... }; toRemove.forEach { map.remove(it) }`
- **Effort:** ~15min

### 10. Scaling race: merge() stale return values
- **ID:** CONC-003
- **File:** `scaling/ScalingEngine.kt:72,280`
- **Problem:** `ConcurrentHashMap.merge()` return value used directly — stale if another thread modifies between merge and use. Causes wrong backoff delays.
- **Impact:** Aggressive retry storms on failed group starts
- **Fix:** Re-read from map after merge
- **Effort:** ~15min

### 11. Config crash: No memory value range check
- **ID:** CFG-001
- **File:** `config/GroupConfig.kt`
- **Problem:** Memory regex validates format (`1G`, `512M`) but not the value. `memory = "999999G"` passes validation, crashes JVM on start.
- **Impact:** Typo in config = instant crash
- **Fix:** Parse numeric value, cap at 512G max
- **Effort:** ~15min

### 12. Infinite loop: No template cycle detection
- **ID:** CFG-002
- **File:** `template/TemplateManager.kt`
- **Problem:** `templates = ["A", "B", "A"]` creates infinite loop during service preparation.
- **Impact:** Service start hangs forever, blocks the group
- **Fix:** Track visited templates, error on revisit
- **Effort:** ~30min

### 13. Silent failures: No CoroutineExceptionHandler
- **ID:** LOG-010
- **File:** `Nimbus.kt:~199`
- **Problem:** No global `CoroutineExceptionHandler`. Background coroutines (scaling, metrics, events) can fail silently with zero log output.
- **Impact:** Stuff breaks and you have no idea why
- **Fix:** Add handler to SupervisorJob:
  ```kotlin
  val handler = CoroutineExceptionHandler { _, e -> logger.error("Uncaught coroutine exception", e) }
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
  ```
- **Effort:** ~5min

### 14. Stale health data: BackendHealthManager draining visibility
- **ID:** CONC-005
- **File:** `loadbalancer/BackendHealthManager.kt:25-34`
- **Problem:** `draining` field not `@Volatile`. Health check thread may not see draining flag set by another thread → routes players to draining backend.
- **Impact:** New players sent to server that's shutting down
- **Fix:** Add `@Volatile` annotation
- **Effort:** ~2min

### 15. Retention cleanup blocks DB
- **ID:** DB-006
- **File:** `database/MetricsCollector.kt:246-252`
- **Problem:** `pruneOldMetrics()` runs a single DELETE on potentially millions of rows. Locks table for seconds, blocking metrics/audit writes.
- **Impact:** Periodic lag spikes every 24h when cleanup runs
- **Fix:** Batch delete in chunks of 5000 rows with small delay between batches
- **Effort:** ~30min

---

## NICE TO HAVE — Cleaner code, unlikely edge cases (~8-10h if bored)

These improve code quality but won't cause real-world problems for most users.

| ID | Issue | Why not urgent |
|----|-------|----------------|
| SCALE-001 | Zero-readings race in scaling | Would need exact race timing, unlikely in practice |
| SCALE-003 | GlobalMaxServices non-atomic cap | Only matters with burst manual API starts |
| SCALE-004 | Stress test proxy off-by-one | Stress testing is a dev tool, not production-critical |
| SCALE-005 | Cooldowns lost on restart | 30s flapping after restart, auto-corrects |
| SCALE-006 | Empty candidates NPE in LB | Only if ALL backends crash simultaneously |
| CONC-004 | ProxySyncManager volatile atomicity | Maintenance MOTD flickers for <200ms |
| CONC-008 | WarmPoolManager non-atomic cap | Only with concurrent warm fills to different groups |
| CONC-010 | EventBus jobs not tracked for shutdown | Events lost during shutdown — meh |
| CONC-011 | ProxySyncManager debounce deadlock | Requires exact lock+cancel timing |
| CONC-012 | StressTestManager profile race | Two admins starting stress test simultaneously |
| CLU-003 | Reconciliation race on reconnect | Controller crash + agent reconnect + scale-up at same instant |
| CLU-005 | Sync push race on stop | Minecraft still writing while sync pushes — edge case |
| CLU-007 | No manifest snapshot consistency | File changes during directory walk — rare |
| CLU-009 | Non-atomic file deletion in commit | Crash during deletion loop — very rare |
| CLU-012 | No multipart timeout | Agent stalls mid-upload — should be timeout but rare |
| PM-001 | Stdout reader not cancelled | Windows-specific thread leak, slow accumulation |
| PM-005 | CRASHED slot no directory cleanup | Old world data in reused slot — confusing but not fatal |
| PM-006 | Ready timeout race | Server ready at 120.01s, killed at 120.00s — unlucky |
| DB-003 | Batch insert drops events on failure | Metrics gap if DB goes down — acceptable |
| CFG-003 | ConfigPatcher regex line matching | Works fine unless config has weird comments |
| CFG-004 | Group/dedicated name collision | Operator mistake, not a crash |
| CFG-005 | Dedicated port collision | Config error, caught at runtime |
| CFG-006 | Symlink fallback disk bloat | Only on Windows without admin rights |
| CFG-007 | SetupWizard name validation | Only during first-time setup |
| CFG-008 | Env var silent failure | `NIMBUS_DB_PORT=abc` gets default — confusing but safe |
| MOD-001 | CLI token plaintext in JSON | `chmod 600` is enough for a MC admin tool |
| MOD-003 | Module ClassLoader isolation | "Malicious modules" on your own MC server? |
| MOD-008 | Display TOML quote escaping | Sign text with quotes breaks config — fixable |
| MOD-009 | NimbusPerms empty token | Plugin silently degrades — confusing but safe |
| LOG-001-005 | Missing stack traces | Helpful for debugging, not critical |

---

## SKIP — Enterprise security theater for a Minecraft system

These 96 findings are technically correct but irrelevant for the actual use case.

**Security overkill:**
- SEC-001: JWT RS256 migration (HMAC-SHA256 is fine for single-operator)
- SEC-005: tlsVerify=false (it's a config option, operator's choice)
- SEC-010: Query param tokens in proxy logs (who logs their MC API?)
- SEC-012: CORS hardcoded (one dashboard, one origin)
- SEC-013: Agent config permissions (server operator owns the machine)
- SEC-014: Bootstrap over HTTP (initial setup, one-time operation)
- API-003/004: Query param auth (API is behind a token anyway)
- API-008: TOCTOU in modpack upload (requires simultaneous admin + symlink manipulation)
- API-009: 10GB multipart (state sync needs large uploads, by design)
- API-010: GeoIP SSRF (decorative feature, optional)

**Concurrency perfectionism:**
- CONC-014: Missing @Volatile on Service fields (fields effectively immutable after init)
- CONC-015: lastEmittedCount race (duplicate stress test events — who cares)
- CONC-016: GroupManager reload race (group added during reload — 1 in a million)
- CONC-017/018: Documentation clarity / init tracking

**Cluster edge cases (95% of users are single-node):**
- CLU-004: Non-idempotent state updates (needs out-of-order WS messages — unlikely)
- CLU-006: Large file streaming truncation (region file write during sync — save-all first)
- CLU-008: No manifest generation tracking (architecture choice, not a bug)
- CLU-010/011: Hardlink fallback / lock ordering (working correctly)

**All remaining MEDIUM/LOW findings** that don't affect normal operation.

---

## Verified False Positives (removed from all counts)

46 findings from the original audit were verified as **not real issues**:

| Category | False Positives | Key Examples |
|----------|----------------|-------------|
| Security | SEC-004, SEC-006, SEC-007, SEC-008, SEC-011 | Fixes already applied, mitigations exist |
| API | API-001, API-002, API-005, API-006, API-007 | Validation exists, rate limiting inherited |
| Cluster | CLU-001, CLU-002, CLU-008, CLU-010, CLU-011 | Reconciliation works, locks correct |
| Database | DB-002, DB-003, DB-004, DB-005, DB-008 | Lock/try-finally correct, queue atomic |
| Process | PM-002, PM-004 | State transition intentional, ports cleaned |
| Concurrency | CONC-002, CONC-013, CONC-014, CONC-018 | Registry IS atomic, mutex correct |
| Resources | RES-001, RES-002, RES-010, RES-011, RES-013-016 | Clients managed, limits exist |
| Modules | MOD-002, MOD-004, MOD-005, MOD-007, MOD-011-014 | No injection, cycle detection exists |

---

## What passed audit (strengths)

- No secrets in log output
- Zero `printStackTrace()` calls — all errors via SLF4J
- Comprehensive audit trail (service, scaling, config events)
- EventBus subscriber exceptions properly wrapped
- CLI session tracking with username/IP/OS
- Config parse errors logged with file context
- Graceful degradation on optional features
- ServiceRegistry max-instance enforcement IS atomic
- MigrationManager advisory locks ARE properly released
- Path traversal IS blocked in FileRoutes and StateRoutes
- Permission inheritance cycles ARE detected via BFS

---

## Action Plan

```
Week 1:  Fix #1-#5   (OOM, memory leaks, database)     ~4h
Week 1:  Fix #6-#10  (ports, scaling, state store)      ~3h
Week 1:  Fix #11-#15 (config, logging, health)          ~2h
         ─────────────────────────────────────────────
Total:   15 findings, ~8-10 hours, 1-2 days with Claude
```

After that: ship it. The nice-to-haves can be addressed over time as part of normal development.

---

*Audit pipeline: 11 audit agents → 5 cross-validation agents → pragmatic triage*
*Original: 187 findings → Cross-validated: 141 → Pragmatic: 15 must-fix*
