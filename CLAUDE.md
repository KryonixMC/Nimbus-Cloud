# Nimbus

Lightweight, console-only Minecraft cloud system. Manages dynamic server instances (lobbies, game servers) from a single JAR.

## Install & Run

One-command installers for end users (Java 21 auto-installed, latest release from GitHub):

```bash
# Controller (Linux/macOS)
curl -fsSL https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install.sh | bash

# Controller (Windows PowerShell)
irm https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install.ps1 | iex

# Agent node (Linux/macOS)
curl -fsSL https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install-agent.sh | bash

# Agent node (Windows PowerShell)
irm https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install-agent.ps1 | iex
```

```bash
# Remote CLI (Linux/macOS)
curl -fsSL https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install-cli.sh | bash

# Remote CLI (Windows PowerShell)
irm https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install-cli.ps1 | iex
```

Install scripts: `install.sh`, `install.ps1`, `install-agent.sh`, `install-agent.ps1`, `install-cli.sh`, `install-cli.ps1` in repo root.

## Build from Source

```bash
./gradlew shadowJar                    # Fat JAR → nimbus-core/build/libs/nimbus-core-<version>-all.jar
./gradlew :nimbus-core:compileKotlin   # Quick compile check
java -jar nimbus-core/build/libs/nimbus-core-<version>-all.jar
```

`shadowJar` also builds and embeds module JARs (perms, display, scaling, players) into `controller-modules/` inside the fat JAR.

Version is defined once in `gradle.properties` (`nimbusVersion=x.y.z`).

## Auto-Updates

`UpdateChecker` (`dev/nimbuspowered/nimbus/update/UpdateChecker.kt`) runs on startup:
- Queries `GET /repos/NimbusPowered/Nimbus/releases/latest` via GitHub API
- Compares semver (major.minor.patch) against `NimbusVersion.version` from JAR manifest
- Patch/minor: auto-downloads new JAR, swaps in place, keeps backup (`nimbus-backup.jar`)
- Major: shows changelog + `[y/N]` prompt via JLine before downloading
- Skipped when version = `dev` (source builds)
- Events: `NimbusUpdateAvailable`, `NimbusUpdateApplied` in `Events.kt`

## Release Workflow

`.github/workflows/release.yml` — manually triggered (`workflow_dispatch`):
- Input: version (optional, defaults to `gradle.properties`) + prerelease flag
- Builds `shadowJar`, uploads `nimbus-core-<version>.jar` + `nimbus-agent-<version>.jar`
- Creates a GitHub Release **draft** with auto-generated release notes

## Modules

- `nimbus-core` — Main application (entry point: `dev.nimbuspowered.nimbus.NimbusKt`)
- `nimbus-agent` — Remote agent node for multi-node clusters
- `nimbus-protocol` — Shared cluster message types
- `nimbus-bridge` — Velocity plugin: hub commands + cloud bridge (Java, auto-embedded as resource `nimbus-bridge.jar` during build)
- `nimbus-sdk` — Server SDK (Spigot 1.8.8+ / Paper / Folia compatible, auto-deployed to backend servers)
- `nimbus-perms` — Permissions plugin: builtin or LuckPerms provider (Spigot 1.8.8+ / Paper / Folia compatible, auto-deployed, configurable)
- `nimbus-display` — Display plugin: server selector signs + NPCs via FancyNpcs (Spigot 1.13+ signs, Paper 1.20+ NPCs, Folia compatible)
- `nimbus-cli` — Remote CLI: standalone JLine3 console that connects to controller via REST + WebSocket (no Minecraft dependencies)
- `nimbus-module-api` — Module API: interfaces for external module developers (NimbusModule, ModuleContext, ModuleCommand, Migration)
- `nimbus-module-perms` — Permissions module: groups, tracks, prefix/suffix, audit log (extracted from core)
- `nimbus-module-display` — Display module: server selector signs + NPCs config (extracted from core)
- `nimbus-module-scaling` — Smart Scaling module: time-based schedules, predictive warmup, player count history
- `nimbus-module-players` — Player module: centralized tracking, session history, cross-server management (controller module, auto-deployed)

