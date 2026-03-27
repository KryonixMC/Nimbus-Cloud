# ⛅ Nimbus — Project Plan

> The lightweight, console-only Minecraft cloud system.
> A modern, minimal alternative to CloudNet and SimpleCloud.

### ASCII Logo (used in CLI startup)

```
               ___  _  __
            .-~   ~~ ~  ~-.
          .~                ~.
        .~   N I M B U S    ~.
    .--~                      ~--.
   (______________________________ )

    The lightweight Minecraft cloud   v0.1.0
```

This logo is displayed on every `java -jar nimbus.jar` startup. The text `N I M B U S` is rendered in green (ANSI), the cloud outline in gray, and the tagline/version in dim gray.

---

## 1. Vision & Positioning

**Nimbus** is a lightweight Minecraft server cloud system that manages dynamic server instances (Lobbies, Minigame servers, etc.) from a single interactive console. It is designed for small-to-medium Minecraft networks running on one or two VPS/dedicated servers.

### Core Philosophy

- **Console-only** — no web dashboard, no REST API, no external interfaces. Everything is controlled from an interactive terminal console.
- **Single JAR** — `java -jar nimbus.jar` starts everything. No complex multi-process setup.
- **TOML config** — one file per server group, human-readable, no YAML/JSON nesting hell.
- **Velocity-first** — modern proxy only, no legacy BungeeCord support.
- **Kotlin + Coroutines** — modern async architecture, no thread pool overhead.

### Differentiation from Competitors

| Feature | CloudNet v3 | SimpleCloud v3 | **Nimbus** |
|---------|------------|----------------|------------|
| Language | Java 8+ | Kotlin | **Kotlin 2.x** |
| Config format | JSON | YAML | **TOML** |
| Interface | Console + REST | CLI + Dashboard + REST | **Console only** |
| Proxy support | Bungee + Velocity | Bungee + Velocity | **Velocity only** |
| Architecture | Monolith | Microservices/K8s | **Single process** |
| Setup complexity | Medium | High (K8s for v3) | **Minimal** |
| Target audience | All sizes | Medium-large | **Small-medium** |

---

## 2. Architecture

### High-Level Overview

```
┌─────────────────────────────────────────────────────┐
│                  Nimbus Console                       │
│              (Interactive REPL/TUI)                   │
└─────────────────────┬───────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────┐
│                Nimbus Controller                      │
│                                                       │
│  ┌──────────────┐ ┌────────────────┐ ┌─────────────┐│
│  │Group Manager  │ │Service Registry│ │Template Store││
│  └──────────────┘ └────────────────┘ └─────────────┘│
│  ┌──────────────┐ ┌────────────────┐ ┌─────────────┐│
│  │Scaling Engine│ │Process Manager │ │  Event Bus   ││
│  └──────────────┘ └────────────────┘ └─────────────┘│
└─────────────────────┬───────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────┐
│            Dynamic Services (JVM Processes)           │
│                                                       │
│  [Velocity]  [Lobby-1]  [Lobby-2]  [BW-1]  [BW-2]  │
└─────────────────────┬───────────────────────────────┘
                      │
                  Players
```

### Component Responsibilities

#### Controller (Orchestrator)
The central component that manages the entire cloud lifecycle.

- **Group Manager**: Loads group configs from TOML files, manages group state (how many instances should run, are running, etc.)
- **Service Registry**: Tracks all running services with their state (STARTING, READY, STOPPING, CRASHED), port, PID, player count.
- **Template Store**: Manages server templates (directories containing server JARs, configs, plugins, worlds). Copies templates into isolated working directories for each service.
- **Scaling Engine**: Monitors player counts per group. When a group's fill rate exceeds `scale_threshold`, starts a new instance. When a service is empty for `idle_timeout` seconds and instances > `min_instances`, stops it.
- **Process Manager**: Starts/stops JVM processes. Wraps `ProcessBuilder`, captures stdout/stderr, handles crash detection and auto-restart.
- **Event Bus**: Internal pub/sub for decoupled communication between components. Events like `ServiceStarted`, `ServiceReady`, `ServiceCrashed`, `PlayerJoined`, `ScaleUp`, `ScaleDown`.

