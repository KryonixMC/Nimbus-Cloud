# Multi-Node & Load Balancer

Nimbus can distribute services across multiple machines using **cluster mode**. A central controller coordinates remote **agent nodes** that run services locally — scaling, placement, and monitoring all happen automatically. An optional **TCP load balancer** sits in front of your Velocity proxies so players connect to a single address.

::: tip When do I need this?
If your network runs on **one machine**, you don't — single-node mode handles everything. Multi-node becomes valuable when you need more RAM, CPU, or want geographic distribution across dedicated servers.
:::

## Architecture

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus — multi-node cluster</span>
  </div>
  <pre class="terminal-body">
<span class="t-dim">                        Players</span>
<span class="t-dim">                           │</span>
<span class="t-cyan t-bold">                 ┌─────────▼──────────┐</span>
<span class="t-cyan t-bold">                 │</span>   <span class="t-bold">Load Balancer</span>    <span class="t-cyan t-bold">│</span>  <span class="t-dim">:25565 (optional)</span>
<span class="t-cyan t-bold">                 └─────────┬──────────┘</span>
<span class="t-dim">                           │</span>
<span class="t-cyan t-bold">              ┌────────────▼────────────┐</span>
<span class="t-cyan t-bold">              │</span>    <span class="t-bold">Nimbus Controller</span>      <span class="t-cyan t-bold">│</span>  <span class="t-dim">nimbus-core</span>
<span class="t-cyan t-bold">              │</span> <span class="t-dim">Groups · Scaling · API</span>    <span class="t-cyan t-bold">│</span>
<span class="t-cyan t-bold">              └───┬──────────────┬─────┘</span>
<span class="t-dim">                  │  WebSocket   │</span>
<span class="t-green t-bold">         ┌────────▼───┐    ┌────▼────────┐</span>
<span class="t-green t-bold">         │</span> <span class="t-bold">Agent 1</span>    <span class="t-green t-bold">│</span>    <span class="t-green t-bold">│</span>   <span class="t-bold">Agent 2</span>   <span class="t-green t-bold">│</span>  <span class="t-dim">nimbus-agent</span>
<span class="t-green t-bold">         │</span> <span class="t-dim">Proxy-1</span>    <span class="t-green t-bold">│</span>    <span class="t-green t-bold">│</span> <span class="t-dim">BedWars-1</span>   <span class="t-green t-bold">│</span>
<span class="t-green t-bold">         │</span> <span class="t-dim">Lobby-1</span>    <span class="t-green t-bold">│</span>    <span class="t-green t-bold">│</span> <span class="t-dim">BedWars-2</span>   <span class="t-green t-bold">│</span>
<span class="t-green t-bold">         │</span> <span class="t-dim">Lobby-2</span>    <span class="t-green t-bold">│</span>    <span class="t-green t-bold">│</span> <span class="t-dim">BedWars-3</span>   <span class="t-green t-bold">│</span>
<span class="t-green t-bold">         └────────────┘</span>    <span class="t-green t-bold">└─────────────┘</span>
</pre>
</div>

**How it works:**

1. The **controller** (`nimbus-core`) manages all state — groups, scaling decisions, player routing
2. **Agent nodes** (`nimbus-agent`) connect via WebSocket and run Java server processes locally
3. When the scaling engine starts a service, it picks the best node using a **placement strategy**
4. Agents auto-download templates from the controller, launch processes, and stream state back
5. If a node disconnects, the controller detects it via heartbeats and reschedules services
6. If **no remote node is available**, dynamic services fall back to running locally on the controller

## Service Placement Rules

Not all service types are distributed equally. Static services have persistent data (worlds, configs) stored in `services/static/` and are therefore always run on the controller — no sync, no backup, no complexity.