## Tech Stack

- Kotlin 2.1.10, Java 21, Gradle + Shadow plugin
- ktoml for TOML config parsing
- JLine 3 for interactive console
- kotlinx-coroutines for async (scaling loops, event bus, process I/O)
- Ktor Client (CIO) for downloading server JARs
- Ktor Server (CIO) for REST API + WebSocket, Ktor Server (Netty) for cluster WebSocket (TLS)
- Exposed (JetBrains ORM) + SQLite/MySQL/PostgreSQL for database, versioned migrations via MigrationManager

## Architecture

```
nimbus-core/src/main/kotlin/dev/nimbuspowered/nimbus/
├── Nimbus.kt              # Entry point, bootstrap
├── api/                   # Ktor REST API + WebSocket (v0.2), ProxyEventRoutes (proxy event endpoint)
├── config/                # TOML config loading (NimbusConfig, GroupConfig)
├── console/               # JLine3 REPL, CommandDispatcher, 30 commands
├── database/              # Exposed ORM: DatabaseManager, MigrationManager, Tables, MetricsCollector, AuditCollector
├── event/                 # Coroutine-based EventBus + sealed Events
├── group/                 # ServerGroup runtime state, GroupManager
├── loadbalancer/          # TcpLoadBalancer, BackendHealthManager, strategies
├── module/                # ModuleManager, ModuleContextImpl (dynamic module loading)
├── scaling/               # ScalingEngine + ScalingRule (auto-scale by player count)
├── proxy/                 # ProxySyncManager (tab list, MOTD, chat, maintenance)
├── service/               # Service lifecycle, ProcessHandle, PortAllocator, ServerListPing, WarmPoolManager (warm pool)
├── setup/                 # First-run interactive SetupWizard
├── stress/                # StressTestManager (simulated player load testing)
├── template/              # TemplateManager, ConfigPatcher, SoftwareResolver (auto-download), ServiceDeployer (deploy-back)
├── cluster/               # RemoteFileProxy (remote file proxy for agent nodes)
├── update/                # UpdateChecker (GitHub Releases auto-updater)
└── velocity/              # VelocityConfigGen (auto-manage proxy server list)
# Note: permissions, display code now lives in their respective module JARs
```

## Configuration

- `config/nimbus.toml` — Main config (network, controller, console, paths, API, database, audit, cluster TLS)
- `config/groups/*.toml` — One file per server group (proxy, lobby, game servers)
- `data/nimbus.db` — SQLite database (default, configurable to MySQL/PostgreSQL)
- `config/modules/display/*.toml` — Display configs per group (signs + NPCs)
- `config/modules/scaling/*.toml` — Smart Scaling configs per group (schedules + warmup)
- `config/modules/players/` — Player module config (tracking, session history)
- `config/modules/syncproxy/motd.toml` — MOTD + maintenance mode config
- `config/modules/syncproxy/tablist.toml` — Tab list header, footer, player format
- `config/modules/syncproxy/chat.toml` — Chat format settings
- Environment variable overrides: `NIMBUS_API_TOKEN`, `NIMBUS_DB_*`, `NIMBUS_CLUSTER_TOKEN`, `NIMBUS_CLUSTER_KEYSTORE_PASSWORD` override TOML config values
- Config keys use `snake_case`, group/service names use `PascalCase` (validated: `[a-zA-Z0-9_-]` only)
- Scaling cooldowns: 30s after scale-up, 120s after scale-down (per group)
- Metrics retention: auto-pruned after 30 days
- Audit log retention: auto-pruned after 90 days (configurable via `[audit] retention_days`)
- MySQL connections use SSL by default (`useSSL=true`)
- `modules/` directory — Controller module JARs loaded at startup
- Module JARs embedded in core shadowJar under `controller-modules/` for SetupWizard extraction
- Group config fields: `scaling.warm_pool_size` (pre-staged services), `lifecycle.deploy_on_stop` (deploy-back on stop), `lifecycle.deploy_excludes` (files to skip during deploy-back), `group.templates` (list of template names, applied in order)

## Key Patterns

