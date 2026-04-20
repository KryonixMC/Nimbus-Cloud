<div align="center">

<img src="docs/public/icon.png" alt="Nimbus Cloud" width="100" />

<br />

### The Minecraft cloud that fits in a single JAR.

Auto-scaling server instances, multi-node clusters, a modern dashboard,<br/>
and a first-class REST API — without the bloat of enterprise cloud stacks.

<br />

[![License: MIT](https://img.shields.io/badge/License-MIT-blue?style=for-the-badge)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://adoptium.net)
[![Release](https://img.shields.io/github/v/release/NimbusPowered/Nimbus?include_prereleases&style=for-the-badge&color=22c55e&label=Release)](https://github.com/NimbusPowered/Nimbus/releases)
[![Docs](https://img.shields.io/badge/Docs-online-0ea5e9?style=for-the-badge&logo=readthedocs&logoColor=white)](https://NimbusPowered.github.io/Nimbus/)

<br />

**[Docs](https://NimbusPowered.github.io/Nimbus/)** &nbsp;·&nbsp;
**[Quick Start](#quick-start)** &nbsp;·&nbsp;
**[Dashboard](https://dashboard.nimbuspowered.org)** &nbsp;·&nbsp;
**[API Reference](https://NimbusPowered.github.io/Nimbus/reference/api.html)**

</div>

<br />

---

## Why Nimbus?

> One binary. One config directory. Zero Docker, zero Kubernetes, zero Redis.
> Start it on a $5 VPS, scale it to a fleet when you're ready.

Most Minecraft cloud systems either ship a single-server wrapper or drag along a full infrastructure stack. **Nimbus sits in between** — a single `java -jar` that handles dynamic servers, proxies, scaling, clustering, permissions, punishments, resource packs, backups, and a live web dashboard. When you need another machine, drop an agent on it. When you need an API, it's already running.

<br />

<table>
<tr>
<td width="33%" valign="top">

### Single Binary
`java -jar nimbus.jar` and you're running.
No supervisors, no brokers, no orchestrators.
The setup wizard takes care of the rest.

</td>
<td width="33%" valign="top">

### Scales With You
Start on one box. Drop an agent on a second
machine when you outgrow it. Services float
across nodes with live state sync.

</td>
<td width="33%" valign="top">

### API-First
Everything the console does, the REST API
does too. Live WebSocket events, bidirectional
service consoles, Prometheus metrics.

</td>
</tr>
</table>

<br />

---

## Features

<table>
<tr>
<td width="50%" valign="top">

**Platform & Servers**
- 9 server platforms — Paper, Purpur, Pufferfish, Leaf, Folia, Velocity, Forge, NeoForge, Fabric
- Modpack import from Modrinth & CurseForge with a single command
- Bedrock crossplay via auto-configured Geyser + Floodgate
- Version support from **1.8.8 → latest** with adaptive Via plugin chain
- Velocity-first — proxy list, forwarding, tab list, MOTD, chat, maintenance all auto-managed

**Scaling & Cluster**
- Smart auto-scaling with schedules, predictive warm-up, and player-count history
- Multi-node cluster with TLS, placement strategies, and failover
- Built-in TCP load balancer with health checks and circuit breaker
- Warm pools — pre-staged services ready to spin up in milliseconds
- State sync — services float across nodes without data loss

</td>
<td width="50%" valign="top">

**Management & Ops**
- Web Dashboard (Beta) at [dashboard.nimbuspowered.org](https://dashboard.nimbuspowered.org)
- Interactive JLine3 console with 34 commands and tab completion
- Remote CLI — same console experience from anywhere, over WebSocket
- Scheduled tar+zstd backups with GFS retention and SHA-256 manifests
- Stress testing — simulate thousands of players without real clients
- Auto-updates from GitHub Releases with changelog prompts

**Platform Features**
- Permissions — groups, tracks, prefixes, wildcards, audit log (or LuckPerms)
- Punishments — network-wide bans, mutes, warnings with auto-expiry
- Resource packs — URL-referenced or uploaded, stacked by scope
- REST API + WebSocket — 40+ endpoints, live events, Prometheus metrics
- Aikar's JVM flags + config tuning out of the box

</td>
</tr>
</table>

<br />

---

## Quick Start

Java 21, the latest release, and a launch script are installed automatically.

<table>
<tr>
<td width="50%" valign="top">

**Linux / macOS**

```bash
curl -fsSL https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install.sh | bash
```

</td>
<td width="50%" valign="top">

**Windows (PowerShell)**

```powershell
irm https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install.ps1 | iex
```

</td>
</tr>
</table>

Then:

```bash
nimbus
```

The setup wizard walks you through network name, proxy, Bedrock, permissions, server groups, and JAR downloads — and starts everything for you.

<sub>Full walkthrough: [Quick Start Guide](https://NimbusPowered.github.io/Nimbus/guide/quickstart.html)</sub>

<br />

<details>
<summary><b>Agent Nodes — add a worker machine</b></summary>

<br />

```bash
# Linux / macOS
curl -fsSL https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install-agent.sh | bash

# Windows (PowerShell)
irm https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install-agent.ps1 | iex
```

The installer prompts for controller address + cluster token and optionally creates a system service.

</details>

<details>
<summary><b>Remote CLI — manage a controller from your laptop</b></summary>

<br />

```bash
# Linux / macOS
curl -fsSL https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install-cli.sh | bash

# Windows (PowerShell)
irm https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/install-cli.ps1 | iex
```

Standalone JLine3 console, talks to the controller over REST + WebSocket.

</details>

<details>
<summary><b>Build from source</b></summary>

<br />

```bash
git clone https://github.com/NimbusPowered/Nimbus.git
cd Nimbus
./gradlew shadowJar

# Output: nimbus-core/build/libs/nimbus-core-<version>-all.jar
java -jar nimbus-core/build/libs/nimbus-core-<version>-all.jar
```

</details>

<br />

---

## Architecture

```
                 ┌─────────────────────────────────────────────────┐
                 │                    Controller                   │
    Console ─►   │   API · WebSocket · Scaling · Placement · DB    │
    Dashboard ─► │                                                 │
    Remote CLI ─►│   Modules: perms · display · scaling · players  │
                 │            punishments · resourcepacks · backup │
                 └───────────┬─────────────────────────┬───────────┘
                             │ TLS cluster             │ TCP LB
                             ▼                         ▼
                 ┌───────────────────────┐   ┌─────────────────────┐
                 │   Agent Node(s)       │   │  Velocity Proxies   │
                 │   Runs services       │   │  + Bridge plugin    │
                 └───────────┬───────────┘   └──────────┬──────────┘
                             │                          │
                             ▼                          ▼
                 ┌───────────────────────────────────────────────┐
                 │   Game Servers · Lobbies · Dedicated          │
                 │   Paper · Purpur · Fabric · Forge · NeoForge  │
                 └───────────────────────────────────────────────┘
```

<br />

### Project Layout

| Module | Role |
|--------|------|
| `nimbus-core` | Controller — console, API, scaling, cluster, load balancer |
| `nimbus-agent` | Remote worker node for multi-node deployments |
| `nimbus-protocol` | Shared cluster message types (controller ↔ agent) |
| `nimbus-cli` | Standalone Remote CLI — no Minecraft deps |
| `plugins/bridge` | Velocity plugin — hub commands, proxy sync, login gate |
| `plugins/sdk` | Server-side SDK (Spigot 1.8.8+ → Folia) |
| `plugins/perms` · `display` · `punishments` · `resourcepacks` | Feature plugins, auto-deployed |
| `modules/perms` · `scaling` · `players` · `display` · `punishments` · `resourcepacks` · `backup` | Controller-side modules |
| `dashboard` | Next.js web dashboard (Beta) |

<br />

---

## Configuration

One TOML file per group. Human-readable, hot-reloadable.

<details>
<summary><b>config/nimbus.toml</b> — main controller config</summary>

<br />

```toml
[network]
name = "MyNetwork"
bind = "0.0.0.0"

[controller]
max_memory = "10G"
max_services = 20

[api]
enabled = true
bind = "0.0.0.0"
port = 8080
token = ""                      # auto-generated on first run

[bedrock]
enabled = false                 # Geyser + Floodgate auto-configured

[cluster]
enabled = false
agent_port = 8443
placement_strategy = "least-services"

[loadbalancer]
enabled = false
port = 25565
strategy = "least-players"

[database]
type = "sqlite"                 # sqlite · mysql · postgresql
```

</details>

<details>
<summary><b>config/groups/lobby.toml</b> — a server group</summary>

<br />

```toml
[group]
name = "Lobby"
type = "DYNAMIC"
templates = ["base", "lobby"]
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
warm_pool_size = 1

[group.lifecycle]
restart_on_crash = true
max_restarts = 5
deploy_on_stop = false

[group.jvm]
optimize = true                 # Aikar's flags + config tuning
```

</details>

<br />

---

## Console

<table>
<tr><th width="18%">Category</th><th>Commands</th></tr>
<tr>
<td><b>Service</b></td>
<td><code>list</code> · <code>start</code> · <code>stop</code> · <code>restart</code> · <code>screen</code> · <code>exec</code> · <code>logs</code></td>
</tr>
<tr>
<td><b>Group</b></td>
<td><code>groups</code> · <code>info</code> · <code>create</code> · <code>import</code> · <code>update</code> · <code>static</code> · <code>dynamic</code></td>
</tr>
<tr>
<td><b>Network</b></td>
<td><code>status</code> · <code>players</code> · <code>send</code> · <code>maintenance</code></td>
</tr>
<tr>
<td><b>Permissions</b></td>
<td><code>perms group</code> · <code>perms user</code> · <code>perms track</code> · <code>perms audit</code></td>
</tr>
<tr>
<td><b>Cluster</b></td>
<td><code>cluster</code> · <code>nodes</code> · <code>lb</code></td>
</tr>
<tr>
<td><b>Backups</b></td>
<td><code>backup run</code> · <code>backup list</code> · <code>backup restore</code> · <code>backup verify</code></td>
</tr>
<tr>
<td><b>Stress</b></td>
<td><code>stress start</code> · <code>stress ramp</code> · <code>stress stop</code> · <code>stress status</code></td>
</tr>
<tr>
<td><b>System</b></td>
<td><code>api</code> · <code>reload</code> · <code>sessions</code> · <code>audit</code> · <code>shutdown</code></td>
</tr>
</table>

<br />

---

## API

```bash
# Health (public)
curl http://localhost:8080/api/health

# List services
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/services

# Start a new instance
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/services/start/BedWars

# Live event stream
wscat -c "ws://localhost:8080/api/events?token=$TOKEN"

# Prometheus metrics
curl http://localhost:8080/api/metrics
```

<sub>Full reference: [REST API Docs](https://NimbusPowered.github.io/Nimbus/reference/api.html) — 40+ endpoints, all error-coded.</sub>

<br />

---

## Tech Stack

| Layer | Choice |
|-------|--------|
| Language · Build | Kotlin 2.1 · Java 21 · Gradle + Shadow |
| Async | kotlinx-coroutines (no raw threads) |
| Config | ktoml |
| Console | JLine 3 |
| HTTP | Ktor Client (CIO) · Ktor Server (CIO + Netty for TLS cluster) |
| Database | Exposed ORM · SQLite / MySQL / PostgreSQL · HikariCP |
| Compression | Apache Commons Compress · zstd-jni (multi-threaded) |
| Dashboard | Next.js · shadcn/ui · Tailwind |

<br />

---

## Contributing

Issues, PRs, and feature requests are welcome. Nimbus is developed in the open — open an issue before starting on anything large so we can align on direction.

<br />

## License

MIT — see [LICENSE](LICENSE).

<br />

---

<div align="center">

<sub>Built for Minecraft network operators who'd rather read a config file than a Helm chart.</sub>

<br /><br />

[![Star History Chart](https://api.star-history.com/svg?repos=NimbusPowered/Nimbus&type=Date)](https://star-history.com/#NimbusPowered/Nimbus&Date)

</div>
