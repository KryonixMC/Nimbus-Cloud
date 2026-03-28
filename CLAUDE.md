# Nimbus

Lightweight, console-only Minecraft cloud system. Manages dynamic server instances (lobbies, game servers) from a single JAR.

## Build & Run

```bash
./gradlew shadowJar                    # Fat JAR → nimbus-core/build/libs/nimbus-core-0.1.0-all.jar
./gradlew :nimbus-core:compileKotlin   # Quick compile check
java -jar nimbus-core/build/libs/nimbus-core-0.1.0-all.jar
```

## Modules

- `nimbus-core` — Main application (entry point: `dev.nimbus.NimbusKt`)
- `nimbus-cloud-plugin` — Velocity plugin: hub commands + cloud bridge (Java, auto-embedded as resource `nimbus-cloud.jar` during build)

## Tech Stack

- Kotlin 2.1.10, Java 21, Gradle + Shadow plugin
- ktoml for TOML config parsing
- JLine 3 for interactive console
- kotlinx-coroutines for async (scaling loops, event bus, process I/O)
- Ktor Client (CIO) for downloading server JARs
- Ktor Server (CIO) for REST API + WebSocket

## Architecture

```
nimbus-core/src/main/kotlin/dev/nimbus/
├── Nimbus.kt              # Entry point, bootstrap
├── api/                   # Ktor REST API + WebSocket (v0.2)
├── config/                # TOML config loading (NimbusConfig, GroupConfig)
├── console/               # JLine3 REPL, CommandDispatcher, 17 commands
├── event/                 # Coroutine-based EventBus + sealed Events
├── group/                 # ServerGroup runtime state, GroupManager
├── scaling/               # ScalingEngine + ScalingRule (auto-scale by player count)
├── service/               # Service lifecycle, ProcessHandle, PortAllocator, ServerListPing
├── setup/                 # First-run interactive SetupWizard
├── template/              # TemplateManager, ConfigPatcher, SoftwareResolver (auto-download)
└── velocity/              # VelocityConfigGen (auto-manage proxy server list)
```

## Configuration

- `nimbus.toml` — Main config (network, controller, console, paths, API)
- `groups/*.toml` — One file per server group (proxy, lobby, game servers)
- Config keys use `snake_case`, group/service names use `PascalCase`

## Key Patterns

- Services named `<GroupName>-<N>` (e.g., `Lobby-1`, `BedWars-3`)
- Proxy ports: 25565+, backend ports: 30000+
- Velocity forwarding: `modern` if all backends >=1.13, else `legacy` (BungeeCord)
- Via plugins (ViaVersion/ViaBackwards) only on backend servers, never on proxy
- EULA auto-accepted for Paper/Purpur templates
- Process ready detection: watches stdout for "Done" pattern
- Graceful shutdown order: game servers → lobbies → proxies

## Code Style

- Kotlin, no frameworks (no Spring/DI). Direct object wiring in `Nimbus.kt`
- Coroutines for all async work (no raw threads)
- Sealed classes for events (`Events.kt`) and enums for state (`ServiceState.kt`)
- ANSI-colored console output via `ConsoleFormatter`

## API (v0.2)

- Bearer token auth (`Authorization: Bearer <token>`)
- REST: `/api/services`, `/api/groups`, `/api/status`, `/api/players`, `/api/reload`, `/api/shutdown`
- WebSocket: `/api/events` (live events), `/api/services/{name}/console` (bidirectional)
- `/api/health` is always public (no auth)