- Services named `<GroupName>-<N>` (e.g., `Lobby-1`, `BedWars-3`)
- Proxy ports: 25565+, backend ports: 30000+
- Velocity forwarding: `modern` if all backends >=1.13, else `legacy` (BungeeCord)
- Via plugins (ViaVersion/ViaBackwards) only on backend servers, never on proxy
- Via plugin dependencies enforced: ViaBackwards auto-includes ViaVersion, ViaRewind requires ViaBackwards
- EULA auto-accepted for Paper/Purpur/Pufferfish/Leaf/Folia templates
- Pufferfish support: downloads from Jenkins CI (`ci.pufferfish.host`), treated as Paper-based (plugins, Via, performance optimizer)
- Leaf support: downloads from Leaf API (`api.leafmc.one`), PaperMC-compatible API, treated as Paper-based (plugins, Via, performance optimizer)
- Cardboard (BETA): optional Bukkit/Paper plugin support for Fabric servers, auto-downloads with iCommon dependency from Modrinth
- Folia: SDK + NimbusPerms are Folia-compatible via SchedulerCompat
- Performance optimizer: Aikar's JVM flags + Paper/Purpur/Pufferfish/Leaf/Folia config tuning (optimize=true default)
- Process ready detection: watches stdout for "Done" pattern (120s timeout, 180s for modded)
- Phased startup order: proxies first (waits for READY) → then backends; ScalingEngine starts after initial boot
- Graceful shutdown order: game servers → lobbies → proxies
- Shutdown requires confirmation: `shutdown` then `shutdown confirm` within 30s
- NimbusPerms auto-deployed to backend servers via module-registered `PluginDeployment`
- Bedrock support: Geyser + Floodgate auto-downloaded from GeyserMC API, key.pem centrally managed
- Permission system: groups, inheritance, tracks, meta, weight, audit log, debug — central DB on controller
- LuckPerms support: optional provider in NimbusPerms, syncs display data to controller for proxy features
- Database migrations: `MigrationManager` auto-applies versioned schema changes on startup; core uses V1 (baseline) + V2 (audit); modules register migrations via `ModuleContext.registerMigrations()`
- Audit logging: `AuditCollector` subscribes to EventBus, batch-writes to `audit_log` table; `audit` console command + `GET /api/audit` endpoint
- CLI session tracking: `CliSessionTracker` records Remote CLI connections in `cli_sessions` table; `sessions` console command (active/history); `CliSessionConnected`/`CliSessionDisconnected` events displayed in local console
- Event actor tracking: `NimbusEvent.actor` field identifies trigger source (`system`, `console`, `api:admin`, `api:service`)
- Cluster TLS: Netty engine with native `sslConnector`; auto-generates self-signed keystore at `config/cluster.jks` if none configured; agents connect via `wss://` with configurable trust (`tls_verify`, `truststore_path`)
- Modules loaded from `modules/*.jar` via ServiceLoader + URLClassLoader
- Module lifecycle: init() → enable() → disable()
- Modules register commands, routes, plugin deployments, event formatters, and migrations via ModuleContext
- Modules can access late-registered services (e.g. ServiceManager) via `ModuleContext.registerService()`
- Embedded modules auto-discovered via build-generated `controller-modules/modules.list`
- SetupWizard lets users choose which modules to install
- `plugins` command: live search on Hangar + Modrinth with multi-select, version-aware, auto-installs dependencies
- Template stacking: `templates = ["base", "overlay"]` in group config, applied in order (later overrides earlier)
- Warm pool: `warm_pool_size` configures pre-staged services per group, PREPARED state between PREPARING and STARTING
- Service deployments: `deploy_on_stop = true` copies changed files back to template on service stop
- Player tracking: Bridge reports player events via `POST /api/proxy/events`, Player Module subscribes via EventBus

## Cross-Version Compatibility

