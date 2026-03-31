# Group Configuration

Server groups define how Nimbus manages collections of server instances. Each group has its own TOML file in the `groups/` directory (e.g., `groups/Lobby.toml`, `groups/BedWars.toml`).

Group configs can be hot-reloaded at runtime using the `reload` console command.

## Complete Example

```toml
[group]
name = "BedWars"
type = "DYNAMIC"
template = "BedWars"
software = "PAPER"
version = "1.21.4"

[group.resources]
memory = "2G"
max_players = 50

[group.scaling]
min_instances = 1
max_instances = 10
players_per_instance = 40
scale_threshold = 0.8
idle_timeout = 0

[group.lifecycle]
stop_on_empty = false
restart_on_crash = true
max_restarts = 5

[group.jvm]
optimize = true
```

---

## `[group]`

Core group identity and server software settings.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `name` | String | *required* | Group name in PascalCase. Services are named `<Name>-<N>` (e.g., `BedWars-1`). |
| `type` | Enum | `"DYNAMIC"` | `STATIC` or `DYNAMIC`. See [Static vs Dynamic](#static-vs-dynamic) below. |
| `template` | String | *required* | Template directory name inside `templates/`. Must not be blank. |
| `software` | Enum | `"PAPER"` | Server software. One of: `PAPER`, `PUFFERFISH`, `PURPUR`, `FOLIA`, `VELOCITY`, `FORGE`, `FABRIC`, `NEOFORGE`, `CUSTOM`. |
| `version` | String | `"1.21.4"` | Minecraft version (e.g., `"1.21.4"`, `"1.8.8"`). Must match format `X.Y` or `X.Y.Z`. |
| `modloader_version` | String | `""` | Modloader version for `FORGE`, `FABRIC`, or `NEOFORGE`. If empty, Nimbus uses the latest stable version. |
| `jar_name` | String | `""` | Custom JAR filename for `CUSTOM` software. Defaults to `"server.jar"` if empty. |
| `ready_pattern` | String | `""` | Custom regex pattern for detecting when a `CUSTOM` server is ready. Nimbus watches stdout for this pattern. |
| `java_path` | String | `""` | Override the Java binary path for this group. Takes priority over the version-based lookup in [nimbus.toml](/config/nimbus-toml#java). |

---

## `[group.resources]`

Memory and player capacity settings.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `memory` | String | `"1G"` | JVM heap size (`-Xmx`). Format: number + `M` or `G` (e.g., `"512M"`, `"2G"`). Counts toward the controller's `max_memory` budget. |
| `max_players` | Int | `50` | Maximum players per instance. Must be >= 1. Used for display and scaling calculations. |

---

## `[group.scaling]`

Auto-scaling behavior for dynamic groups. Ignored for static groups.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `min_instances` | Int | `1` | Minimum running instances. Nimbus ensures at least this many are always running. Must be >= 0 and <= `max_instances`. |
| `max_instances` | Int | `4` | Maximum instances the scaling engine will create. |
| `players_per_instance` | Int | `40` | Target player capacity per instance. Used in the fill-rate calculation. |
| `scale_threshold` | Double | `0.8` | Fill-rate threshold (0.0 - 1.0) that triggers scale-up. When the ratio of total players to total capacity exceeds this value, a new instance is started. |
| `idle_timeout` | Long | `0` | Seconds before an empty instance is stopped. Set to `0` to disable idle shutdown (instances stay running indefinitely). Only applies when current instances exceed `min_instances`. |

### Scaling Formula

The scaling engine runs every `heartbeat_interval` milliseconds (configured in [nimbus.toml](/config/nimbus-toml#controller)) and evaluates each dynamic group:

**Scale Up** - A new instance starts when:
```
fill_rate = total_players / (routable_instances * players_per_instance)
fill_rate > scale_threshold AND current_instances < max_instances
```

**Scale Down** - An empty instance stops when:
```
instance_players == 0
AND seconds_idle > idle_timeout
AND idle_timeout > 0
AND current_instances > min_instances
```

::: info
Services with a custom state (e.g., `INGAME`, `ENDING`) are excluded from capacity calculations. They are not considered "routable" and won't accept new players, so they don't count toward the fill rate.
:::

### Example: High-Volume Game Server

```toml
[group.scaling]
min_instances = 2       # Always have 2 ready
max_instances = 20      # Scale up to 20
players_per_instance = 16
scale_threshold = 0.7   # Start new instance at 70% fill
idle_timeout = 120      # Remove empty instances after 2 minutes
```

---

## `[group.lifecycle]`

Instance lifecycle management.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `stop_on_empty` | Boolean | `false` | Stop the instance when the last player leaves. Useful for game servers where an empty instance has no purpose. |
| `restart_on_crash` | Boolean | `true` | Automatically restart an instance if its process exits unexpectedly. |
| `max_restarts` | Int | `5` | Maximum consecutive automatic restarts. After this limit, the instance stays stopped to prevent crash loops. Must be >= 0. |

---

## `[group.jvm]`

JVM and performance optimization settings.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `optimize` | Boolean | `true` | Enable automatic performance optimization. When enabled with no custom `args`, applies [Aikar's JVM flags](https://docs.papermc.io/paper/aikars-flags) and optimizes `spigot.yml` + `paper-world-defaults.yml` for Paper/Pufferfish/Purpur/Folia servers. |
| `args` | List\<String\> | `[]` | Custom JVM arguments passed before the `-jar` flag. When set alongside `optimize = true`, these args are used instead of Aikar's flags, but config optimization still applies. |

### Performance Optimization

When `optimize = true` (the default), Nimbus automatically:

1. **Aikar's JVM Flags** — Applies optimized G1GC tuning flags that reduce GC pauses and improve throughput. Flags are adjusted automatically for large heaps (12G+). Applied to all server types.
2. **Config Tuning** (Paper/Pufferfish/Purpur/Folia only) — Optimizes `spigot.yml` (merge radius, entity activation ranges) and `paper-world-defaults.yml` (chunk save throttling, explosion optimization, despawn ranges).

Three modes:

```toml
# Mode 1: Full auto (default) — Aikar's flags + config tuning
[group.jvm]
optimize = true

# Mode 2: Custom JVM flags + config tuning
[group.jvm]
optimize = true
args = ["-XX:+UseZGC", "-Dcom.mojang.eula.agree=true"]

# Mode 3: No optimization — fully manual
[group.jvm]
optimize = false
```

---

## Static vs Dynamic

| Aspect | STATIC | DYNAMIC |
|--------|--------|---------|
| **Template handling** | Template copied once; existing files preserved on restart | Template re-applied from scratch on every start |
| **World data** | Persisted across restarts | Wiped on every start |
| **Scaling** | No auto-scaling; instances managed manually | Auto-scaled based on player count and thresholds |
| **Use case** | Survival worlds, persistent lobbies, build servers | Minigame servers, temporary game instances |
| **Instance count** | Fixed at `min_instances` | Ranges from `min_instances` to `max_instances` |

---

## Full Examples

### Proxy (Velocity)

```toml
[group]
name = "Proxy"
type = "STATIC"
template = "Proxy"
software = "VELOCITY"
version = "3.4.0"

[group.resources]
memory = "512M"
max_players = 500

[group.scaling]
min_instances = 1
max_instances = 1

[group.lifecycle]
restart_on_crash = true
max_restarts = 10

[group.jvm]
optimize = true
```

::: tip
Proxy ports start at 25565. Backend ports start at 30000. Port allocation is automatic.
:::

### Lobby

```toml
[group]
name = "Lobby"
type = "STATIC"
template = "Lobby"
software = "PAPER"
version = "1.21.4"

[group.resources]
memory = "1G"
max_players = 100

[group.scaling]
min_instances = 1
max_instances = 3
players_per_instance = 80
scale_threshold = 0.8
idle_timeout = 0

[group.lifecycle]
restart_on_crash = true
max_restarts = 5

[group.jvm]
optimize = true
```

### Game Server (BedWars)

```toml
[group]
name = "BedWars"
type = "DYNAMIC"
template = "BedWars"
software = "PAPER"
version = "1.21.4"

[group.resources]
memory = "2G"
max_players = 16

[group.scaling]
min_instances = 1
max_instances = 10
players_per_instance = 16
scale_threshold = 0.7
idle_timeout = 120

[group.lifecycle]
stop_on_empty = true
restart_on_crash = true
max_restarts = 3

[group.jvm]
optimize = true
```

### Modded Server (Forge)

```toml
[group]
name = "ModdedSMP"
type = "STATIC"
template = "ModdedSMP"
software = "FORGE"
version = "1.20.1"
modloader_version = "47.2.0"

[group.resources]
memory = "6G"
max_players = 30

[group.scaling]
min_instances = 1
max_instances = 1

[group.lifecycle]
restart_on_crash = true
max_restarts = 3

[group.jvm]
optimize = true
```

### Fabric Server

```toml
[group]
name = "FabricSMP"
type = "STATIC"
template = "FabricSMP"
software = "FABRIC"
version = "1.21.4"

[group.resources]
memory = "4G"
max_players = 40

[group.scaling]
min_instances = 1
max_instances = 1

[group.lifecycle]
restart_on_crash = true
max_restarts = 3

[group.jvm]
optimize = true
```

### Folia Server (Regionized Multithreading)

```toml
[group]
name = "FoliaLobby"
type = "STATIC"
template = "FoliaLobby"
software = "FOLIA"
version = "1.21.4"

[group.resources]
memory = "4G"
max_players = 200

[group.scaling]
min_instances = 1
max_instances = 2

[group.lifecycle]
restart_on_crash = true
max_restarts = 3

[group.jvm]
optimize = true
```

::: warning Folia Plugin Compatibility
Folia uses regionized multithreading, which breaks most Bukkit/Paper plugins. The Nimbus SDK and ProtocolLib are **automatically excluded** from Folia services. Only use plugins that explicitly support Folia's threading model.
:::

### Custom Server Software

```toml
[group]
name = "CustomServer"
type = "STATIC"
template = "CustomServer"
software = "CUSTOM"
version = "1.21.4"
jar_name = "custom-server.jar"
ready_pattern = "Server started on port \\d+"

[group.resources]
memory = "2G"
max_players = 50

[group.scaling]
min_instances = 1
max_instances = 1

[group.lifecycle]
restart_on_crash = true
max_restarts = 3

[group.jvm]
optimize = true
```

---

## Validation Rules

Nimbus validates every group config on load and rejects invalid configurations:

- `name` must not be blank
- `template` must not be blank
- `version` must match `X.Y` or `X.Y.Z` format (e.g., `1.21.4`, `1.8.8`)
- `memory` must match format like `512M` or `2G`
- `min_instances` must be >= 0 and <= `max_instances`
- `scale_threshold` must be between 0.0 and 1.0
- `max_players` must be >= 1
- `max_restarts` must be >= 0

::: warning
Invalid group configs are skipped with an error log message. Other valid groups will still load normally.
:::