#### Console (Interactive REPL)
A JLine3-based interactive console that runs in the foreground. Features:

- Command input with tab-completion and history
- Live event stream (scaling events, crashes, player joins) printed between command prompts
- `screen` command to attach to a service's stdin/stdout (like CloudNet's screen system)
- Colored output with status indicators

#### Dynamic Services
Managed JVM processes, each running in an isolated working directory under `running/<service-name>/`. The controller:

1. Copies the template directory
2. Generates/patches config files (e.g., `server.properties` with assigned port)
3. Starts the JVM process with configured memory and JVM args
4. Monitors the process for crashes
5. Cleans up the working directory on stop

### Data Flow: Starting a Service

```
1. GroupManager reads groups/bedwars.toml
2. ScalingEngine detects: instances < min_instances
3. ScalingEngine emits ScaleUp event
4. ServiceManager receives ScaleUp
5. TemplateManager copies templates/bedwars/ → running/BedWars-1/
6. ServiceManager patches server.properties (port, etc.)
7. ProcessManager starts JVM with configured args
8. Service writes "Done" to stdout → ProcessManager detects READY
9. ServiceManager updates ServiceRegistry: BedWars-1 = READY
10. VelocityConfig regenerates velocity server list
11. Console prints: [START] BedWars-1 on port 25567
```

### Data Flow: Auto-Scaling

```
1. ScalingEngine runs periodic check (every 5s)
2. For each group: calculate fill_rate = total_players / (instances * max_players_per_instance)
3. If fill_rate > scale_threshold AND instances < max_instances:
   → Emit ScaleUp event → start new instance
4. If a service has 0 players for > idle_timeout AND instances > min_instances:
   → Emit ScaleDown event → gracefully stop service
```

### Data Flow: Crash Recovery

```
1. ProcessManager detects process exit (non-zero exit code)
2. Emits ServiceCrashed event
3. ServiceManager checks: restart_count < max_restarts?
   → Yes: restart service (re-use same working directory)
   → No: mark as FAILED, log error, do not restart
```

---

## 3. Configuration

### Directory Structure

```
nimbus/
├── nimbus.jar                # The Nimbus executable
├── nimbus.toml               # Main configuration
├── groups/                   # One TOML per server group
│   ├── proxy.toml
│   ├── lobby.toml
│   └── bedwars.toml
├── templates/                # Server templates
│   ├── proxy/                # Velocity template
│   │   ├── velocity.jar
│   │   └── plugins/
│   ├── lobby/                # Lobby template
│   │   ├── paper.jar
│   │   ├── plugins/
│   │   └── server.properties
│   └── bedwars/              # BedWars template
│       ├── paper.jar
│       ├── plugins/
│       ├── worlds/
│       └── server.properties
├── running/                  # Auto-created, isolated service dirs
│   ├── Proxy-1/
│   ├── Lobby-1/
│   ├── Lobby-2/
│   └── BedWars-1/
└── logs/                     # Nimbus controller logs
    └── nimbus.log
```

### nimbus.toml — Main Config

```toml
# ⛅ Nimbus — Main Configuration

[network]
name = "QEEX"
bind = "0.0.0.0"

[controller]
max_memory = "10G"          # Total memory budget for all services
max_services = 20           # Max concurrent services
heartbeat_interval = 5000   # ms — how often to check service health

[console]
colored = true              # ANSI colors in output
log_events = true           # Print scaling/crash events to console
history_file = ".nimbus_history"

[paths]
templates = "templates"
running = "running"
logs = "logs"
```

### groups/proxy.toml — Proxy Group

