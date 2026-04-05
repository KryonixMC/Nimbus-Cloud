# Core Concepts

This page explains the fundamental building blocks of Nimbus. Understanding these concepts will help you configure and operate your network effectively.

## Groups

A **group** is a definition for a type of server. It specifies the software, version, resource limits, scaling rules, and lifecycle behavior. Groups are configured as TOML files in the `config/groups/` directory.

```
config/groups/
├── proxy.toml      # Velocity proxy
├── lobby.toml      # Lobby servers
└── bedwars.toml    # BedWars game servers
```

### Group Types

| Type | Behavior | Use Case |
|---|---|---|
| **STATIC** | Persistent instances. Data preserved across restarts. Stored in `services/static/`. | Proxy servers, build servers |
| **DYNAMIC** | Ephemeral instances. Created fresh from templates each time. Stored in `services/temp/`. | Lobbies, game servers, any scalable workload |

### Naming Convention

Group names use **PascalCase**: `Lobby`, `BedWars`, `SkyWars`, `Proxy`.

### Example Group Config

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
scale_threshold = 0.8
idle_timeout = 300

[group.lifecycle]
stop_on_empty = true
restart_on_crash = true
max_restarts = 5

[group.jvm]
optimize = true
```

## Services

A **service** is a running instance of a group. When Nimbus starts a service, it creates a process from the group's template, assigns a port, and monitors its lifecycle.

### Naming

Services are named `<GroupName>-<N>`, where N is an incrementing number:

- `Proxy-1`, `Lobby-1`, `Lobby-2`, `BedWars-1`, `BedWars-3`

### Service States

Every service moves through a lifecycle of states:

```
PREPARING → STARTING → READY → STOPPING → STOPPED
                         │
                         └──→ CRASHED
```

| State | Description |
|---|---|
| **PREPARING** | Template files are being copied to the service directory. |
| **STARTING** | JVM process has launched. Nimbus watches stdout for the "Done" pattern. |
| **READY** | Server is fully started and accepting player connections. |
| **STOPPING** | Graceful shutdown has been initiated. |
| **STOPPED** | Process has exited cleanly. |
| **CRASHED** | Process exited unexpectedly. May auto-restart based on lifecycle config. |

### Static vs Dynamic Lifecycle

**Static services** (like a proxy) keep their data between restarts. Their files live in `services/static/Proxy-1/` and persist across Nimbus restarts.

**Dynamic services** (like lobbies and game servers) are created fresh each time from their template. When stopped, their `services/temp/` directory is cleaned up. This ensures every new instance starts from a known good state.

### Custom States

Services can report **custom states** via the Nimbus SDK (e.g., `INGAME`, `ENDING`, `WAITING`). A service with a custom state is excluded from routing — the scaling engine won't count it toward available capacity, and the proxy won't send new players to it.

::: tip
Custom states are perfect for game servers. Set the state to `INGAME` when a match starts, and the proxy will stop sending new players while the scaling engine starts fresh instances for the queue.
:::

## Templates

Templates define what files get copied into a service instance when it starts. Nimbus uses a layered template system:

### Template Layers

```
templates/
├── global/            # Layer 1: All backend servers
│   └── plugins/
│       └── nimbus-sdk.jar
├── global_proxy/      # Layer 1: All proxy servers
│   └── plugins/
│       ├── nimbus-bridge.jar
│       └── nimbus-bridge/
│           └── bridge.json
└── lobby/             # Layer 2: Group-specific
    ├── server.jar
    ├── plugins/
    │   └── viabackwards.jar
    └── server.properties