| Service Type | Where it runs | Reason |
|---|---|---|
| **Static** (e.g., Lobby, Survival) | Controller only | Persistent data in `services/static/` — no remote sync |
| **Dynamic** (e.g., BedWars, SkyWars) | Any node (controller or agent) | Stateless — rebuilt from template every start |
| **Proxy** (Velocity) | Any node (controller or agent) | No persistent state |

::: warning
Static services **always** run on the controller. There is no mechanism to sync or backup static service data to remote nodes. If you need a service on a remote node, make it dynamic.
:::

## Quick Start

### 1. Enable cluster mode on the controller

```bash
nimbus> cluster enable
```

This generates an auth token and saves the config. Restart Nimbus to activate.

Or edit `config/nimbus.toml` manually:

```toml
[cluster]
enabled = true
token = "your-secret-token"        # shared with all agents
agent_port = 8443                   # WebSocket port for agents
bind = "0.0.0.0"
heartbeat_interval = 5000           # ms between heartbeats
node_timeout = 15000                # ms before node is considered dead
placement_strategy = "least-services"
```

### 2. Set up an agent node

Copy `nimbus-agent/build/libs/nimbus-agent-&lt;version&gt;-all.jar` to each worker machine. Then either run the interactive wizard:

```bash
java -jar nimbus-agent-&lt;version&gt;-all.jar
```

Or pass everything via CLI flags:

```bash
java -jar nimbus-agent-&lt;version&gt;-all.jar \
  --controller ws://10.0.0.1:8443/cluster \
  --token your-secret-token \
  --name worker-1 \
  --max-memory 16G \
  --max-services 10
```

The agent creates an `agent.toml` on first run:

```toml
[agent]
controller = "ws://10.0.0.1:8443/cluster"
token = "your-secret-token"
node_name = "worker-1"
max_memory = "16G"
max_services = 10

# Optional: specify paths to Java installations.
# Leave empty for auto-detection / auto-download from Adoptium.
[java]
java_8 = ""
java_11 = ""
java_16 = ""
java_17 = ""
java_21 = ""
```

::: tip JDK Auto-Resolution
Agent nodes automatically detect installed JDKs and download missing versions from Adoptium when needed. You only need to configure the `[java]` section if you want to override the auto-detected paths.
:::

### 3. Verify the connection

On the controller:

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> nodes
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Cluster Nodes</span> ──────────────────────────────────</span>
<span class="t-bold t-bright-cyan">NODE        HOST          STATUS    CPU    MEMORY         SERVICES</span>
<span class="t-dim">──────────────────────────────────────────────────────────────────</span>
<span class="t-bold">worker-1</span>    10.0.0.2      <span class="t-green">online</span>    12%    3241/16384MB   2/10
<span class="t-bold">worker-2</span>    10.0.0.3      <span class="t-green">online</span>    8%     1024/8192MB    1/5
<span class="t-dim">2/2 online</span>
</pre>
</div>

## Placement Strategies

When starting a new service, the controller picks a node using one of these strategies:

| Strategy | Description | Best for |
|---|---|---|
| `least-services` | Node running the fewest services (default) | Even distribution |
| `least-memory` | Node with the most free memory | Memory-heavy servers (modded, large worlds) |
| `round-robin` | Rotate through nodes sequentially | Predictable, simple |

```toml
[cluster]
placement_strategy = "least-services"
```

Nodes that are **offline**, **at max services**, or **out of memory** are automatically skipped.

## TCP Load Balancer

When running multiple Velocity proxy instances across nodes, enable the load balancer so all players connect to one address (`play.yourserver.com:25565`):

```bash
nimbus> lb enable
```

Or in `config/nimbus.toml`:

```toml
[loadbalancer]
enabled = true
bind = "0.0.0.0"
port = 25565                    # players connect here
strategy = "least-players"      # or "round-robin"
proxy_protocol = false          # enable for real client IPs
connection_timeout = 5000
buffer_size = 16384
```

The load balancer is a **Layer-4 TCP proxy** — it forwards raw Minecraft protocol bytes without inspecting them. This means zero overhead and full compatibility with any Minecraft version.