```toml
[group]
name = "Proxy"
type = "STATIC"             # STATIC = always exactly min_instances running
template = "proxy"
software = "VELOCITY"
version = "3.4.0"

[group.resources]
memory = "512M"
max_players = 500

[group.scaling]
min_instances = 1
max_instances = 1           # Proxy is always 1 instance

[group.jvm]
args = ["-XX:+UseG1GC", "-XX:MaxGCPauseMillis=50"]
```

### groups/lobby.toml — Lobby Group (Dynamic)

```toml
[group]
name = "Lobby"
type = "DYNAMIC"
template = "lobby"
software = "PAPER"
version = "1.21.4"

[group.resources]
memory = "1G"
max_players = 50

[group.scaling]
min_instances = 1
max_instances = 4
players_per_instance = 40   # Target players per instance
scale_threshold = 0.8       # Scale up at 80% fill
idle_timeout = 0            # Never shut down lobby (0 = disabled)

[group.lifecycle]
stop_on_empty = false       # Lobbies stay running
restart_on_crash = true
max_restarts = 5

[group.jvm]
args = ["-XX:+UseG1GC", "-XX:MaxGCPauseMillis=50"]
```

### groups/bedwars.toml — Game Server Group (Dynamic)

```toml
[group]
name = "BedWars"
type = "DYNAMIC"
template = "bedwars"
software = "PAPER"
version = "1.21.4"

[group.resources]
memory = "2G"
max_players = 16

[group.scaling]
min_instances = 1
max_instances = 10
players_per_instance = 16
scale_threshold = 0.75
idle_timeout = 300          # Shutdown after 5 min empty

[group.lifecycle]
stop_on_empty = true        # Stop game servers when empty
restart_on_crash = true
max_restarts = 3

[group.jvm]
args = ["-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled"]
```

---

## 4. Interactive Console

### Startup Sequence

```
$ java -jar nimbus.jar

               ___  _  __
            .-~   ~~ ~  ~-.
          .~                ~.
        .~   N I M B U S    ~.
    .--~                      ~--.
   (______________________________ )

    The lightweight Minecraft cloud   v0.1.0

  [14:20:01] INFO  Loading nimbus.toml...
  [14:20:01] INFO  Found 3 groups: Proxy, Lobby, BedWars
  [14:20:01] INFO  Starting services...
  [14:20:02] START Proxy-1 on port 25565 (Velocity 3.4.0)
  [14:20:04] START Lobby-1 on port 25566 (Paper 1.21.4)
  [14:20:06] START BedWars-1 on port 25567 (Paper 1.21.4)
  [14:20:06] INFO  All services ready. Network online.

  nimbus > _
```

### Command Reference

#### Service Commands

| Command | Description |
|---------|-------------|
| `list` | Show all running services with status, port, players, memory |
| `start <group>` | Manually start a new instance of a group |
| `stop <service>` | Gracefully stop a specific service |
| `restart <service>` | Stop and restart a service |
| `screen <service>` | Attach to service console (Ctrl+Q to detach) |
| `exec <service> <command>` | Execute a command on a service without attaching |
| `logs <service>` | Show last N lines of a service's log output |

#### Group Commands

| Command | Description |
|---------|-------------|
| `groups` | List all configured groups with instance counts |
| `info <group>` | Show group config, scaling rules, active instances |

#### Network Commands

| Command | Description |
|---------|-------------|
| `status` | Full cluster overview: groups, resources, uptime |
| `players` | List all connected players and their current server |
| `send <player> <service>` | Transfer a player to another service |

#### System Commands

| Command | Description |
|---------|-------------|
| `reload` | Hot-reload group TOML files (without restarting services) |
| `shutdown` | Graceful shutdown: stop all services, then exit |
| `clear` | Clear console output |
| `help` | Show command list |

### Screen Session

The `screen` command attaches to a service's stdin/stdout, letting you interact with the Minecraft server console directly:

```
nimbus > screen BedWars-1

  Attached to BedWars-1 — press Ctrl+Q to detach

  [14:25:12] xNotch joined the game
  [14:25:44] Dream joined the game
  [14:26:01] [WARN] Can't keep up! TPS: 18.4

  BedWars-1 > say Hello from the cloud!
  [14:27:01] [Server] Hello from the cloud!

  BedWars-1 > [Ctrl+Q]

  Detached from BedWars-1

nimbus > _
```

### Status Display

```
nimbus > status

  ⛅ QEEX Network    ● online    uptime 4h 23m

  GROUP          SERVICES   PLAYERS   MEMORY    STATUS
  Proxy          1/1        47/500    512 MB    ●
  Lobby          2/4        12/80     1.0 GB    ●
  BedWars        3/10       28/48     3.0 GB    ●
  SkyBlock       1/1        7/50      2.0 GB    ● scaling

  CPU  23%  ████░░░░░░░░░░░░
  MEM  61%  █████████░░░░░░░  6.5 / 10.0 GB
```

### Live Event Stream

Events are printed to the console in the background while you work:

```
nimbus > _

  [14:28:01] SCALE  SkyBlock: 1 → 2 instances (threshold 80%)
  [14:28:03] START  SkyBlock-2 on port 25570
  [14:28:08] READY  SkyBlock-2 accepting players
  [14:30:44] CRASH  BedWars-3 exited (code 1)
  [14:30:44] START  BedWars-3 restarting (attempt 1/3)
  [14:30:49] READY  BedWars-3 back online

nimbus > _
```

---

## 5. Project Structure

```
nimbus/
├── nimbus-core/                     # Single module — everything lives here
│   └── src/main/kotlin/dev/nimbus/
│       ├── Nimbus.kt                # Entry point: bootstrap, start controller + console
│       │
│       ├── config/
│       │   ├── NimbusConfig.kt      # Data class for nimbus.toml
│       │   ├── GroupConfig.kt       # Data class for group TOML files
│       │   └── ConfigLoader.kt      # TOML parsing + validation + hot-reload
│       │
│       ├── group/
│       │   ├── ServerGroup.kt       # Runtime group state (instances, fill rate)
│       │   └── GroupManager.kt      # Load groups, CRUD, provide to scaling engine
│       │
│       ├── service/
│       │   ├── Service.kt           # Service model (name, group, state, port, pid, players)
│       │   ├── ServiceState.kt      # Enum: STARTING, READY, STOPPING, STOPPED, CRASHED
│       │   ├── ServiceManager.kt    # Start/stop/restart orchestration
│       │   ├── ProcessHandle.kt     # JVM process wrapper (stdin/stdout/stderr capture)
│       │   └── PortAllocator.kt     # Assign unique ports from a range (25565+)
│       │
│       ├── scaling/
│       │   ├── ScalingEngine.kt     # Periodic check loop, emit scale events
│       │   └── ScalingRule.kt       # Evaluate: should we scale up/down?
│       │
│       ├── template/
│       │   ├── TemplateManager.kt   # Copy template dir → running/<n>/
│       │   ├── SoftwareResolver.kt  # Download Paper/Velocity JARs from API
│       │   └── ConfigPatcher.kt     # Patch server.properties, velocity.toml
│       │
│       ├── velocity/
│       │   └── VelocityConfigGen.kt # Generate/update velocity.toml server entries
│       │
│       ├── console/
│       │   ├── NimbusConsole.kt     # JLine3 REPL: input loop, tab completion, history
│       │   ├── CommandDispatcher.kt # Parse input → find command → execute
│       │   ├── ScreenSession.kt     # Attach to service I/O (pipe stdin/stdout)
│       │   ├── ConsoleFormatter.kt  # ANSI colors, table formatting, progress bars
│       │   └── commands/
│       │       ├── ListCommand.kt
│       │       ├── StartCommand.kt
│       │       ├── StopCommand.kt
│       │       ├── RestartCommand.kt
│       │       ├── StatusCommand.kt
│       │       ├── ScreenCommand.kt
│       │       ├── ExecCommand.kt
│       │       ├── GroupsCommand.kt
│       │       ├── InfoCommand.kt
│       │       ├── PlayersCommand.kt
│       │       ├── SendCommand.kt
│       │       ├── ReloadCommand.kt
│       │       ├── ShutdownCommand.kt
│       │       ├── ClearCommand.kt
│       │       └── HelpCommand.kt
│       │
│       └── event/
│           ├── EventBus.kt          # Simple in-process pub/sub with coroutines
│           └── Events.kt            # Sealed class hierarchy of all events
│
├── build.gradle.kts                 # Gradle build with Shadow plugin for fat JAR
├── settings.gradle.kts
├── gradle.properties
│
├── nimbus.toml                      # Example main config
├── groups/
│   ├── proxy.toml                   # Example proxy group
│   ├── lobby.toml                   # Example lobby group
│   └── bedwars.toml                 # Example game group
├── templates/                       # Example templates (empty, user fills these)
│   ├── proxy/
│   ├── lobby/
│   └── bedwars/
│
├── LICENSE                          # MIT
└── README.md
```