```

When Nimbus starts a `Lobby` service, it merges templates in order:

1. **`templates/global/`** — Copied first. Contains files shared across all backend servers (Paper, Purpur, etc.). The nimbus-sdk plugin is auto-deployed here.
2. **`templates/lobby/`** — Copied second, overwriting any conflicts. Contains the group-specific server JAR, plugins, and configuration.

For proxy services, `templates/global_proxy/` is used instead of `templates/global/`.

::: info Auto-deployed plugins
Nimbus automatically deploys `nimbus-bridge.jar` to `global_proxy/plugins/` and `nimbus-sdk.jar` to `global/plugins/`. When [Bedrock support](/guide/proxy-setup#bedrock-support) is enabled, Geyser is deployed to `global_proxy/plugins/` and Floodgate to both `global/plugins/` and `global_proxy/plugins/`. You don't need to manage these manually.
:::

### What to Put in Templates

| Location | Contents |
|---|---|
| `templates/global/plugins/` | Plugins shared across all backend servers |
| `templates/global_proxy/plugins/` | Plugins shared across all proxies |
| `templates/<group>/` | Server JAR (auto-downloaded), group-specific plugins, `server.properties`, world files, etc. |

## Scaling

Nimbus automatically manages the number of running instances for each dynamic group based on player count.

### How It Works

The **Scaling Engine** runs on a configurable interval (default: every 5 seconds). For each dynamic group, it:

1. **Pings** all ready services to get current player counts
2. **Evaluates scale-up rules** — Do we need more capacity?
3. **Evaluates scale-down rules** — Are there idle instances to remove?

### Scale Up

A new instance is started when:

- Total players across routable instances exceed `players_per_instance * routable_count * scale_threshold`
- The current instance count is below `max_instances`

**Example:** With `players_per_instance = 16`, `scale_threshold = 0.8`, and 1 instance holding 13 players, Nimbus sees 13 > (16 * 1 * 0.8 = 12.8) and starts a second instance.

### Scale Down

An instance is stopped when:

- It has **zero players**
- It has been idle longer than `idle_timeout` seconds (0 = never scale down idle)
- The group still has more than `min_instances` running

::: warning Lobby scaling
For lobbies, set `idle_timeout = 0` to prevent them from being scaled down when empty. Lobbies should always be available for players to connect to.
:::

### Scaling Config Reference

| Setting | Default | Description |
|---|---|---|
| `min_instances` | 1 | Minimum number of instances always running |
| `max_instances` | 4 | Maximum number of instances the engine will create |
| `players_per_instance` | 40 | Target players per instance for capacity calculation |
| `scale_threshold` | 0.8 | Percentage of capacity that triggers scale-up (0.0 - 1.0) |
| `idle_timeout` | 0 | Seconds before an empty instance is stopped (0 = never) |

## Server Software

Nimbus auto-downloads server JARs from official APIs. The following platforms are supported:

### Vanilla & Plugin Servers

| Software | Type | API Source |
|---|---|---|
| **Paper** | Minecraft server | PaperMC API |
| **Pufferfish** | Minecraft server (Paper fork, high-performance) | [Pufferfish CI](https://ci.pufferfish.host) |
| **Purpur** | Minecraft server (Paper fork) | Purpur API |
| **Folia** | Minecraft server (regionized multithreading) | PaperMC API |
| **Velocity** | Proxy | PaperMC API |
| **Custom** | Any | You provide the JAR manually |

### Modded Servers

| Software | Type | Auto-download | Proxy Forwarding |
|---|---|---|---|
| **Forge** | Modded Minecraft | Installer from Forge Maven | [proxy-compatible-forge](https://modrinth.com/mod/proxy-compatible-forge) (auto-installed) |
| **Fabric** | Modded Minecraft | Launcher JAR from Fabric Meta | [FabricProxy-Lite](https://modrinth.com/mod/fabricproxy-lite) + Fabric API (auto-installed) |
| **NeoForge** | Modded Minecraft | Installer from NeoForge Maven | [proxy-compatible-forge](https://modrinth.com/mod/proxy-compatible-forge) (auto-installed) |
| **Quilt** | Modded Minecraft | Via Fabric compatibility | FabricProxy-Lite (auto-installed) |

::: tip Cardboard for Fabric (BETA)
Fabric servers can optionally install [Cardboard](https://modrinth.com/mod/cardboard) to run Bukkit/Paper plugins alongside Fabric mods. During group creation, Nimbus offers to download Cardboard and its dependency [iCommon](https://modrinth.com/mod/icommon) automatically. This is experimental — not all plugins will work correctly.
:::

::: tip Folia
Folia uses regionized multithreading which breaks most Bukkit/Paper plugins. The Nimbus plugins (SDK, NimbusPerms, NimbusDisplay) are **fully Folia-compatible** using region-aware scheduling.
:::

Modded servers are first-class citizens in Nimbus. When you create a Forge, Fabric, or NeoForge group, Nimbus:

1. **Downloads the modloader** — Runs the installer automatically (Forge/NeoForge) or fetches the launcher JAR (Fabric)
2. **Installs the proxy forwarding mod** — Downloads the correct mod from Modrinth so players can connect through Velocity
3. **Configures forwarding** — Sets modern or legacy mode in the mod's config, copies the forwarding secret
4. **Handles startup** — Uses the correct launch command (`@libraries/...` args file for Forge/NeoForge, `-jar server.jar` for Fabric)

You can also import entire modpacks from Modrinth with a single command -- see [Modpack Import](/guide/modpacks).

::: tip EULA
Nimbus automatically accepts the Minecraft EULA for Paper, Pufferfish, Purpur, and Folia servers. You don't need to manually edit `eula.txt`.
:::

### Via Plugins

For cross-version compatibility, Nimbus can download and manage Via plugins on **backend servers only** (never on the proxy):

- **ViaVersion** — Lets players with newer Minecraft clients join older servers
- **ViaBackwards** — Lets players with older clients join newer servers (requires ViaVersion — auto-included if missing)
- **ViaRewind** — Extends backwards support down to 1.7/1.8 clients (requires ViaBackwards)

## Automatic JDK Management {#automatic-jdk-management}

Different Minecraft versions require different Java versions. Nimbus handles this automatically -- you don't need to install multiple JDKs manually.

### Java version mapping

| Minecraft Version | Required Java | Max Java |
|---|---|---|
| 1.16.x and below | Java 16 | Java 16 |
| 1.17.x | Java 16 | No limit |
| 1.18.x - 1.20.4 | Java 17 | No limit |
| 1.20.5 - 1.21+ | Java 21 | No limit |
| 26.x+ (new scheme) | Java 25 | No limit |
| Velocity (all versions) | Java 21 | No limit |

### How it works

When Nimbus needs to start a server, it resolves the correct Java executable through several layers:

1. **Per-group override** — If the group config has a `java_path` set, that is used directly
2. **Configured paths** — Paths set in the `[java]` section of [`nimbus.toml`](/config/nimbus-toml#java)
3. **Auto-detection** — Nimbus scans for installed JDKs in common locations:
   - Nimbus's own `jdks/` cache directory
   - Environment variables (`JAVA_16_HOME`, `JAVA_17_HOME`, `JAVA_21_HOME`, etc.)
   - `JAVA_HOME`
   - System directories (`/usr/lib/jvm`, `~/.sdkman/candidates/java`, `~/.jdks`, etc.)
4. **Auto-download** — If no compatible JDK is found, Nimbus downloads one from [Eclipse Adoptium](https://adoptium.net/) (Temurin) automatically

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-dim">[12:00:01]</span> <span class="t-cyan">ℹ</span> Detected Java installations: Java 17, Java 21
<span class="t-dim">[12:00:05]</span> <span class="t-yellow">⚠</span> No Java 16 found — downloading automatically...
<span class="t-dim">[12:00:08]</span> <span class="t-cyan">ℹ</span> Downloaded Java 16 (187.3 MB), extracting...
<span class="t-dim">[12:00:12]</span> <span class="t-green">✓</span> Java 16 installed to jdks/java-16/bin/java
</pre>
</div>

Downloaded JDKs are cached in the `jdks/` directory inside your Nimbus installation. They persist across restarts and are reused automatically.

::: tip
For most modern setups (1.20.5+), you only need Java 21. Nimbus will auto-detect it from your system PATH. The auto-download feature is most useful when running legacy servers (1.8.x - 1.16.x) alongside modern ones. Java 16 is the minimum supported runtime for all Nimbus plugins.
:::

## Ports

Nimbus automatically allocates ports for all services:

| Port Range | Used For |
|---|---|
| **25565** | Proxy (Velocity). This is the port players connect to. |
| **30000 - 39999** | Backend servers (Paper, Purpur, etc.). Allocated sequentially. |

Ports are allocated when a service starts and released when it stops. Backend ports are checked for availability before assignment — if a port is already in use by another process, Nimbus skips it.

::: info
Players never connect to backend ports directly. They connect to the proxy on port 25565, and the proxy routes them to the appropriate backend server.
:::

## Shutdown Order

When Nimbus shuts down (via the `shutdown` command or a system signal), it stops services in a specific order to avoid disrupting players:

1. **Game servers** — Dynamic instances are stopped first
2. **Lobbies** — Lobby instances are stopped next
3. **Proxies** — The proxy is stopped last, after all backends are down

This ensures players get kicked cleanly from game servers before the proxy goes down, rather than having their connections silently dropped.