### Load balancer strategies

| Strategy | Description |
|---|---|
| `least-players` | Route to the proxy with the fewest players (default) |
| `round-robin` | Distribute evenly across all proxies |

### PROXY protocol (real client IPs)

By default, Velocity sees all connections coming from the load balancer's IP. Enable PROXY protocol v2 to preserve real client IPs:

```toml
[loadbalancer]
proxy_protocol = true
```

Nimbus automatically patches `haproxy-protocol = true` in Velocity's config when this is enabled.

### Monitoring

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> lb
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Load Balancer</span> ───────────────────────────────</span>
  Total Connections:   1,247
  Active Connections:  89

<span class="t-bold t-bright-cyan">BACKEND       HOST          PORT    PLAYERS   STATE</span>
<span class="t-dim">──────────────────────────────────────────────────────</span>
<span class="t-bold">Proxy-1</span>       10.0.0.2      30000   45        <span class="t-green">● READY</span>
<span class="t-bold">Proxy-2</span>       10.0.0.3      30000   44        <span class="t-green">● READY</span>
</pre>
</div>

## CLI Commands

Manage cluster mode and load balancer from the console without editing config files:

```bash
# Cluster (multi-node)
cluster status                  # Show cluster status
cluster enable                  # Enable cluster mode (generates token)
cluster disable                 # Disable cluster mode
cluster token                   # Show auth token
cluster token regenerate        # Generate new token (update all agents!)

# Load Balancer (independent of cluster mode)
lb                              # Show LB status + backend proxies
lb enable                       # Enable load balancer
lb disable                      # Disable load balancer
lb strategy <name>              # Set strategy (least-players, round-robin)
```

Changes are saved to `nimbus.toml` and take effect after restart.

::: info
The load balancer is **independent** of cluster mode. You can use it with multiple local Velocity proxies without enabling cluster mode.
:::

## Node Failure & Recovery

Nimbus handles node failures automatically:

| Scenario | What happens |
|---|---|
| **Agent disconnects briefly** | Services keep running. Agent reconnects within `node_timeout` and resumes. |
| **Agent doesn't reconnect** | After `2 × node_timeout`, services are marked CRASHED. Scaling engine restarts them on other nodes. |
| **Controller restarts** | Agents reconnect automatically (5-second retry loop). Running services are preserved. |

## Template Distribution

Agents automatically download templates from the controller when starting a service:

1. Controller sends `StartService` with template name + SHA-256 hash
2. Agent checks its local cache — if hash matches, uses cached version
3. If not, downloads the template as a ZIP from `GET /api/templates/{name}/download`
4. Extracts to local `templates/` directory and starts the service

Templates are cached on agents — they only re-download when the template changes on the controller.

Velocity forwarding configuration is auto-patched on agent nodes, just like on the controller. Agents apply the same `modern` or `legacy` forwarding settings so proxy services on remote nodes work seamlessly.

## Shutdown Order

### Controller shutdown (cluster mode)

1. Send graceful shutdown to all agents
2. Wait for agents to stop their services (up to 30s)
3. Stop local game servers
4. Stop local lobbies
5. Stop load balancer
6. Stop local proxies
7. Stop cluster WebSocket server

### Agent shutdown (Ctrl+C)

1. Stop all local services (game → lobby → proxy)
2. Close WebSocket connection
3. Exit

## Configuration Reference

See [nimbus.toml — Cluster](/config/nimbus-toml#cluster) and [nimbus.toml — Load Balancer](/config/nimbus-toml#load-balancer) for all configuration options.

## Next Steps

- [Scaling Guide](/guide/scaling) — How auto-scaling works with multi-node
- [Commands Reference](/guide/commands) — `nodes`, `lb`, `cluster` commands
- [REST API](/reference/api) — `/api/nodes`, `/api/loadbalancer` endpoints
- [Architecture](/developer/architecture) — Technical deep-dive
