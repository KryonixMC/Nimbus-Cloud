# Console Commands

Nimbus provides an interactive console with tab completion, command history, and ANSI-colored output. Type `help` or `?` to see all available commands.

## Service Management

### `list`

Show all running services with status, port, player count, and uptime.

**Syntax:** `list [group]`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> list
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Services</span> ──────────────────────────────────────</span>
<span class="t-bold t-bright-cyan">NAME            GROUP       STATE           PORT    PLAYERS  PID     UPTIME</span>
<span class="t-dim">──────────────────────────────────────────────────────────────────────────────</span>
<span class="t-bold">Proxy-1</span>         Proxy       <span class="t-green">● READY</span>          25565/19132   36       48190   2h 15m
<span class="t-bold">Lobby-1</span>         Lobby       <span class="t-green">● READY</span>          30001   12       48201   2h 15m
<span class="t-bold">Lobby-2</span>         Lobby       <span class="t-green">● READY</span>          30002   8        48215   1h 42m
<span class="t-bold">BedWars-1</span>       BedWars     <span class="t-green">● READY</span>          30003   16       48230   0h 55m
<span class="t-bold">BedWars-2</span>       BedWars     <span class="t-yellow">● STARTING</span>       30004   0        48245   0m 01s
<span class="t-dim">5 service(s)</span>
</pre>
</div>

::: tip
Filter by group name: `list Lobby` shows only Lobby instances. When Bedrock support is enabled, proxy services show both TCP and UDP ports (e.g., `25565/19132`).
:::

**Tab completion:** Group names.

---

### `start`

Start a new instance of a server group.

**Syntax:** `start <group>`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> start BedWars
<span class="t-cyan">ℹ</span> Starting new instance for group 'BedWars'...
<span class="t-green">✓ Service start initiated for group 'BedWars'.</span>
</pre>
</div>

::: warning
Fails if the group has reached its `max_instances` limit or the server JAR is unavailable.
:::

**Tab completion:** Group names.

---

### `stop`

Gracefully stop a running service. Sends the Minecraft `stop` command to the process.

**Syntax:** `stop <service>`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> stop BedWars-2
<span class="t-cyan">ℹ</span> Stopping service 'BedWars-2'...
<span class="t-green">✓ Service 'BedWars-2' stop initiated.</span>
</pre>
</div>

**Tab completion:** Running service names.

---

### `restart`

Stop a service and start a new instance in its place.

**Syntax:** `restart <service>`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> restart Lobby-1
<span class="t-cyan">ℹ</span> Restarting service 'Lobby-1'...
<span class="t-green">✓ Service 'Lobby-1' restart initiated.</span>
</pre>
</div>

**Tab completion:** Running service names.

---

### `purge`

Force-kill a service process, clean up crashed services, or kill a process by PID. Use this when a service is stuck and `stop` doesn't work.

**Syntax:** `purge <service|crashed|pid:<pid>>`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> purge crashed
<span class="t-yellow">⚠</span> Purging 2 crashed service(s)...
<span class="t-green">  Purged 'BedWars-3'</span>
<span class="t-green">  Purged 'BedWars-5'</span>
<span class="t-green">✓ Purged 2/2 crashed service(s).</span>
</pre>
</div>

| Argument | Description |
|---|---|
| `<service>` | Force-kill and remove a specific service |
| `crashed` | Remove all services in CRASHED state |
| `pid:<pid>` | Kill any process by its system PID |

::: warning
`purge` force-kills processes immediately without graceful shutdown. Use `stop` first whenever possible.
:::

**Tab completion:** Running service names.

---

### `screen`

Attach to a service's live console output. You can type commands directly into the server.

**Syntax:** `screen <service>`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus — screen Lobby-1</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> screen Lobby-1
<span class="t-cyan">ℹ</span> Attached to Lobby-1 <span class="t-dim">(ESC or Ctrl+Q to detach)</span>
<span class="t-dim">────────────────────────────────────────────────────────</span>
<span class="t-dim">[14:23:01 INFO]:</span> Done (2.341s)! For help, type "help"
<span class="t-dim">[14:23:05 INFO]:</span> Player Steve joined the game
<span class="t-yellow">&gt;</span> say Hello from Nimbus!
<span class="t-dim">[14:23:12 INFO]:</span> [Server] Hello from Nimbus!
<span class="t-dim">────────────────────────────────────────────────────────</span>
<span class="t-cyan">ℹ</span> Detached from Lobby-1
</pre>
</div>

Press **ESC** or **Ctrl+Q** to detach and return to the Nimbus console.

::: warning
Cannot attach to services in `STOPPED` or `PREPARING` state.
:::

**Tab completion:** Running service names.

---

### `exec`

Execute a command on a service's stdin without attaching to its console.

**Syntax:** `exec <service> <command...>`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> exec Lobby-1 say Restarting in 5 minutes!
<span class="t-green">✓ Sent to Lobby-1:</span> <span class="t-dim">say Restarting in 5 minutes!</span>
</pre>
</div>

**Tab completion:** Running service names (first argument).

---

### `logs`

Show recent log output from a service's `logs/latest.log` file.

**Syntax:** `logs <service> [lines]`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> logs BedWars-1 20
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Logs: BedWars-1</span> ────────────────────────────────</span>
<span class="t-dim">Last 20 line(s) from services/BedWars-1/logs/latest.log</span>
<span class="t-dim">────────────────────────────────────────────────────────</span>
<span class="t-dim">[14:23:01 INFO]: Player Alex joined the game</span>
<span class="t-dim">[14:23:05 INFO]: Player Steve joined the game</span>
<span class="t-dim">...</span>
<span class="t-dim">────────────────────────────────────────────────────────</span>
<span class="t-dim">20 of 1482 line(s)</span>
</pre>
</div>

Defaults to **50 lines** if not specified. If the log file doesn't exist, Nimbus suggests using `screen` for live output.

**Tab completion:** Running service names.

---

### `players`

List all connected players across services. Queries each READY service via Server List Ping.