---

## 6. Tech Stack & Dependencies

### Language & Build

| Component | Choice | Reason |
|-----------|--------|--------|
| Language | Kotlin 2.1.x | Modern, concise, coroutines, JVM-native |
| Build | Gradle 8.x (Kotlin DSL) | Standard for Kotlin/JVM projects |
| JVM target | Java 21 | Modern LTS, virtual threads available |
| Fat JAR | Shadow plugin | Single executable JAR |

### Libraries

| Library | Purpose | Coordinates |
|---------|---------|-------------|
| kotlinx-coroutines | Async scaling loops, event bus, process I/O | `org.jetbrains.kotlinx:kotlinx-coroutines-core` |
| kotlinx-serialization | JSON serialization (for Velocity config) | `org.jetbrains.kotlinx:kotlinx-serialization-json` |
| ktoml | TOML parsing for config files | `com.akuleshov7:ktoml-core` + `ktoml-file` |
| JLine 3 | Interactive console (readline, history, completion) | `org.jline:jline` |
| slf4j + logback | Logging | `org.slf4j:slf4j-api` + `ch.qos.logback:logback-classic` |
| ktor-client (optional) | HTTP client for downloading Paper/Velocity JARs | `io.ktor:ktor-client-cio` |

### Why These Choices

- **ktoml** over other TOML libs: Pure Kotlin, kotlinx-serialization integration, no Java bloat.
- **JLine3**: Industry standard for JVM console apps. Tab completion, history, key bindings out of the box. Used by Minecraft itself.
- **Coroutines** over threads: The scaling engine, event bus, and process I/O monitoring all benefit from structured concurrency. No thread pool tuning needed.
- **No Spring/Micronaut/etc**: We don't need a framework. This is a standalone application, not a web service.

---

## 7. Key Implementation Details

### Port Allocation

```kotlin
// PortAllocator assigns ports sequentially from a base port
// Base port: 25565 (Velocity proxy gets this)
// Game servers: 25566, 25567, 25568, ...
// Track allocated ports in a Set<Int>
// Release port on service stop
// Check port availability with ServerSocket before assigning
```

### Velocity Integration

Nimbus automatically manages the Velocity proxy's server list:

1. On service start: add server entry to `velocity.toml` under `[servers]`
2. On service stop: remove server entry
3. The `try` list in Velocity config always points to Lobby instances
4. Velocity process is reloaded (or config hot-patched) after changes

The `VelocityConfigGen` component handles this by:
- Reading the template's `velocity.toml`
- Injecting/removing server entries based on current ServiceRegistry state
- Writing the updated file to the Velocity service's working directory

### Process Management

Each service runs as a child process via `ProcessBuilder`:

