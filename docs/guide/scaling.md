# Auto-Scaling Guide

Nimbus includes an auto-scaling engine that dynamically adjusts the number of running instances per group based on player demand. This ensures you always have enough server capacity without wasting resources on idle instances.

## How scaling works

The scaling engine runs a continuous loop:

1. Every **heartbeat interval** (default: 5 seconds), ping all `READY` services via [Server List Ping](https://wiki.vg/Server_List_Ping) to update player counts
2. For each **dynamic** group, count players on **routable** services (those with no custom state)
3. Apply the scale-up and scale-down rules

Static groups are never auto-scaled.

## Scale-up

The engine scales up when the **fill rate** exceeds the **scale threshold**:

```
fill_rate = total_players / (routable_count * players_per_instance)

if fill_rate > scale_threshold
   AND routable_count < max_instances
→ start one new instance
```

**Example:** A BedWars group with `players_per_instance = 16` and `scale_threshold = 0.8`:

| Routable servers | Players | Fill rate | Action |
|---|---|---|---|
| 2 | 20 | 62.5% | No action |
| 2 | 27 | 84.4% | Scale up (> 80%) |
| 3 | 27 | 56.3% | No action |
| 3 | 40 | 83.3% | Scale up (> 80%) |

::: info
Only one instance is started per evaluation cycle. This prevents over-provisioning during sudden spikes. If demand continues, another instance will start on the next cycle (5 seconds later).
:::

## Scale-down

The engine scales down when a service has been **empty for longer than the idle timeout**:

```
if service.playerCount == 0
   AND service has been empty for > idle_timeout seconds
   AND routable_count > min_instances
→ stop that service
```

The idle timer starts when a service's player count drops to zero and resets if any player joins.

::: warning
If `idle_timeout` is set to `0`, scale-down is **disabled** -- empty instances will never be stopped automatically. This is the default, and appropriate for lobbies that should always be running.
:::

## Custom states and routing

Services can have a **custom state** set by game plugins via the SDK (e.g., `"WAITING"`, `"INGAME"`, `"ENDING"`). Services with a custom state are treated specially:

- **Excluded from routable count** -- they don't count toward capacity calculations
- **Never scaled down** -- even if empty, a service mid-game won't be stopped
- **Not chosen for routing** -- the proxy won't send new players to them

This is critical for game servers. Without custom states, a BedWars server mid-game would count toward capacity, and the scaling engine might not start new instances even though no server is actually accepting players.

```java
// In your game plugin:
Nimbus.setState("INGAME");    // Server is no longer routable
// ... game plays out ...
Nimbus.clearState();          // Server is routable again
```

### How custom states affect scaling

Consider a BedWars group with 4 instances:

| Service | Players | Custom State | Routable? |
|---|---|---|---|
| BedWars-1 | 16 | `INGAME` | No |
| BedWars-2 | 14 | `INGAME` | No |
| BedWars-3 | 8 | `null` | Yes |
| BedWars-4 | 0 | `null` | Yes |

The scaling engine sees: **2 routable** servers, **8 total routable players**. Fill rate = 8 / (2 * 16) = 25%. No scale-up needed.

If BedWars-4 fills up:

| Service | Players | Custom State | Routable? |
|---|---|---|---|
| BedWars-3 | 14 | `null` | Yes |
| BedWars-4 | 14 | `null` | Yes |

Fill rate = 28 / (2 * 16) = 87.5% > 80% threshold. A new instance (BedWars-5) starts.

## Configuration

Scaling is configured per group in the `[group.scaling]` section:

```toml
[group.scaling]
min_instances = 1          # Minimum instances (always running)
max_instances = 10         # Maximum instances (hard cap)
players_per_instance = 16  # Expected players per server
scale_threshold = 0.8      # Scale up at this fill rate (0.0 - 1.0)
idle_timeout = 300         # Seconds before stopping an empty server
```

| Field | Default | Description |
|---|---|---|
| `min_instances` | `1` | Floor -- instances are started on boot to meet this |
| `max_instances` | `4` | Ceiling -- scaling will never exceed this |
| `players_per_instance` | `40` | Used to calculate fill rate |
| `scale_threshold` | `0.8` | Fill rate that triggers scale-up (80%) |
| `idle_timeout` | `0` | Seconds empty before scale-down (0 = disabled) |

## Practical examples

### Lobby servers

Always-on, spread players evenly. Never scale down below 1.

```toml
[group]
name = "Lobby"
type = "STATIC"

[group.scaling]
min_instances = 1
max_instances = 4
players_per_instance = 100
scale_threshold = 0.8
idle_timeout = 0             # Never stop lobbies
```

### BedWars (minigame)

Ephemeral servers that scale with demand. Stop empty servers after 5 minutes.

```toml
[group]
name = "BedWars"
type = "DYNAMIC"

[group.scaling]
min_instances = 1
max_instances = 10
players_per_instance = 16
scale_threshold = 0.8
idle_timeout = 300

[group.lifecycle]
stop_on_empty = false        # Let idle_timeout handle it
restart_on_crash = true
max_restarts = 3
```

### Survival server

Single persistent instance, no scaling needed.

```toml
[group]
name = "Survival"
type = "STATIC"

[group.scaling]
min_instances = 1
max_instances = 1
```

### Event mode

Expecting a traffic spike? Temporarily increase capacity:

```toml
[group.scaling]
min_instances = 5
max_instances = 20
players_per_instance = 50
scale_threshold = 0.7        # Scale earlier for headroom
idle_timeout = 600
```

Or edit the group config file and reload:

```bash
nimbus> reload
```

## Manual scaling

### Console commands

```bash
# Manually start an instance
nimbus> start BedWars

# Manually stop an instance
nimbus> stop BedWars-3

# View current scaling state
nimbus> status
```

### REST API

```bash
# Start a new instance
curl -X POST http://127.0.0.1:8080/api/services/BedWars/start \
  -H "Authorization: Bearer <token>"

# Stop a specific instance
curl -X POST http://127.0.0.1:8080/api/services/BedWars-3/stop \
  -H "Authorization: Bearer <token>"
```

## Monitoring

The `status` command shows current scaling state for all groups:

```
nimbus> status
```

This displays:
- Active instances per group
- Player counts per instance
- Custom states
- Scaling bounds (min/max)

## Tuning tips

### Threshold selection

| Threshold | Behavior | Best for |
|---|---|---|
| `0.6` | Scale early, lots of headroom | Competitive games where lag = lost players |
| `0.8` | Balanced (default) | Most use cases |
| `0.95` | Pack tightly, minimal waste | Large lobbies, budget-constrained |

### Idle timeout

| Timeout | Behavior |
|---|---|
| `0` | Never auto-stop (good for lobbies) |
| `60` | Aggressive cleanup (1 minute) |
| `300` | Balanced (5 minutes, good for minigames) |
| `900` | Conservative (15 minutes, good for longer game modes) |

### Players per instance

Set this to the **expected peak capacity**, not the absolute maximum. If your BedWars maps hold 16 players, set `players_per_instance = 16`. If your lobby can handle 200 but you want to keep it comfortable, set `players_per_instance = 100`.

::: tip
The `scale_threshold` and `players_per_instance` work together. A threshold of 0.8 with 16 players per instance means scaling triggers at 13 players on average per routable server.
:::

## Multi-node scaling

By default, Nimbus runs all services on a single machine. For larger networks, you can enable **cluster mode** to distribute services across multiple machines with automatic placement, failover, and an optional TCP load balancer.

The scaling engine works the same way in multi-node — it just has more machines to place services on. Remote services report their player counts via heartbeats, so scaling decisions remain accurate. If no remote node is available, dynamic services fall back to running locally on the controller.

**Static services are never distributed** — they always run on the controller because they have persistent data (worlds, configs) in `services/static/`. Only dynamic and proxy services are placed on remote agent nodes.

::: tip
See the dedicated **[Multi-Node & Load Balancer](/guide/multi-node)** guide for full setup instructions, placement strategies, load balancer configuration, and failure handling.
:::

## Next steps

- [Server Groups](/guide/server-groups) -- Group configuration details
- [SDK](/developer/sdk) -- Setting custom states from game plugins
- [API Reference](/reference/api) -- REST API for manual control
- [nimbus.toml Reference](/config/nimbus-toml) -- Load balancer and cluster configuration