**Syntax:** `players [service]`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> players
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Players</span> ───────────────────────────────────────</span>
<span class="t-bold t-bright-cyan">PLAYER      SERVICE       SERVER</span>
<span class="t-dim">──────────────────────────────────────────────────</span>
Alex        BedWars-1     BedWars
Notch       Lobby-1       Lobby
Steve       BedWars-1     BedWars
<span class="t-dim">3 player(s) online</span>
</pre>
</div>

Pass a service name to filter: `players Lobby-1`.

**Tab completion:** Running service names.

---

## Group Management

### `groups`

List all configured server groups with software, version, and instance counts.

**Syntax:** `groups`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> groups
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Groups</span> ────────────────────────────────────────</span>
<span class="t-bold t-bright-cyan">NAME        TYPE      SOFTWARE    VERSION    MEMORY  INSTANCES  MIN/MAX</span>
<span class="t-dim">──────────────────────────────────────────────────────────────────────────</span>
Proxy       STATIC    VELOCITY    3.4.0      512M    1          1/1
Lobby       DYNAMIC   PAPER       1.21.4     1G      2          2/4
BedWars     DYNAMIC   PAPER       1.21.4     2G      2          1/8
<span class="t-dim">3 group(s)</span>
</pre>
</div>

---

### `info`

Show detailed configuration and runtime state for a group.

**Syntax:** `info <group>`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> info BedWars
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Group: BedWars</span> ─────────────────────────────────</span>
  <span class="t-dim">Type</span>                  <span class="t-cyan">DYNAMIC</span>
  <span class="t-dim">Software</span>              <span class="t-cyan">PAPER</span>
  <span class="t-dim">Version</span>               1.21.4
  <span class="t-dim">Template</span>              <span class="t-dim">default</span>
<span class="t-cyan">▸ <span class="t-bold" style="color:#c0caf5">Resources</span></span>
  <span class="t-dim">Memory</span>                2G
  <span class="t-dim">Max Players</span>           16
<span class="t-cyan">▸ <span class="t-bold" style="color:#c0caf5">Scaling</span></span>
  <span class="t-dim">Min Instances</span>         1
  <span class="t-dim">Max Instances</span>         8
  <span class="t-dim">Players/Instance</span>      16
  <span class="t-dim">Scale Threshold</span>       80%
  <span class="t-dim">Idle Timeout</span>          300000ms
<span class="t-cyan">▸ <span class="t-bold" style="color:#c0caf5">Lifecycle</span></span>
  <span class="t-dim">Stop on Empty</span>         <span class="t-green">yes</span>
  <span class="t-dim">Restart on Crash</span>      <span class="t-green">yes</span>
  <span class="t-dim">Max Restarts</span>          5
<span class="t-cyan">▸ <span class="t-bold" style="color:#c0caf5">JVM</span></span>
  <span class="t-dim">Optimize</span>              <span class="t-green">Aikar's Flags + Config Tuning</span>
<span class="t-cyan">▸ <span class="t-bold" style="color:#c0caf5">Runtime</span></span>
  <span class="t-dim">Running Instances</span>     <span class="t-green">2</span>
  <span class="t-dim">Total Players</span>         <span class="t-bold">24</span>
</pre>
</div>

**Tab completion:** Group names.

---

### `create`

Launch an interactive wizard to create a new server group. Clears the screen and walks through:

1. Group name
2. Server software (Paper, Pufferfish, Purpur, Folia, Forge, NeoForge, Fabric, Custom, or Modpack import)
3. Minecraft version (with tab completion from live version lists)
4. Modloader version (for Forge/NeoForge/Fabric only)
5. Static vs. dynamic mode
6. Instance counts and memory
7. Via plugins (ViaVersion/ViaBackwards/ViaRewind -- Paper/Purpur/Folia only)
8. Automatic JAR download and template setup
9. Optional immediate instance start

**Syntax:** `create`

#### Example: Paper game server

The most common case -- a Paper backend with Via plugin support.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus — create wizard</span>
  </div>
  <pre class="terminal-body">
<span class="t-bold">Create New Group</span>
Group name: <span class="t-bold">SkyWars</span>
<span class="t-dim">Available server software:</span>
  <span class="t-cyan">paper</span>       — Paper (optimized vanilla, plugins)
  <span class="t-cyan">pufferfish</span>  — Pufferfish (Paper fork, performance)
  <span class="t-cyan">purpur</span>      — Purpur (Paper fork, extra features)
  <span class="t-cyan">folia</span>       — Folia (regionized multithreading, 1.19.4+)
  <span class="t-cyan">forge</span>       — Forge (mods, auto-installs)
  <span class="t-cyan">neoforge</span>    — NeoForge (modern Forge fork)
  <span class="t-cyan">fabric</span>      — Fabric (lightweight mods)
  <span class="t-cyan">modpack</span>     — Import a Modrinth modpack
  <span class="t-cyan">custom</span>      — Custom JAR (bring your own)
Server software <span class="t-dim">[paper]</span><span class="t-dim">:</span> <span class="t-bold">paper</span>
<span class="t-dim">Fetching available versions...</span> <span class="t-green">✓</span>
<span class="t-dim">Stable: 1.21.4  1.21.3  1.21.1  1.20.6  1.20.4  1.20.2  1.20.1  1.19.4  ...</span>
Minecraft version <span class="t-dim">[1.21.4]</span><span class="t-dim">:</span> <span class="t-bold">1.21.4</span>
<span class="t-dim">Static services keep their data (world, configs) across restarts.</span>
<span class="t-dim">Dynamic services start fresh from the template every time.</span>
Static service <span class="t-dim">[y/N]</span><span class="t-dim">:</span> <span class="t-bold">n</span>
Min instances <span class="t-dim">[1]</span><span class="t-dim">:</span> <span class="t-bold">1</span>
Max instances <span class="t-dim">[4]</span><span class="t-dim">:</span> <span class="t-bold">8</span>
Memory per instance <span class="t-dim">[1G]</span><span class="t-dim">:</span> <span class="t-bold">2G</span>
<span class="t-bold">Protocol support:</span>
<span class="t-dim">ViaVersion allows newer clients, ViaBackwards allows older clients.</span>
Install <span class="t-cyan">ViaVersion</span>? <span class="t-dim">[y/N]</span><span class="t-dim">:</span> <span class="t-bold">n</span>
Install <span class="t-cyan">ViaBackwards</span>? <span class="t-dim">(older clients can join)</span> <span class="t-dim">[Y/n]</span><span class="t-dim">:</span> <span class="t-bold">y</span>
Install <span class="t-cyan">ViaRewind</span>? <span class="t-dim">(1.7/1.8 clients)</span> <span class="t-dim">[y/N]</span><span class="t-dim">:</span> <span class="t-bold">n</span>
<span class="t-green">✓</span> Via plugins: ViaBackwards
<span class="t-bold">Downloading files...</span>
<span class="t-dim">↓</span> Paper 1.21.4 <span class="t-green">✓</span>
<span class="t-dim">↓</span> ViaBackwards <span class="t-green">✓</span>
<span class="t-green">✓</span> config/groups/skywars.toml
<span class="t-green">✓</span> Group configs reloaded
<span class="t-green t-bold">Group 'SkyWars' created!</span>
Start an instance now? <span class="t-dim">[Y/n]</span><span class="t-dim">:</span> <span class="t-bold">y</span>
<span class="t-green">✓</span> Service start initiated.
</pre>
</div>