```kotlin
// ProcessHandle wraps a JVM child process
// - Captures stdout/stderr line-by-line via coroutines
// - Provides stdin writer for screen sessions and exec commands
// - Detects "Done" pattern in stdout → marks service as READY
// - Detects process exit → emits ServiceCrashed or ServiceStopped event
// - Supports graceful shutdown: send "stop" command, wait N seconds, force kill
```

### Service Naming

Services are named `<GroupName>-<N>` where N is an auto-incrementing counter per group:
- `Proxy-1`, `Lobby-1`, `Lobby-2`, `BedWars-1`, `BedWars-2`, etc.
- Counter resets when all instances of a group are stopped
- Names must be unique across the entire cloud at any point in time

### Config Hot-Reload

The `reload` command re-reads all TOML files without restarting services:
- New groups are registered (but not auto-started until scaling triggers)
- Modified scaling rules take effect immediately
- Removed groups: running services are NOT stopped (warning printed)
- Validation errors: reject the reload, keep current config

### Player Count Tracking

Since there's no Plugin API in v0.1, player counts are tracked by:
1. Parsing Velocity's stdout for player join/leave events
2. OR: periodically querying each service via the Minecraft server list ping protocol (port 25565+ query)
3. The scaling engine uses these counts to make decisions

Option 2 (server list ping) is more reliable and doesn't depend on log format. Implement `ServerListPing` as a simple TCP client that sends a status request packet and parses the response JSON for `players.online`.

---

## 8. Development Roadmap

### Phase 1: Foundation (Weeks 1-2)

**Goal**: Boot Nimbus, load configs, start/stop JVM processes manually.

- [ ] Gradle project setup (Kotlin, Shadow, dependencies)
- [ ] `NimbusConfig` + `GroupConfig` data classes with ktoml deserialization
- [ ] `ConfigLoader`: read `nimbus.toml` + scan `groups/*.toml`
- [ ] `TemplateManager`: copy template directory to `running/<n>/`
- [ ] `ConfigPatcher`: patch `server.properties` with assigned port
- [ ] `PortAllocator`: sequential port assignment with availability check
- [ ] `ProcessHandle`: start JVM, capture stdout/stderr, detect ready state
- [ ] `ServiceManager`: start/stop services, track state
- [ ] `ServiceRegistry`: in-memory map of running services
- [ ] Basic `NimbusConsole` with JLine3: `help`, `list`, `start`, `stop`, `status`
- [ ] `Nimbus.kt` entry point: load config → start min_instances → open console

**Milestone**: `java -jar nimbus.jar` boots, starts configured services, console works.

### Phase 2: Networking & Console (Weeks 3-4)

**Goal**: Velocity integration, full console with screen attach, player tracking.

- [ ] `VelocityConfigGen`: auto-manage Velocity server list
- [ ] `ScreenSession`: attach/detach to service I/O
- [ ] `ExecCommand`: run command on service without attaching
- [ ] `ConsoleFormatter`: colored output, table rendering, progress bars
- [ ] Tab completion for service names and group names
- [ ] `ServerListPing`: query player counts from running services
- [ ] `PlayersCommand`: list players across all services
- [ ] `SendCommand`: transfer player via Velocity command execution
- [ ] Live event stream in console (print events between prompts)
- [ ] `LogsCommand`: show recent log lines from a service

**Milestone**: Full interactive console, Velocity auto-configured, player counts visible.

### Phase 3: Intelligence (Weeks 5-6)

**Goal**: Auto-scaling, crash recovery, config hot-reload.

