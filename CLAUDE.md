# Nimbus

Lightweight, console-only Minecraft cloud system. Manages dynamic server instances (lobbies, game servers) from a single JAR.

## Build & Run

```bash
./gradlew shadowJar                    # Fat JAR → nimbus-core/build/libs/nimbus-core-<version>-all.jar
./gradlew :nimbus-core:compileKotlin   # Quick compile check
java -jar nimbus-core/build/libs/nimbus-core-<version>-all.jar
```

Version is defined once in `gradle.properties` (`nimbusVersion=x.y.z`).

## Modules

- `nimbus-core` — Main application (entry point: `dev.nimbus.NimbusKt`)
- `nimbus-agent` — Remote agent node for multi-node clusters
- `nimbus-protocol` — Shared cluster message types
- `nimbus-bridge` — Velocity plugin: hub commands + cloud bridge (Java, auto-embedded as resource `nimbus-bridge.jar` during build)
- `nimbus-sdk` — Paper server SDK (auto-deployed to backend servers)
- `nimbus-signs` — Paper signs plugin for server selectors

## Tech Stack

- Kotlin 2.1.10, Java 21, Gradle + Shadow plugin
- ktoml for TOML config parsing
- JLine 3 for interactive console
- kotlinx-coroutines for async (scaling loops, event bus, process I/O)
- Ktor Client (CIO) for downloading server JARs
- Ktor Server (CIO) for REST API + WebSocket
- Exposed (JetBrains ORM) + SQLite/MySQL/PostgreSQL for database

## Architecture

```
nimbus-core/src/main/kotlin/dev/nimbus/
├── Nimbus.kt              # Entry point, bootstrap
├── api/                   # Ktor REST API + WebSocket (v0.2)
├── config/                # TOML config loading (NimbusConfig, GroupConfig)
├── console/               # JLine3 REPL, CommandDispatcher, 25 commands
├── database/              # Exposed ORM: DatabaseManager, Tables, MetricsCollector
├── event/                 # Coroutine-based EventBus + sealed Events
├── group/                 # ServerGroup runtime state, GroupManager
├── scaling/               # ScalingEngine + ScalingRule (auto-scale by player count)
├── proxy/                 # ProxySyncManager (tab list, MOTD, chat, maintenance)
├── service/               # Service lifecycle, ProcessHandle, PortAllocator, ServerListPing
├── setup/                 # First-run interactive SetupWizard
├── stress/                # StressTestManager (simulated player load testing)
├── template/              # TemplateManager, ConfigPatcher, SoftwareResolver (auto-download)
└── velocity/              # VelocityConfigGen (auto-manage proxy server list)
```

## Configuration

- `config/nimbus.toml` — Main config (network, controller, console, paths, API, database)
- `config/groups/*.toml` — One file per server group (proxy, lobby, game servers)
- `data/nimbus.db` — SQLite database (default, configurable to MySQL/PostgreSQL)
- `config/modules/display/*.toml` — Display configs per group (signs + NPCs)
- `config/modules/syncproxy/motd.toml` — MOTD + maintenance mode config
- `config/modules/syncproxy/tablist.toml` — Tab list header, footer, player format
- `config/modules/syncproxy/chat.toml` — Chat format settings
- Config keys use `snake_case`, group/service names use `PascalCase`

## Key Patterns

- Services named `<GroupName>-<N>` (e.g., `Lobby-1`, `BedWars-3`)
- Proxy ports: 25565+, backend ports: 30000+
- Velocity forwarding: `modern` if all backends >=1.13, else `legacy` (BungeeCord)
- Via plugins (ViaVersion/ViaBackwards) only on backend servers, never on proxy
- EULA auto-accepted for Paper/Purpur/Folia templates
- Folia: SDK + ProtocolLib auto-excluded (incompatible with regionized threading)
- Performance optimizer: Aikar's JVM flags + Paper/Purpur/Folia config tuning (optimize=true default)
- Process ready detection: watches stdout for "Done" pattern
- Graceful shutdown order: game servers → lobbies → proxies
- Shutdown requires confirmation: `shutdown` then `shutdown confirm` within 30s
- ProtocolLib auto-deployed to backend servers (embedded in JAR, tracked via `.nimbus-plugins`)
- Bedrock support: Geyser + Floodgate auto-downloaded from GeyserMC API, key.pem centrally managed

## Code Style

- Kotlin, no frameworks (no Spring/DI). Direct object wiring in `Nimbus.kt`
- Coroutines for all async work (no raw threads)
- Sealed classes for events (`Events.kt`) and enums for state (`ServiceState.kt`)
- ANSI-colored console output via `ConsoleFormatter`

## API (v0.2)

- Bearer token auth (`Authorization: Bearer <token>`)
- REST: `/api/services`, `/api/groups`, `/api/status`, `/api/players`, `/api/maintenance`, `/api/stress`, `/api/reload`, `/api/shutdown`
- WebSocket: `/api/events` (live events), `/api/services/{name}/console` (bidirectional)
- `/api/health` is always public (no auth)

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
- ScalingEngine reacts naturally (starts new instances up to `max_instances`)
- Proxy services auto-reflect total backend player count
- Fake player entities appear in tab list on backend servers via ProtocolLib
- `StressBot-*` events are suppressed from the console to avoid spam
- Template download to agent nodes includes global plugins (SDK, ProtocolLib)

### REST API
```
GET  /api/stress              # Status (active, players, capacity, per-service)
POST /api/stress/start        # Body: {"players": 100, "group": "Lobby", "rampSeconds": 30}
POST /api/stress/stop         # Stop active test
POST /api/stress/ramp         # Body: {"players": 200, "durationSeconds": 60}
```