- Plugins (SDK, Perms, Display) support Spigot 1.8.8+ through latest Paper/Folia
- `dev.nimbuspowered.nimbus.sdk.compat` package provides cross-version abstractions:
  - `VersionHelper`: runtime detection of Folia, Adventure API, AsyncChatEvent
  - `SchedulerCompat`: Bukkit/Folia scheduler abstraction (delegates to `FoliaScheduler` on Folia)
  - `TextCompat`: Adventure/legacy text abstraction (delegates to `AdventureHelper` on Paper 1.16.5+)
- `api-version` removed from plugin.yml for universal loading
- Chat rendering: `ModernChatHandler` (Paper AsyncChatEvent) vs `LegacyChatHandler` (Bukkit AsyncPlayerChatEvent)
- Sign rendering: `TextCompat.setSignLine()` uses `sign.line(Component)` or `sign.setLine(String)` based on server
- Hologram text: `TextCompat.setCustomName()` for cross-version ArmorStand naming
- FancyNpcs features only available on Paper 1.20+ (soft dependency, graceful degradation)

## Code Style

- Kotlin, no frameworks (no Spring/DI). Direct object wiring in `Nimbus.kt`
- Coroutines for all async work (no raw threads)
- All console commands implement `execute(args, output: CommandOutput)` as the canonical path; `execute(args)` delegates via `ConsoleOutput()`
- Sealed classes for events (`Events.kt`) with generic `ModuleEvent` for module-fired events
- Enums for state (`ServiceState.kt`)
- ANSI-colored console output via `ConsoleFormatter`

## API (v0.2)

- Bearer token auth (`Authorization: Bearer <token>`), auto-generated if not configured
- REST: `/api/services`, `/api/services/health` (aggregated health summary), `/api/groups`, `/api/status`, `/api/players`, `/api/maintenance`, `/api/stress`, `/api/reload`, `/api/shutdown`, `/api/loadbalancer`, `/api/nodes`, `/api/metrics`, `/api/audit` (admin-only audit log), `/api/scaling/*` (smart scaling module), `/api/permissions/*` (perms module), `/api/displays/*` (display module), `/api/players/*` (player module), `/api/console/complete` (tab completion for Remote CLI)
- Proxy events: `POST /api/proxy/events` — generic proxy event reporting (player connect/disconnect/switch)
- Player module: `/api/players/online`, `/api/players/history/{uuid}`, `/api/players/info/{uuid}`, `/api/players/stats`
- WebSocket: `/api/events` (live events), `/api/services/{name}/console` (bidirectional), `/api/console/stream` (Remote CLI: multiplexed command execution, events, screen sessions) — auth via `Authorization` header or `?token=` query param
- `/api/health` is always public (no auth), all other endpoints (including `/api/metrics`) require auth
- Rate limiting: 120 requests/minute global, 5 requests/minute for stress endpoints
- Error responses include machine-readable `error` codes (e.g. `SERVICE_NOT_FOUND`, `VALIDATION_FAILED`) — see `ApiErrors.kt`
- API token passed to child processes via `NIMBUS_API_TOKEN` environment variable (not visible in `ps`)

## Stress Testing

Simulates player load across backend servers without real Minecraft clients.

### Console Commands
```
stress start <players> [group] [--ramp <seconds>]   # Start stress test
stress stop                                          # Stop and clean up
stress ramp <players> [--duration <seconds>]         # Adjust target mid-test
stress status                                        # Show live status
```

### In-Game (Bridge)
```
/cloud stress start <players> [group] [rampSeconds]
/cloud stress stop
/cloud stress ramp <players> [durationSeconds]
/cloud stress status
```

### Behavior
- Only backend groups receive simulated players (proxy groups are excluded)
- Each service is capped at its `max_players` config value
- ScalingEngine is **paused** during active stress tests (won't scale up/down based on simulated players)
- Proxy services auto-reflect total backend player count
- Simulated player counts are reflected in proxy MOTD and tab header/footer
- Template download to agent nodes includes global plugins (SDK)

### REST API
```
GET  /api/stress              # Status (active, players, capacity, per-service)
POST /api/stress/start        # Body: {"players": 100, "group": "Lobby", "rampSeconds": 30}
POST /api/stress/stop         # Stop active test
POST /api/stress/ramp         # Body: {"players": 200, "durationSeconds": 60}
```