#### Example: Fabric modded server

For Fabric, Forge, and NeoForge, the wizard adds a **modloader version** prompt after the Minecraft version. It also auto-downloads the appropriate proxy forwarding mod -- FabricProxy-Lite for Fabric, or a Forge/NeoForge forwarding mod for Forge-based servers. Via plugin prompts are skipped since modded servers don't use them.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus — create wizard (modded)</span>
  </div>
  <pre class="terminal-body">
<span class="t-bold">Create New Group</span>
Group name: <span class="t-bold">Survival</span>
<span class="t-dim">Available server software:</span>
  <span class="t-cyan">paper</span>       — Paper (optimized vanilla, plugins)
  <span class="t-cyan">pufferfish</span>  — Pufferfish (Paper fork, performance)
  <span class="t-cyan">purpur</span>      — Purpur (Paper fork, extra features)
  <span class="t-cyan">folia</span>       — Folia (regionized multithreading, 1.19.4+)
  <span class="t-cyan">forge</span>       — Forge (mods, auto-installs)
  <span class="t-cyan">neoforge</span>    — NeoForge (modern Forge fork)
  <span class="t-cyan">fabric</span>      — Fabric (lightweight mods)
  <span class="t-cyan">modpack</span>     — Import a Modrinth modpack
  <span class="t-cyan">custom</span>      — Custom JAR (bring your own)
Server software <span class="t-dim">[paper]</span><span class="t-dim">:</span> <span class="t-bold">fabric</span>
<span class="t-dim">Fetching available versions...</span> <span class="t-green">✓</span>
<span class="t-dim">Stable: 1.21.4  1.21.3  1.21.1  1.20.6  1.20.4  ...</span>
Minecraft version <span class="t-dim">[1.21.4]</span><span class="t-dim">:</span> <span class="t-bold">1.21.4</span>
<span class="t-dim">Fetching modloader versions...</span> <span class="t-green">✓</span>
<span class="t-dim">Available: 0.16.9  0.16.8  0.16.7  0.16.6  0.16.5  ...</span>
Modloader version <span class="t-dim">[0.16.9]</span><span class="t-dim">:</span> <span class="t-bold">0.16.9</span>
<span class="t-dim">Static services keep their data (world, configs) across restarts.</span>
<span class="t-dim">Dynamic services start fresh from the template every time.</span>
Static service <span class="t-dim">[y/N]</span><span class="t-dim">:</span> <span class="t-bold">y</span>
Min instances <span class="t-dim">[1]</span><span class="t-dim">:</span> <span class="t-bold">1</span>
Max instances <span class="t-dim">[1]</span><span class="t-dim">:</span> <span class="t-bold">1</span>
Memory per instance <span class="t-dim">[1G]</span><span class="t-dim">:</span> <span class="t-bold">4G</span>
<span class="t-bold">Downloading files...</span>
<span class="t-dim">↓</span> Fabric 0.16.9 for MC 1.21.4 <span class="t-green">✓</span>
<span class="t-dim">↓</span> FabricProxy-Lite <span class="t-green">✓</span>
<span class="t-green">✓</span> config/groups/survival.toml
<span class="t-green">✓</span> Group configs reloaded
<span class="t-green t-bold">Group 'Survival' created!</span>
Start an instance now? <span class="t-dim">[Y/n]</span><span class="t-dim">:</span> <span class="t-bold">y</span>
<span class="t-green">✓</span> Service start initiated.
</pre>
</div>