- [ ] `EventBus`: coroutine-based pub/sub
- [ ] `Events.kt`: full event hierarchy (ServiceStarted, ServiceReady, ServiceCrashed, ScaleUp, ScaleDown, PlayerJoined, PlayerLeft, etc.)
- [ ] `ScalingEngine`: periodic evaluation loop using player counts
- [ ] `ScalingRule`: evaluate threshold + min/max instances
- [ ] Auto-scale up: start new instance when fill > threshold
- [ ] Auto-scale down: stop empty instance after idle_timeout
- [ ] Crash detection + auto-restart with attempt counter
- [ ] `ReloadCommand`: hot-reload TOML configs
- [ ] `SoftwareResolver`: download Paper/Velocity JARs from Mojang/PaperMC API
- [ ] `GroupsCommand` + `InfoCommand`: display group details

**Milestone**: Fully autonomous cloud — auto-scales, auto-recovers, hot-reloads.

### Phase 4: Polish & Release (Week 7)

**Goal**: Error handling, logging, README, initial release.

- [ ] Comprehensive error handling (invalid configs, port conflicts, OOM)
- [ ] Logback configuration (file + console, rotation)
- [ ] Graceful shutdown: `shutdown` command stops all services in order (game → lobby → proxy)
- [ ] Signal handling (Ctrl+C → graceful shutdown)
- [ ] README.md with getting started guide, config reference, screenshots
- [ ] Example configs for common setups (Lobby + BedWars, Lobby + SkyBlock)
- [ ] GitHub release with Shadow JAR artifact
- [ ] MIT License

**Milestone**: v0.1.0 public release on GitHub.

---

## 9. Future Roadmap (Post v0.1)

These features are explicitly **out of scope** for v0.1 but planned for later:

- **v0.2**: REST API (Ktor) for external integrations
- **v0.3**: Web Dashboard (Astro + React)
- **v0.4**: Plugin/Bridge API (Kotlin SDK for Paper/Velocity plugins)
- **v0.5**: Multi-Node support (run services across multiple machines)
- **v0.6**: Module system (extend Nimbus with plugins)
- **v1.0**: Docs site (Astro Starlight), Landing Page, stable API

---

## 10. Build & Run

### Build

```bash
./gradlew shadowJar
# Output: build/libs/nimbus-0.1.0-all.jar
```

### Run

```bash
java -jar nimbus-0.1.0-all.jar
```

### Development

```bash
./gradlew run
```

---

## 11. Naming Conventions

| Element | Convention | Example |
|---------|-----------|---------|
| Package | `dev.nimbus` | `dev.nimbus.service` |
| Classes | PascalCase | `ServiceManager`, `ScalingEngine` |
| Files | Match class name | `ServiceManager.kt` |
| Config keys | snake_case | `max_players`, `idle_timeout` |
| Service names | PascalCase-N | `Lobby-1`, `BedWars-3` |
| Group names | PascalCase | `Lobby`, `BedWars`, `SkyBlock` |
| Events | Past tense | `ServiceStarted`, `PlayerJoined` |
| Commands | lowercase | `list`, `start`, `screen` |

---

## 12. Design Decisions Log

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Single module vs multi-module | Single (`nimbus-core`) | No need for module boundaries yet. Split later when API/Bridge are added. |
| TOML over YAML | TOML | Cleaner syntax, explicit types, better for flat configs. MC community knows YAML but TOML is objectively better for this use case. |
| JLine3 over custom console | JLine3 | Battle-tested, tab completion, history, key bindings. Don't reinvent the wheel. |
| Coroutines over threads | Coroutines | Structured concurrency for process I/O, scaling loops, event bus. Cleaner cancellation. |
| Server list ping over log parsing | Server list ping | More reliable, doesn't depend on log format, works with any server software. |
| Shadow JAR over multi-file dist | Shadow JAR | Single file distribution. Users download one JAR. |
| Sequential ports over random | Sequential | Predictable, easy to firewall, easy to debug. |
| Velocity only (no BungeeCord) | Velocity | Modern, better performance, active development. BungeeCord is legacy. |
| No database | File-based state | For v0.1, all state is in-memory + TOML files. No need for SQLite/Redis yet. |

---

*Last updated: 2026-03-27*
*Author: Jonas (laux.digital)*
