# Stress Testing

Nimbus includes a built-in stress testing system that simulates player load across your backend servers without requiring real Minecraft clients. This lets you validate scaling rules, find performance bottlenecks, and determine optimal `players_per_instance` values before going live.

## How it works

The stress test manager creates simulated player sessions on backend servers. These fake players:

- Count toward each service's player total (triggering the scaling engine naturally)
- Appear in the tab list on backend servers via ProtocolLib
- Are reflected in proxy player counts
- Respect each service's `max_players` cap

::: info
Only **backend groups** receive simulated players. Proxy groups are excluded from stress tests.
:::

## Console commands

### Start a test

```bash
# Simulate 100 players across all backend groups
nimbus> stress start 100

# Target a specific group
nimbus> stress start 200 BedWars

# Ramp up gradually over 60 seconds
nimbus> stress start 500 --ramp 60
```

### Check status

```bash
nimbus> stress status
```

Shows active test details: total simulated players, capacity, and per-service breakdown.

### Adjust mid-test

```bash
# Ramp to a new target without stopping
nimbus> stress ramp 300

# Ramp over 30 seconds
nimbus> stress ramp 300 --duration 30
```

### Stop

```bash
nimbus> stress stop
```

All simulated players are removed and services return to normal.

## In-game commands

The bridge plugin exposes stress testing via `/cloud stress`:

```
/cloud stress start 100 Lobby 30
/cloud stress stop
/cloud stress ramp 200 60
/cloud stress status
```

Requires the `nimbus.cloud.stress` permission.

::: tip
The in-game commands follow the format `/cloud stress start <players> [group] [rampSeconds]` and `/cloud stress ramp <players> [durationSeconds]`.
:::

## REST API

All endpoints require bearer token authentication.

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/stress` | Current status (active, players, capacity, per-service) |
| `POST` | `/api/stress/start` | Start a stress test |
| `POST` | `/api/stress/stop` | Stop the active test |
| `POST` | `/api/stress/ramp` | Adjust target mid-test |

### Start via API

```bash
curl -X POST http://127.0.0.1:8080/api/stress/start \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"players": 100, "group": "Lobby", "rampSeconds": 30}'
```

### Check status via API

```bash
curl http://127.0.0.1:8080/api/stress \
  -H "Authorization: Bearer <token>"
```

```json
{
  "active": true,
  "totalPlayers": 100,
  "targetPlayers": 100,
  "capacity": 400,
  "services": {
    "Lobby-1": 40,
    "Lobby-2": 40,
    "Lobby-3": 20
  }
}
```

### Ramp via API

```bash
curl -X POST http://127.0.0.1:8080/api/stress/ramp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"players": 200, "durationSeconds": 60}'
```

## Scaling interaction

The stress test is designed to exercise your scaling rules naturally:

1. Simulated players fill backend services up to `max_players`
2. The scaling engine detects rising fill rates and starts new instances (up to `max_instances`)
3. When you stop the test or ramp down, empty instances are stopped after `idle_timeout`

This means you can validate your entire scaling pipeline -- from scale-up thresholds to idle timeouts -- without coordinating real players.

## Best practices

### Finding the right players_per_instance

Run progressively larger tests and watch server performance:

```bash
nimbus> stress start 50 BedWars --ramp 30
# Monitor TPS via screen command
nimbus> screen BedWars-1
# Check if TPS stays above 19.5

nimbus> stress ramp 100 --duration 30
# If TPS drops, your players_per_instance is too high
```

### Testing scale-up and scale-down

```bash
# 1. Start with your normal config and ramp to trigger scaling
nimbus> stress start 200 Lobby --ramp 60

# 2. Watch new instances start
nimbus> status

# 3. Ramp down and verify cleanup
nimbus> stress ramp 10 --duration 30

# 4. Wait for idle_timeout, then check instances stopped
nimbus> status
```

### Monitoring during tests

Use `screen` to attach to a service console and watch TPS, memory, and tick times:

```bash
nimbus> screen Lobby-1
# Press Ctrl+C to detach
```

The `status` command shows per-instance player counts during the test. The REST API at `/api/metrics` provides historical data for post-test analysis.

::: warning
Stress tests consume real server resources (CPU, memory) even though the players are simulated. ProtocolLib creates fake player entities that the server must track. Start with small numbers and ramp up.
:::

## Limitations

- **Proxy groups are excluded** -- only backend groups receive simulated players
- **max_players is respected** -- each service is capped at its configured `max_players`
- **max_instances is respected** -- the scaling engine will not exceed the group's `max_instances`
- **Console spam suppressed** -- `StressBot-*` events are filtered from the console output
- **No real gameplay** -- simulated players do not move, interact, or generate chunk load. CPU load is lower than real players.

::: tip
Because simulated players do not generate chunk load or entity interactions, real-world performance may differ. Use stress tests for scaling validation and baseline benchmarks, then fine-tune with real players during a beta phase.
:::

## Next steps

- [Auto-Scaling](/guide/scaling) -- Configure scaling rules that stress tests exercise
- [Commands Reference](/guide/commands) -- Full command syntax
- [API Reference](/reference/api) -- REST API for automation
