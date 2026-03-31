<div align="center">

<img src="docs/public/banner.svg" alt="Nimbus Cloud" width="460" />

**Lightweight Minecraft Cloud System**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Java](https://img.shields.io/badge/Java-21+-ED8B00.svg?style=for-the-badge&logo=openjdk&logoColor=white)](https://adoptium.net)
[![Gradle](https://img.shields.io/badge/Gradle-8.x-02303A.svg?style=for-the-badge&logo=gradle&logoColor=white)](https://gradle.org)
[![Docs](https://img.shields.io/badge/Docs-Read%20the%20Docs-0ea5e9.svg?style=for-the-badge&logo=readthedocs&logoColor=white)](https://jonax1337.github.io/Nimbus/)

Dynamic server management from a single JAR — auto-scaling, multi-node clusters, and a powerful API without the bloat.

[Documentation](https://jonax1337.github.io/Nimbus/) &#183; [Quick Start](#quick-start) &#183; [API Reference](https://jonax1337.github.io/Nimbus/reference/api.html)

</div>

---

## Features

- **Single JAR** — `java -jar nimbus.jar` starts everything, no external dependencies
- **Multi-Node Cluster** — distribute servers across machines with automatic placement, failover, and a built-in TCP load balancer
- **Auto-Scaling** — spin up/down instances based on real-time player count with configurable thresholds
- **9 Server Platforms** — Paper, Pufferfish, Purpur, Folia, Velocity, Forge, NeoForge, Fabric, and Custom JARs
- **Modpack Import** — import any Modrinth modpack with a single command (Fabric, Forge, NeoForge, Quilt)
- **Velocity-First** — auto-manages proxy server list, forwarding, tab list, MOTD, chat sync, and maintenance mode
- **Version Compatibility** — supports 1.8.8 to latest via adaptive forwarding + auto-deployed Via plugins
- **Bedrock Crossplay** — Geyser + Floodgate auto-configured for mobile and console players
- **Built-in Permissions** — groups, inheritance, tracks, prefixes/suffixes, wildcards, and audit log
- **REST API + WebSocket** — 40+ endpoints, live events, bidirectional console, file management, and metrics
- **Interactive Console** — JLine3-powered REPL with 28 commands, tab completion, and screen sessions
- **Performance Optimizer** — Aikar's JVM flags + Paper/Purpur/Pufferfish/Folia config tuning out of the box
- **Stress Testing** — simulate player load across servers without real Minecraft clients
- **Crash Recovery** — auto-restarts crashed servers with configurable attempt limits
- **TOML Config** — one file per server group, human-readable and hot-reloadable
- **Database** — SQLite (default), MySQL, or PostgreSQL for permissions, metrics, and player data

## Requirements

- **Java 21+** — everything else is bundled in the Shadow JAR

## Quick Start

```bash
# Download or build the JAR
java -jar nimbus.jar

# The setup wizard guides you through:
#   - Network name
#   - Proxy setup (Velocity, auto-detected)
#   - Bedrock support (Geyser + Floodgate)
#   - Permissions plugin (NimbusPerms)
#   - Server groups (Lobby, Game servers, etc.)
#   - Software downloads

# Nimbus starts all services automatically
```

See the [Quick Start Guide](https://jonax1337.github.io/Nimbus/guide/quickstart.html) for a full walkthrough.

## Build from Source

```bash
git clone https://github.com/jonax1337/Nimbus.git
cd Nimbus
./gradlew shadowJar

# Output: nimbus-core/build/libs/nimbus-core-<version>-all.jar
java -jar nimbus-core/build/libs/nimbus-core-<version>-all.jar
```

## Modules

| Module | Description |
|--------|-------------|
| `nimbus-core` | Main application — console, API, scaling, cluster, load balancer |
| `nimbus-agent` | Remote agent node for multi-node clusters |
| `nimbus-protocol` | Shared cluster message types (controller ↔ agent) |
| `nimbus-bridge` | Velocity plugin — hub commands, proxy sync, permissions |
| `nimbus-sdk` | Paper server SDK — game states, routing, events, player tracking |
| `nimbus-perms` | Paper permissions plugin — built-in or LuckPerms provider |
| `nimbus-display` | Paper display plugin — server selector signs and NPCs |

## Architecture

```
nimbus-core/src/main/kotlin/dev/nimbus/
├── Nimbus.kt              # Entry point, bootstrap
├── api/                   # Ktor REST API + WebSocket (40+ endpoints)
├── cluster/               # NodeManager, PlacementStrategy, ClusterServer
├── config/                # TOML config loading (NimbusConfig, GroupConfig)
├── console/               # JLine3 REPL, CommandDispatcher, 28 commands
├── database/              # Exposed ORM: SQLite/MySQL/PostgreSQL
├── display/               # Sign/NPC display configs per group
├── event/                 # Coroutine-based EventBus + sealed Events
├── group/                 # ServerGroup runtime state, GroupManager
├── loadbalancer/          # TCP load balancer, health checks, circuit breaker
├── permissions/           # Permission groups, tracks, wildcards, audit log
├── proxy/                 # ProxySyncManager (tab list, MOTD, chat, maintenance)
├── scaling/               # ScalingEngine + ScalingRule (auto-scale by player count)
├── service/               # Lifecycle, ProcessHandle, PortAllocator, ServerListPing
├── setup/                 # First-run interactive SetupWizard
├── stress/                # StressTestManager (simulated player load testing)
├── template/              # TemplateManager, SoftwareResolver, PerformanceOptimizer
└── velocity/              # VelocityConfigGen (auto-manage proxy server list)
```

## Configuration

<details>
<summary><b>config/nimbus.toml</b> — Main Config</summary>

```toml
[network]
name = "MyNetwork"
bind = "0.0.0.0"

[controller]
max_memory = "10G"
max_services = 20
heartbeat_interval = 5000

[console]
colored = true
log_events = true

[api]
enabled = true
bind = "0.0.0.0"
port = 8080
token = ""                      # auto-generated on first run

[bedrock]
enabled = false                 # Geyser + Floodgate auto-configured
base_port = 19132

[permissions]
deploy_plugin = true            # auto-deploy NimbusPerms to backends

[cluster]
enabled = false
agent_port = 8443
placement_strategy = "least-services"

[loadbalancer]
enabled = false
port = 25565
strategy = "least-players"

[database]
type = "sqlite"                 # sqlite, mysql, or postgresql
```

</details>

<details>
<summary><b>config/groups/lobby.toml</b> — Group Config</summary>

```toml
[group]
name = "Lobby"
type = "DYNAMIC"
template = "Lobby"
software = "PAPER"
version = "1.21.4"

[group.resources]
memory = "1G"
max_players = 50

[group.scaling]
min_instances = 1
max_instances = 4
players_per_instance = 40
scale_threshold = 0.8
idle_timeout = 0

[group.lifecycle]
stop_on_empty = false
restart_on_crash = true
max_restarts = 5

[group.jvm]
optimize = true                 # Aikar's flags + config tuning
```

</details>

## Console Commands

| Category | Command | Description |
|----------|---------|-------------|
| **Service** | `list [group]` | Show all running services with status, port, players |
| | `start <group>` | Start a new instance of a group |
| | `stop <service>` | Gracefully stop a service |
| | `restart <service>` | Stop and restart a service |
| | `screen <service>` | Attach to service console (ESC to detach) |
| | `exec <service> <cmd>` | Execute a command on a service |
| | `logs <service> [lines]` | Show recent log output |
| **Group** | `groups` | List all groups with instance counts |
| | `info <group>` | Show group config, scaling, and runtime state |
| | `create` | Interactive group creation wizard |
| | `import <url\|slug>` | Import a Modrinth modpack as a new group |
| | `update <group>` | Update software version or switch platforms |
| | `static group\|service` | Convert to static mode (persistent data) |
| | `dynamic <group>` | Convert to dynamic mode (fresh from template) |
| **Network** | `status` | Network overview: groups, players, capacity |
| | `players [service]` | List all connected players |
| | `send <player> <srv>` | Transfer a player to another service |
| | `maintenance` | Toggle maintenance mode (global or per-group) |
| **Permissions** | `perms group <sub>` | Manage permission groups (create, delete, permissions, display) |
| | `perms user <sub>` | Manage players (addgroup, check, promote, demote) |
| | `perms track <sub>` | Manage promotion tracks |
| | `perms audit` | View permission change audit log |
| **Cluster** | `cluster <sub>` | Enable/disable cluster mode, manage tokens |
| | `nodes [name]` | Show connected agent nodes |
| | `lb [sub]` | Load balancer status, enable/disable, strategy |
| **Stress** | `stress start <n>` | Start stress test with simulated players |
| | `stress stop` | Stop active stress test |
| **System** | `api <sub>` | Start/stop REST API, show token |
| | `reload` | Hot-reload group and proxy sync configs |
| | `shutdown` | Ordered shutdown: games → lobbies → proxies |

## API Highlights

```bash
# Health check (no auth)
curl http://localhost:8080/api/health

# List services
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/services

# Start a new instance
curl -X POST -H "Authorization: Bearer <token>" \
  http://localhost:8080/api/services/start/BedWars

# Live event stream
wscat -c "ws://localhost:8080/api/events?token=<token>"

# Prometheus metrics (no auth)
curl http://localhost:8080/api/metrics
```

See the full [REST API Reference](https://jonax1337.github.io/Nimbus/reference/api.html) for all 40+ endpoints.

## Tech Stack

| Component | Choice |
|-----------|--------|
| Language | Kotlin 2.1.10, Java 21 |
| Build | Gradle + Shadow plugin |
| Async | kotlinx-coroutines |
| Config | ktoml (TOML) |
| Console | JLine 3 |
| HTTP Client | Ktor Client (CIO) |
| API Server | Ktor Server (CIO) |
| Database | Exposed ORM + SQLite/MySQL/PostgreSQL |
| Downloads | Paper, Purpur, Pufferfish, Velocity, Modrinth, GeyserMC APIs |

## License

MIT — see [LICENSE](LICENSE)

---

<br>

<div align="center">

[![Star History Chart](https://api.star-history.com/svg?repos=jonax1337/Nimbus&type=Date)](https://star-history.com/#jonax1337/Nimbus&Date)

</div>