::: tip
Press **Ctrl+C** at any point to cancel the wizard. Selecting `modpack` as the software type redirects to the [`import`](#import) wizard instead.
:::

---

### `import`

Import a Modrinth modpack as a new server group. Clears the screen and walks through resolving the modpack, displaying its info, downloading the modloader, mods, configs/overrides, and proxy forwarding mods.

**Syntax:** `import <url|slug|path.mrpack>`

Accepts:
- Modrinth URLs: `import https://modrinth.com/modpack/adrenaserver`
- Modrinth slugs: `import adrenaserver`
- Local `.mrpack` files: `import /path/to/modpack.mrpack`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus — import modpack</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> import adrenaserver
<span class="t-bold">Import Modpack</span>
<span class="t-dim">Resolving modpack...</span> <span class="t-green">✓</span>
<span class="t-bold">Adrenaserver</span> <span class="t-dim">v1.2.0</span>
<span class="t-dim">MC 1.21.4 · FABRIC 0.16.9</span>
<span class="t-dim">42 server mods (18 client-only skipped)</span>
Group name <span class="t-dim">[Adrenaserver]</span><span class="t-dim">:</span> <span class="t-bold">Adrenaserver</span>
<span class="t-dim">Static services keep their data (world, configs) across restarts.</span>
<span class="t-dim">Dynamic services start fresh from the template every time.</span>
Static service <span class="t-dim">[Y/n]</span><span class="t-dim">:</span> <span class="t-bold">y</span>
Memory per instance <span class="t-dim">[2G]</span><span class="t-dim">:</span> <span class="t-bold">4G</span>
Min instances <span class="t-dim">[1]</span><span class="t-dim">:</span> <span class="t-bold">1</span>
Max instances <span class="t-dim">[1]</span><span class="t-dim">:</span> <span class="t-bold">1</span>
<span class="t-bold">Installing...</span>
<span class="t-dim">↓</span> FABRIC 0.16.9 <span class="t-green">✓</span>
<span class="t-dim">↓</span> Mods 42/42 <span class="t-dim">lithium-fabric-0.14.3-mc1.21.4.jar</span>
<span class="t-green">✓</span> 42 mods downloaded
<span class="t-dim">↓</span> Configs & overrides <span class="t-green">✓</span>
<span class="t-dim">↓</span> Proxy mods <span class="t-green">✓</span>
<span class="t-green">✓</span> config/groups/adrenaserver.toml
<span class="t-green">✓</span> Group configs reloaded
<span class="t-green t-bold">Modpack 'Adrenaserver' imported as group 'Adrenaserver'!</span>
Start an instance now? <span class="t-dim">[Y/n]</span><span class="t-dim">:</span> <span class="t-bold">y</span>
<span class="t-green">✓</span> Service start initiated.
</pre>
</div>

The wizard automatically handles proxy forwarding -- FabricProxy-Lite for Fabric modpacks, or the appropriate Forge/NeoForge forwarding mod. Client-only mods are detected from the modpack index and skipped during installation.

---

### `update`

Update a group's server software or Minecraft version. Supports both direct and interactive modes. Includes compatibility checks that prevent incompatible switches (e.g., plugin servers to modded, Forge to Fabric).

**Syntax:** `update <group> [version <ver>] [software <sw> [<ver>]]`

#### Update Minecraft version

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> update Lobby version 1.21.5
<span class="t-cyan">ℹ</span> Updating Lobby: 1.21.4 -> 1.21.5
<span class="t-dim">Downloading paper 1.21.5...</span> <span class="t-green">ok</span>
<span class="t-green">ok</span> Configs reloaded
<span class="t-green t-bold">Updated 'Lobby' to version 1.21.5.</span>
</pre>
</div>

#### Switch server software

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> update Lobby software purpur
<span class="t-cyan">ℹ</span> Updating Lobby: PAPER 1.21.4 -> PURPUR 1.21.4
<span class="t-dim">Downloading purpur 1.21.4...</span> <span class="t-green">ok</span>
<span class="t-green">ok</span> Configs reloaded
<span class="t-green t-bold">Updated 'Lobby' to PURPUR 1.21.4.</span>
</pre>
</div>

#### Switch software and version at once

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> update Lobby software purpur 1.21.5
<span class="t-cyan">ℹ</span> Updating Lobby: PAPER 1.21.4 -> PURPUR 1.21.5
<span class="t-dim">Downloading purpur 1.21.5...</span> <span class="t-green">ok</span>
<span class="t-green">ok</span> Configs reloaded
<span class="t-green t-bold">Updated 'Lobby' to PURPUR 1.21.5.</span>
</pre>
</div>

#### Interactive mode

Running `update <group>` without subcommands opens an interactive wizard:

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus — update wizard</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> update Lobby
<span class="t-bold">Update Group: Lobby</span>

<span class="t-dim">Current: PAPER 1.21.4</span>

What to update? <span class="t-dim">[version]</span><span class="t-dim">:</span> <span class="t-bold">software</span>
<span class="t-dim">Compatible software for PAPER:</span>
  <span class="t-cyan">purpur</span>
<span class="t-dim">Fetching available versions...</span> <span class="t-green">ok</span>
<span class="t-dim">Available: 1.21.4  1.21.3  1.21.1  1.20.6  ...</span>
Minecraft version <span class="t-dim">[1.21.4]</span><span class="t-dim">:</span> <span class="t-bold">1.21.4</span>

<span class="t-cyan">ℹ</span> Updating Lobby: PAPER 1.21.4 -> PURPUR 1.21.4
<span class="t-dim">Downloading purpur 1.21.4...</span> <span class="t-green">ok</span>
<span class="t-green">ok</span> Configs reloaded
<span class="t-green t-bold">Updated 'Lobby' to PURPUR 1.21.4.</span>
</pre>
</div>

#### Compatibility rules

The `update` command enforces compatibility between server software families to prevent broken servers:

| Switch | Allowed | Reason |
|--------|---------|--------|
| Paper ↔ Pufferfish ↔ Purpur ↔ Folia | Yes | Same plugin API family (Paper forks). Note: most plugins won't work on Folia. |
| Forge ↔ NeoForge | Yes (with warning) | Similar but diverging mod ecosystems |
| Paper/Pufferfish/Purpur → Forge/Fabric/NeoForge | **No** | Plugins and mods are incompatible |
| Forge/NeoForge → Fabric | **No** | Completely different mod formats |
| Fabric → Forge/NeoForge | **No** | Completely different mod formats |
| Any ↔ Velocity | **No** | Proxy vs game server |
| Any ↔ Custom | **No** | Cannot auto-resolve custom JARs |

::: warning
Running services are **not** automatically restarted after an update. Restart them manually to apply the changes.
:::

**Tab completion:** Group names → `version`/`software` → software names (for `software` subcommand).

---

### `static`

Convert a group or individual service to static mode. Static services preserve their working directory across restarts.

**Syntax:** `static group <name>` | `static service <name>`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> static group BedWars
<span class="t-green">✓ Group 'BedWars' is now STATIC.</span>
<span class="t-dim">New services will preserve their working directory across restarts.</span>
<span class="t-yellow">⚠ 2 running service(s) are still dynamic. Use 'static service &lt;name&gt;' to convert them individually.</span>
</pre>
</div>

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> static service BedWars-1
<span class="t-cyan">ℹ</span> Converting 'BedWars-1' to static <span class="t-dim">(copying working directory)</span>...
<span class="t-green">✓ Service 'BedWars-1' is now static.</span>
<span class="t-dim">Working directory will be preserved when the service stops.</span>
</pre>
</div>

**Tab completion:** `group`/`service` subcommands, then group or service names.

---

### `dynamic`

Set a group back to dynamic mode. Dynamic services start fresh from the template each time.

**Syntax:** `dynamic <group>`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> dynamic BedWars
<span class="t-green">✓ Group 'BedWars' is now DYNAMIC.</span>
<span class="t-dim">New services will use temporary directories that are cleaned up on stop.</span>
</pre>
</div>

**Tab completion:** Group names.

---

## Permission Management

The `perms` command manages Nimbus's built-in permission system. All subcommands support tab completion.

**Syntax:** `perms <group|user|track|audit|reload> [subcommand] [args]`

### Group Subcommands

#### `perms group list`

List all permission groups with their default status, priority, and parent groups.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms group list
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Permission Groups</span> ─────────────────────────────</span>
<span class="t-bold t-bright-cyan">NAME        DEFAULT    PRIORITY  PREFIX       PERMISSIONS  PARENTS</span>
<span class="t-dim">──────────────────────────────────────────────────────────────────────</span>
Admin       no         100       <span class="t-red">[Admin]</span>      12           Moderator
Moderator   no         50        <span class="t-yellow">[Mod]</span>        5            Default
Default     <span class="t-green">yes</span>        0         <span class="t-dim">-</span>            3            <span class="t-dim">-</span>
<span class="t-dim">3 group(s)</span>
</pre>
</div>

#### `perms group info <name>`

Show full details of a permission group including all permissions.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms group info Admin
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Permission Group: Admin</span> ──────────────────────</span>
  <span class="t-dim">Default:</span>   no
  <span class="t-dim">Priority:</span>  100
  <span class="t-dim">Prefix:</span>    <span class="t-red">[Admin]</span>
  <span class="t-dim">Suffix:</span>    <span class="t-dim">-</span>
  <span class="t-dim">Parents:</span>   Moderator
<span class="t-cyan">▸ <span class="t-bold" style="color:#c0caf5">Permissions (4)</span></span>
  <span class="t-green">nimbus.admin</span>
  <span class="t-green">nimbus.manage.*</span>
  <span class="t-green">nimbus.start</span>
  <span class="t-red">-nimbus.debug</span>
</pre>
</div>

#### `perms group create <name>`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms group create vip
<span class="t-green">✓ Permission group 'vip' created.</span>
</pre>
</div>

#### `perms group delete <name>`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms group delete vip
<span class="t-red">✖ Permission group 'vip' deleted.</span>
</pre>
</div>

#### `perms group addperm <group> <permission>`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms group addperm vip nimbus.join.full
<span class="t-green">✓ Added 'nimbus.join.full' to group 'vip'.</span>
</pre>
</div>

#### `perms group removeperm <group> <permission>`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms group removeperm vip nimbus.join.full
<span class="t-green">✓ Removed 'nimbus.join.full' from group 'vip'.</span>
</pre>
</div>

#### `perms group setdefault <group> [true/false]`

Set whether a group is assigned to all players by default. Defaults to `true` if no value is given.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms group setdefault default true
<span class="t-green">✓ Group 'default' default set to true.</span>
</pre>
</div>

#### `perms group addparent <group> <parent>`

Add permission inheritance from another group.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms group addparent vip default
<span class="t-green">✓ Added parent 'default' to group 'vip'.</span>
</pre>
</div>

#### `perms group removeparent <group> <parent>`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms group removeparent vip default
<span class="t-green">✓ Removed parent 'default' from group 'vip'.</span>
</pre>
</div>

#### `perms group setprefix <group> <prefix...>`

Set the display prefix for a group (supports MiniMessage format).

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms group setprefix admin &lt;red&gt;[Admin]&lt;/red&gt;
<span class="t-green">✓ Prefix for 'admin' set to:</span> <span class="t-red">[Admin]</span>
</pre>
</div>

#### `perms group setsuffix <group> <suffix...>`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms group setsuffix vip &lt;gold&gt; ★&lt;/gold&gt;
<span class="t-green">✓ Suffix for 'vip' set to:</span> <span class="t-yellow">★</span>
</pre>
</div>

#### `perms group setpriority <group> <number>`

Set the display priority (higher = shown first in tab list).

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms group setpriority admin 100
<span class="t-green">✓ Priority for 'admin' set to 100.</span>
</pre>
</div>

#### `perms group setweight <group> <number>`

Set the conflict resolution weight for a group. When a player belongs to multiple groups with conflicting permissions, the group with the higher weight takes precedence.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms group setweight admin 100
<span class="t-green">✓ Weight for 'admin' set to 100.</span>
</pre>
</div>

#### `perms group meta <set|remove|list> <group> [key] [value]`

Manage custom metadata key-value pairs on a permission group.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms group meta set vip display-color gold
<span class="t-green">✓ Meta 'display-color' set to 'gold' on group 'vip'.</span>
</pre>
</div>

### User Subcommands

#### `perms user list`

List all players with assigned permission groups.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms user list
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Player Assignments</span> ────────────────────────────</span>
<span class="t-bold t-bright-cyan">NAME    UUID                                  GROUPS</span>
<span class="t-dim">──────────────────────────────────────────────────────────────</span>
Alex    <span class="t-dim">550e8400-e29b-41d4-a716-446655440000</span>  <span class="t-cyan">admin</span>, <span class="t-cyan">vip</span>
Steve   <span class="t-dim">6ba7b810-9dad-11d1-80b4-00c04fd430c8</span>  <span class="t-cyan">vip</span>
<span class="t-dim">2 player(s)</span>
</pre>
</div>

#### `perms user info <name|uuid>`

Show a player's groups, effective permissions, and display format.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms user info Alex
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Player: Alex</span> ─────────────────────────────────</span>
  <span class="t-dim">UUID:</span>    550e8400-e29b-41d4-a716-446655440000
  <span class="t-dim">Groups:</span>  admin, vip
  <span class="t-dim">Default:</span> Default
  <span class="t-dim">Display:</span> <span class="t-red">[Admin]</span> Alex <span class="t-dim">(group: admin, priority: 100)</span>
<span class="t-cyan">▸ <span class="t-bold" style="color:#c0caf5">Effective Permissions (18)</span></span>
  <span class="t-green">minecraft.command.gamemode</span>
  <span class="t-green">nimbus.admin</span>
  <span class="t-green">nimbus.command.*</span>
  <span class="t-green">nimbus.join.full</span>
  <span class="t-dim">...</span>
</pre>
</div>

Accepts either player name or UUID.

#### `perms user addgroup <name|uuid> <group>`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms user addgroup Steve vip
<span class="t-green">✓ Added group 'vip' to player 'Steve'.</span>
</pre>
</div>

#### `perms user removegroup <name|uuid> <group>`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms user removegroup Steve vip
<span class="t-green">✓ Removed group 'vip' from player 'Steve'.</span>
</pre>
</div>

#### `perms user check <name|uuid> <permission>`

Check whether a player has a specific permission and show the resolution chain.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms user check Alex nimbus.admin
<span class="t-green">✓ Alex HAS 'nimbus.admin' — Resolved via: admin (exact match)</span>
</pre>
</div>

#### `perms user promote <name|uuid> <track>`

Promote a player to the next group in a permission track.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms user promote Steve ranks
<span class="t-green">✓ Promoted Steve: member → vip (track: ranks)</span>
</pre>
</div>

#### `perms user demote <name|uuid> <track>`

Demote a player to the previous group in a permission track.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms user demote Steve ranks
<span class="t-green">✓ Demoted Steve: vip → member (track: ranks)</span>
</pre>
</div>

#### `perms user meta <set|remove|list> <name|uuid> [key] [value]`

Manage custom metadata on a player.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms user meta set Alex nickname "TheAdmin"
<span class="t-green">✓ Meta 'nickname' set to 'TheAdmin' on player 'Alex'.</span>
</pre>
</div>

### `perms reload`

Reload permission data from files.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms reload
<span class="t-green">✓ Permissions reloaded.</span>
</pre>
</div>

### Track Subcommands

#### `perms track list`

List all permission tracks.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms track list
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Permission Tracks</span> ─────────────────────────────</span>
<span class="t-bold t-bright-cyan">NAME        GROUPS</span>
<span class="t-dim">──────────────────────────────────────────────────────</span>
ranks       member → vip → mvp → admin
staff       helper → moderator → admin
<span class="t-dim">2 track(s)</span>
</pre>
</div>

#### `perms track create <name> <group1,group2,...>`

Create a new permission track with an ordered list of groups.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms track create ranks member,vip,mvp,admin
<span class="t-green">✓ Track 'ranks' created with 4 groups.</span>
</pre>
</div>

#### `perms track delete <name>`

Delete a permission track.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms track delete ranks
<span class="t-red">✖ Track 'ranks' deleted.</span>
</pre>
</div>

### Audit Subcommand

#### `perms audit [lines]`

Show the permission audit log with recent changes. Defaults to 20 entries.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> perms audit
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Permission Audit Log</span> ──────────────────────────</span>
<span class="t-dim">2026-03-31 14:22:01</span>  <span class="t-green">ADD_PERM</span>     group:vip       nimbus.join.full
<span class="t-dim">2026-03-31 14:21:45</span>  <span class="t-yellow">SET_PREFIX</span>   group:admin     <span class="t-red">[Admin]</span>
<span class="t-dim">2026-03-31 14:20:12</span>  <span class="t-cyan">ADD_GROUP</span>    user:Alex       admin
<span class="t-dim">3 entry(s)</span>
</pre>
</div>

---

## Network & System

### `status`

Full cluster overview showing groups, instances, player counts, and capacity.

**Syntax:** `status`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> status
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Network: MyNetwork</span> ────────────────────────────</span>
<span class="t-dim">Services:</span> <span class="t-green">5 ready</span> <span class="t-dim">/ 5 total</span>    <span class="t-dim">Players:</span> <span class="t-bold">36</span>
<span class="t-bold t-bright-cyan">GROUP       TYPE      INSTANCES  MIN/MAX  PLAYERS  STATUS</span>
<span class="t-dim">────────────────────────────────────────────────────────────────</span>
Proxy       STATIC    1          1/2      36       <span class="t-green">healthy</span>
Lobby       DYNAMIC   2          2/4      12       <span class="t-green">healthy</span>
BedWars     DYNAMIC   2          1/8      24       <span class="t-green">healthy</span>
<span class="t-dim">Capacity:</span> <span class="t-green">████████░░░░░░░░░░░░░░░░░░░░░░</span> 5/20 services
<span class="t-dim">Bedrock:</span>  <span class="t-green">enabled</span> (Geyser + Floodgate, base port 19132)
</pre>
</div>

The Bedrock line only appears when `[bedrock] enabled = true` is set in `nimbus.toml`.

---

### `send`

Transfer a player to another service via the Velocity proxy.

**Syntax:** `send <player> <service>`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> send Steve Lobby-1
<span class="t-green">✓ Sent transfer command:</span> <span class="t-dim">Steve -> Lobby-1 (via Proxy-1)</span>
</pre>
</div>

::: warning
Requires a running Velocity proxy instance.
:::

---

### `reload`

Hot-reload all group configuration files and proxy sync settings. Running services are not affected.

**Syntax:** `reload`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> reload
<span class="t-cyan">ℹ</span> Reloading configurations...
<span class="t-green">✓ Loaded 3 group configuration(s).</span>
<span class="t-green">●</span> <span class="t-bold">Proxy</span>    <span class="t-green">1 running</span>
<span class="t-green">●</span> <span class="t-bold">Lobby</span>    <span class="t-green">2 running</span>
<span class="t-dim">○</span> <span class="t-bold">BedWars</span>  <span class="t-dim">0 running</span>
<span class="t-green">✓ Proxy sync config reloaded and pushed to proxies.</span>
</pre>
</div>

::: tip
If a group is removed from config but has running services, they will continue until manually stopped.
:::

---

### `api`

Manage the REST API server.

**Syntax:** `api <start|stop|status|token> [port]`

#### `api status` (or just `api`)

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> api
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">REST API</span> ─────────────────────────────────────</span>
<span class="t-green">●</span> Status:    <span class="t-green">RUNNING</span>
<span class="t-dim">Endpoint:</span>  <span class="t-cyan">http://127.0.0.1:8080</span>
<span class="t-dim">Auth:</span>      Bearer token
<span class="t-dim">Health:</span>    <span class="t-cyan">http://127.0.0.1:8080/api/health</span>
<span class="t-dim">Events:</span>    <span class="t-cyan">ws://127.0.0.1:8080/api/events</span>
</pre>
</div>

#### `api start [port]`

Start the API on the configured port or a custom one.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> api start 9090
<span class="t-green">✓ REST API started on http://0.0.0.0:9090</span>
</pre>
</div>

#### `api stop`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> api stop
<span class="t-yellow">⚠ REST API stopped.</span>
</pre>
</div>

#### `api token`

Show the configured API token (partially masked).

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> api token
<span class="t-cyan">ℹ</span> API Token: <span class="t-dim">secr****oken</span>
</pre>
</div>

**Tab completion:** `start`, `stop`, `status`, `token`.

---

### `nodes`

Show connected cluster nodes. Only available when cluster mode is enabled (`cluster.enabled = true`).

**Syntax:** `nodes [node-name]`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> nodes
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Cluster Nodes</span> ─────────────────────────────────</span>
<span class="t-bold t-bright-cyan">NODE        HOST          STATUS    CPU    MEMORY         SERVICES</span>
<span class="t-dim">──────────────────────────────────────────────────────────────────────</span>
<span class="t-bold">node-1</span>      10.0.0.2      <span class="t-green">online</span>    32%    4096/8192MB    3/10
<span class="t-bold">node-2</span>      10.0.0.3      <span class="t-green">online</span>    18%    2048/8192MB    2/10
<span class="t-dim">2/2 online</span>
</pre>
</div>

Provide a node name for detailed information:

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> nodes node-1
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Node: node-1</span> ──────────────────────────────────</span>
  <span class="t-dim">Host:</span>       10.0.0.2
  <span class="t-dim">Status:</span>     <span class="t-green">online</span>
  <span class="t-dim">CPU:</span>        32.4%
  <span class="t-dim">Memory:</span>     4096MB / 8192MB
  <span class="t-dim">Services:</span>   3 / 10
  <span class="t-dim">Version:</span>    0.1.0
  <span class="t-dim">OS:</span>         Linux amd64
  <span class="t-dim">Running:</span>    Lobby-1, BedWars-1, BedWars-2
</pre>
</div>

::: info
If no nodes are connected, the command prints a warning. Use this to verify agents are properly connected to the controller.
:::

**Tab completion:** Node names.

---

### `lb`

Manage the TCP load balancer. This command works independently of cluster mode — you can use a load balancer with multiple local Velocity proxies without enabling cluster mode.

**Syntax:** `lb [status|enable|disable|strategy <name>]`

With no arguments, shows status and backend proxy table:

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> lb
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Load Balancer</span> ─────────────────────────────────</span>
  Status:     <span class="t-green">ENABLED</span>
  Bind:       0.0.0.0:25565
  Strategy:   least-players
  Active:     36 connections
  Total:      1,247 connections
  Rejected:   3 connections
  Failed:     1 connections

<span class="t-bold t-bright-cyan">BACKEND         HOST          PORT    PLAYERS  STATE          HEALTH     CONNS</span>
<span class="t-dim">─────────────────────────────────────────────────────────────────────────────────────</span>
<span class="t-bold">Proxy-1</span>         127.0.0.1     30010   18       <span class="t-green">● READY</span>       <span class="t-green">HEALTHY</span>    12
<span class="t-bold">Proxy-2</span>         10.0.0.2      30010   18       <span class="t-green">● READY</span>       <span class="t-green">HEALTHY</span>    14
</pre>
</div>

| Subcommand | Description |
|---|---|
| `lb enable` | Enable the load balancer (saves to config, restart required) |
| `lb disable` | Disable the load balancer |
| `lb strategy <name>` | Set strategy: `least-players` or `round-robin` |

**Tab completion:** `status`, `enable`, `disable`, `strategy` → strategy names.

---

### `maintenance`

Toggle maintenance mode for the entire network or individual server groups.

**Syntax:** `maintenance [on|off | <group> on|off | list | add <player> | remove <player>]`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> maintenance
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Maintenance Mode</span> ──────────────────────────────</span>

  <span class="t-dim">Global          </span><span class="t-dim">disabled</span>
  <span class="t-dim">Groups          </span><span class="t-dim">none</span>
</pre>
</div>

**Subcommands:**

| Command | Description |
|---|---|
| `maintenance` | Show current maintenance status |
| `maintenance on` | Enable global maintenance (blocks new connections) |
| `maintenance off` | Disable global maintenance |
| `maintenance <group> on` | Put a specific group in maintenance |
| `maintenance <group> off` | Remove maintenance from a group |
| `maintenance list` | Show whitelisted players |
| `maintenance add <player>` | Add player to maintenance whitelist |
| `maintenance remove <player>` | Remove player from maintenance whitelist |

**Global maintenance** replaces the MOTD with a maintenance message, sets the version protocol to show a red "x" in the server list, and disconnects non-whitelisted players. Players with the `nimbus.maintenance.bypass` permission or on the whitelist can still join.

**Group maintenance** prevents players from joining servers of that group — useful for template updates or testing. Players are sent back to the lobby with a message.

Configuration is stored in `config/modules/syncproxy/motd.toml` under the `[maintenance]` section.

**Tab completion:** `on`, `off`, `list`, `add`, `remove`, group names.

---

### `shutdown`

Gracefully shut down all services in order: game servers first, then lobbies, then proxies.

**Syntax:** `shutdown`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> shutdown
<span class="t-yellow">⚠ Initiating graceful shutdown...</span>
<span class="t-dim">Stopping 5 service(s)...</span>
<span class="t-green">✓ All services stopped.</span> <span class="t-dim">Goodbye.</span>
</pre>
</div>

::: warning
Shutdown requires confirmation. Run `shutdown` first, then `shutdown confirm` within 30 seconds.
:::

---

### `clear`

Clear the terminal screen.

**Syntax:** `clear`

---

### `help`

Show all available commands or detailed help for a specific command.

**Syntax:** `help [command]`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> help
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Commands</span> ──────────────────────────────────────</span>
  <span class="t-cyan">list</span>          <span class="t-dim">List all running services</span>
  <span class="t-cyan">start</span>         <span class="t-dim">Start a new service instance</span>
  <span class="t-cyan">stop</span>          <span class="t-dim">Stop a running service</span>
  <span class="t-cyan">restart</span>       <span class="t-dim">Restart a running service</span>
  <span class="t-cyan">screen</span>        <span class="t-dim">Attach to a service console</span>
  <span class="t-cyan">exec</span>          <span class="t-dim">Execute a command on a service</span>
  <span class="t-cyan">logs</span>          <span class="t-dim">Show recent log output</span>
  <span class="t-cyan">players</span>       <span class="t-dim">List connected players</span>
  <span class="t-cyan">send</span>          <span class="t-dim">Transfer a player to a service</span>
  <span class="t-cyan">groups</span>        <span class="t-dim">List all server groups</span>
  <span class="t-cyan">info</span>          <span class="t-dim">Show group configuration</span>
  <span class="t-cyan">create</span>        <span class="t-dim">Create a new server group</span>
  <span class="t-cyan">import</span>        <span class="t-dim">Import a Modrinth modpack</span>
  <span class="t-cyan">update</span>        <span class="t-dim">Update software or version</span>
  <span class="t-cyan">static</span>        <span class="t-dim">Set a group or service to static</span>
  <span class="t-cyan">dynamic</span>       <span class="t-dim">Set a group to dynamic mode</span>
  <span class="t-cyan">perms</span>         <span class="t-dim">Manage permissions</span>
  <span class="t-cyan">reload</span>       <span class="t-dim">Reload group configurations</span>
  <span class="t-cyan">api</span>           <span class="t-dim">Manage the REST API</span>
  <span class="t-cyan">cluster</span>       <span class="t-dim">Manage cluster mode & load balancer</span>
  <span class="t-cyan">nodes</span>         <span class="t-dim">Show connected cluster nodes</span>
  <span class="t-cyan">lb</span>            <span class="t-dim">Show load balancer status</span>
  <span class="t-cyan">status</span>        <span class="t-dim">Show network status overview</span>
  <span class="t-cyan">shutdown</span>      <span class="t-dim">Graceful shutdown and exit</span>
  <span class="t-cyan">clear</span>         <span class="t-dim">Clear the console</span>
  <span class="t-cyan">help</span>          <span class="t-dim">Show available commands</span>
<span class="t-dim">Type 'help &lt;command&gt;' for detailed usage.</span>
</pre>
</div>

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> help start
<span class="t-cyan t-bold">start</span>  <span class="t-dim">-- Start a new service instance for a group</span>
Usage: start &lt;group&gt;
</pre>
</div>

::: tip
You can also type `?` as a shorthand for `help`.
:::

---

## Cluster & Load Balancer

These commands manage multi-node cluster mode. The `cluster` command works regardless of whether cluster mode is currently enabled — it's how you enable it. The `nodes` command is only available when cluster mode is active. The `lb` command is documented above under [Service Management](#lb) — it's independent of cluster mode.

### `cluster`

Manage cluster mode and load balancer settings from the console without editing config files. Changes are saved to `nimbus.toml` and take effect after restart.

**Syntax:** `cluster <status|enable|disable|token|lb> [subcommand] [args]`

#### `cluster status`

Show cluster and load balancer status.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> cluster status
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Cluster</span> ──────────────────────────────────────</span>
  <span class="t-dim">Mode:</span>         <span class="t-green">enabled</span>
  <span class="t-dim">Agent Port:</span>   8443
  <span class="t-dim">Strategy:</span>     least-services
  <span class="t-dim">Nodes:</span>        2 connected
</pre>
</div>

#### `cluster enable`

Enable cluster mode. Generates an auth token if none exists and saves the config.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> cluster enable
<span class="t-green">✓ Cluster mode enabled.</span> <span class="t-dim">Token generated. Restart to activate.</span>
</pre>
</div>

#### `cluster disable`

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> cluster disable
<span class="t-yellow">⚠ Cluster mode disabled.</span> <span class="t-dim">Restart to apply.</span>
</pre>
</div>

#### `cluster token`

Show the current cluster auth token (partially masked).

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> cluster token
<span class="t-cyan">ℹ</span> Cluster Token: <span class="t-dim">abc1****xyz9</span>
</pre>
</div>

#### `cluster token regenerate`

Generate a new cluster token. All agents will need to be updated with the new token.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> cluster token regenerate
<span class="t-yellow">⚠ New token generated.</span> <span class="t-dim">Update all agents with the new token!</span>
</pre>
</div>

**Tab completion:** `status`, `enable`, `disable`, `token` → `regenerate`.

---

### `nodes`

Show connected cluster nodes and their resource usage. Only available when `cluster.enabled = true`.

**Syntax:** `nodes [node-name]`

Without arguments, shows a summary table of all nodes. With a node name, shows detailed info including CPU, memory, running services, and agent version.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> nodes
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Cluster Nodes</span> ──────────────────────────────────────</span>
<span class="t-bold t-bright-cyan">NODE            HOST            STATUS    CPU     MEMORY          SERVICES</span>
<span class="t-dim">──────────────────────────────────────────────────────────────────────────────</span>
<span class="t-bold">worker-1</span>        10.0.1.10       <span class="t-green">online</span>    42%     3200/8192MB     3/10
<span class="t-bold">worker-2</span>        10.0.1.11       <span class="t-green">online</span>    28%     2100/8192MB     2/10
<span class="t-dim">2/2 online</span>
</pre>
</div>

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> nodes worker-1
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Node: worker-1</span> ─────────────────────────────────</span>
  Host:       10.0.1.10
  Status:     <span class="t-green">online</span>
  CPU:        42.3%
  Memory:     3200MB / 8192MB
  Services:   3 / 10
  Version:    0.1.0
  OS:         Linux amd64
  Running:    Lobby-2, BedWars-1, BedWars-2
</pre>
</div>

**Tab completion:** Node names.

See [`lb`](#lb) in the Service Management section above — the load balancer is independent of cluster mode.
