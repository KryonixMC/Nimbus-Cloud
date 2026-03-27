# Nimbus — Development Progress

## Phase 1: Foundation
> Goal: Boot Nimbus, load configs, start/stop JVM processes manually.

| # | Task | Status |
|---|------|--------|
| 1 | Gradle project setup (Kotlin 2.1.10, Shadow, all deps) | done |
| 2 | `NimbusConfig` + `GroupConfig` data classes with ktoml | done |
| 3 | `ConfigLoader`: read nimbus.toml + scan groups/*.toml | done |
| 4 | `EventBus`: coroutine-based pub/sub | done |
| 5 | `Events.kt`: sealed class event hierarchy | done |
| 6 | `PortAllocator`: sequential port assignment | done |
| 7 | `TemplateManager`: copy template dir to running/\<n\>/ | done |
| 8 | `ConfigPatcher`: patch server.properties with port | done |
| 9 | `ProcessHandle`: start JVM, capture stdout/stderr, detect ready | done |
| 10 | `ServiceState` enum + `Service` model | done |
| 11 | `ServiceRegistry`: in-memory map of running services | done |
| 12 | `ServiceManager`: start/stop/restart orchestration | done |
| 13 | `NimbusConsole` with JLine3 + 12 commands | done |
| 14 | `Nimbus.kt` entry point: load config, start services, open console | done |
| 15 | Example TOML configs (nimbus.toml, proxy, lobby, bedwars) | done |

### Build Verification
- Compiles successfully with `./gradlew :nimbus-core:compileKotlin`
- Shadow JAR built: `nimbus-core-0.1.0-all.jar` (11 MB)
- Run with: `java -jar nimbus-core/build/libs/nimbus-core-0.1.0-all.jar`

### Files Created (Phase 1)

```
nimbus/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradle/
├── nimbus.toml
├── groups/
│   ├── proxy.toml
│   ├── lobby.toml
│   └── bedwars.toml
├── templates/{proxy,lobby,bedwars}/
└── nimbus-core/
    ├── build.gradle.kts
    └── src/main/kotlin/dev/nimbus/
        ├── Nimbus.kt                    # Entry point
        ├── config/
        │   ├── NimbusConfig.kt          # Main config data classes
        │   ├── GroupConfig.kt           # Group config data classes + enums
        │   └── ConfigLoader.kt          # TOML loading + validation
        ├── event/
        │   ├── Events.kt               # Sealed event hierarchy
        │   └── EventBus.kt             # Coroutine-based pub/sub
        ├── group/
        │   ├── ServerGroup.kt          # Runtime group state
        │   └── GroupManager.kt         # Group CRUD + reload
        ├── service/
        │   ├── ServiceState.kt         # State enum
        │   ├── Service.kt              # Service data class
        │   ├── ServiceRegistry.kt      # Thread-safe service map
        │   ├── PortAllocator.kt        # Sequential port allocation
        │   ├── ProcessHandle.kt        # JVM process wrapper
        │   └── ServiceManager.kt       # Orchestrator
        ├── template/
        │   ├── TemplateManager.kt      # Template copy
        │   └── ConfigPatcher.kt        # server.properties / velocity.toml patching
        └── console/
            ├── NimbusConsole.kt         # JLine3 REPL
            ├── CommandDispatcher.kt     # Command routing + tab completion
            ├── ConsoleFormatter.kt      # ANSI colors + tables
            ├── ScreenSession.kt         # Attach to service I/O
            └── commands/
                ├── HelpCommand.kt
                ├── ListCommand.kt
                ├── StartCommand.kt
                ├── StopCommand.kt
                ├── RestartCommand.kt
                ├── StatusCommand.kt
                ├── ScreenCommand.kt
                ├── ExecCommand.kt
                ├── GroupsCommand.kt
                ├── InfoCommand.kt
                ├── ClearCommand.kt
                └── ShutdownCommand.kt
```

---

## Phase 2: Networking & Console
> Goal: Velocity integration, full console with screen attach, player tracking.

| # | Task | Status |
|---|------|--------|
| 1 | `VelocityConfigGen`: auto-manage Velocity server list | done |
| 2 | `ServerListPing`: query player counts via MC protocol | done |
| 3 | `PlayersCommand`: list players across services | done |
| 4 | `SendCommand`: transfer player via Velocity | done |
| 5 | `LogsCommand`: show recent log lines from a service | done |
| 6 | Tab completion for service/group names | done |
| 7 | Wire Velocity integration into ServiceManager lifecycle | done |

### New Files (Phase 2)

```
nimbus-core/src/main/kotlin/dev/nimbus/
├── velocity/
│   └── VelocityConfigGen.kt      # Auto-manage Velocity server list
├── service/
│   └── ServerListPing.kt         # MC protocol SLP implementation
└── console/commands/
    ├── PlayersCommand.kt          # List players across services
    ├── SendCommand.kt             # Transfer player via Velocity
    └── LogsCommand.kt            # Show service log output
```

### Changes (Phase 2)
- `CommandDispatcher.kt` — contextual tab completion (service/group names)
- `NimbusConsole.kt` — registers 3 new commands (players, send, logs), wires tab completion context
- `ServiceManager.kt` — calls `VelocityConfigGen.updateProxyServerList()` on service ready/stop

---

## Phase 3: Intelligence
> Goal: Auto-scaling, crash recovery, config hot-reload, auto-download.

| # | Task | Status |
|---|------|--------|
| 1 | `SoftwareResolver`: auto-download Paper/Velocity/Purpur JARs | done |
| 2 | `ScalingEngine` + `ScalingRule`: periodic evaluation, scale up/down | done |
| 3 | `ReloadCommand`: hot-reload group TOML configs | done |
| 4 | Wire SoftwareResolver into ServiceManager (auto-download + EULA) | done |
| 5 | Wire ScalingEngine into Nimbus.kt startup | done |

### New Files (Phase 3)

```
nimbus-core/src/main/kotlin/dev/nimbus/
├── template/
│   └── SoftwareResolver.kt       # Auto-download JARs from PaperMC/Purpur API
├── scaling/
│   ├── ScalingEngine.kt          # Periodic scaling evaluation loop
│   └── ScalingRule.kt            # Scale up/down decision logic
└── console/commands/
    └── ReloadCommand.kt          # Hot-reload TOML configs
```

### Key Behaviors
- **Auto-download**: Downloads Paper/Purpur/Velocity JARs from APIs if missing
- **Purpur support**: Uses separate Purpur API (`api.purpurmc.org`)
- **EULA auto-accept**: Creates `eula.txt` in Paper/Purpur template dirs
- **Auto-scaling**: Periodic ping → scale up on threshold, scale down on idle
- **Hot-reload**: `reload` command re-reads group TOMLs without restart

---

## Phase 4: Setup, UX & Bugfixes
> Goal: Interactive onboarding, create command, Velocity networking, version compat.

| # | Task | Status |
|---|------|--------|
| 1 | Interactive setup wizard on first launch | done |
| 2 | Redesign setup wizard (cloud banner, steps, templates, tab completion) | done |
| 3 | Dynamic version fetching from PaperMC/Purpur APIs | done |
| 4 | ViaVersion/ViaBackwards/ViaRewind plugin auto-download (backend only) | done |
| 5 | `create` command for adding groups at runtime | done |
| 6 | Velocity auto-init (first-run config generation) | done |
| 7 | Velocity server linking (`velocity reload` after config update) | done |
| 8 | Velocity modern forwarding + Paper forwarding secret | done |
| 9 | Port separation (Proxy=25565, Backend=30000+) | done |
| 10 | Fix: exit code 0 not treated as crash | done |
| 11 | Fix: `--nogui` → version-aware (only 1.13+) | done |
| 12 | Fix: `server.properties` created if missing (port assignment) | done |
| 13 | Fix: Via plugins only on backend, not proxy | done |
| 14 | Fix: Velocity always `online-mode=true` + `modern` forwarding | done |
| 15 | Screen detach with ESC key (in addition to Ctrl+Q) | done |
| 16 | Console init before service start (events visible during boot) | done |

### New Files (Phase 4)

```
nimbus-core/src/main/kotlin/dev/nimbus/
├── setup/
│   └── SetupWizard.kt            # Interactive first-time setup
└── console/commands/
    └── CreateGroupCommand.kt     # Create new group at runtime
```

### Key Changes (Phase 4)
- `SoftwareResolver.kt` — version fetching from APIs, Hangar plugin downloads, Purpur API support
- `ConfigPatcher.kt` — Velocity forwarding mode, Paper velocity config, creates server.properties if missing
- `ServiceManager.kt` — auto-init Velocity template, version-aware `--nogui`, port separation, forwarding config
- `PortAllocator.kt` — separate `allocateProxyPort()` (25565) and `allocateBackendPort()` (30000+)
- `VelocityConfigGen.kt` — robust TOML section replacement, original service names (case-sensitive)
- `ScreenSession.kt` — ESC key detach support
- `NimbusConsole.kt` — `init()` method for early setup, accepts `softwareResolver`
- `Nimbus.kt` — setup wizard before boot, console init before services

### Setup Wizard Features
- Cloud ASCII banner
- 5 numbered steps with colored output
- 3 templates: Standard Lobby / Lobby+Games / Custom
- Dynamic version list from PaperMC + Purpur APIs (1.8.8 to latest + nightlies)
- Tab completion for versions, software, yes/no
- ViaVersion/ViaBackwards/ViaRewind prompts with smart defaults
- Auto-download all JARs and plugins
- TOML config generation

### Architecture Decisions
- **Via plugins on backend only**: ViaVersion on Velocity proxy causes UUID parsing crashes with modern forwarding. Backend servers handle protocol translation themselves.
- **Velocity always modern forwarding + online-mode**: Proxy authenticates with Mojang, backend servers trust the proxy via forwarding secret.
- **Pre-1.13 servers**: No Velocity forwarding config (unsupported), `online-mode=false` only. Via on the backend handles protocol translation.
- **Port separation**: Proxy on 25565 (standard MC port), all backend servers on 30000+ (hidden from direct access).

---

## Full File Tree

```
nimbus-core/src/main/kotlin/dev/nimbus/
├── Nimbus.kt
├── config/
│   ├── NimbusConfig.kt
│   ├── GroupConfig.kt
│   └── ConfigLoader.kt
├── console/
│   ├── NimbusConsole.kt
│   ├── CommandDispatcher.kt
│   ├── ConsoleFormatter.kt
│   ├── ScreenSession.kt
│   └── commands/
│       ├── HelpCommand.kt
│       ├── ListCommand.kt
│       ├── StartCommand.kt
│       ├── StopCommand.kt
│       ├── RestartCommand.kt
│       ├── StatusCommand.kt
│       ├── ScreenCommand.kt
│       ├── ExecCommand.kt
│       ├── GroupsCommand.kt
│       ├── InfoCommand.kt
│       ├── PlayersCommand.kt
│       ├── SendCommand.kt
│       ├── LogsCommand.kt
│       ├── ReloadCommand.kt
│       ├── CreateGroupCommand.kt
│       ├── ClearCommand.kt
│       └── ShutdownCommand.kt
├── event/
│   ├── Events.kt
│   └── EventBus.kt
├── group/
│   ├── ServerGroup.kt
│   └── GroupManager.kt
├── scaling/
│   ├── ScalingEngine.kt
│   └── ScalingRule.kt
├── service/
│   ├── ServiceState.kt
│   ├── Service.kt
│   ├── ServiceRegistry.kt
│   ├── PortAllocator.kt
│   ├── ProcessHandle.kt
│   ├── ServerListPing.kt
│   └── ServiceManager.kt
├── setup/
│   └── SetupWizard.kt
├── template/
│   ├── TemplateManager.kt
│   ├── ConfigPatcher.kt
│   └── SoftwareResolver.kt
└── velocity/
    └── VelocityConfigGen.kt
```

---

*Last updated: 2026-03-27*
