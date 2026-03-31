# nimbus.toml Reference

The main configuration file for Nimbus. Located at `config/nimbus.toml` inside your Nimbus installation directory. If this file does not exist on first launch, Nimbus uses built-in defaults for all options.

::: tip
After editing `nimbus.toml`, restart Nimbus for changes to take effect. Group configs can be hot-reloaded with the `reload` command, but main config changes require a restart.
:::

## Complete Example

```toml
[network]
name = "Nimbus"
bind = "0.0.0.0"

[controller]
max_memory = "10G"
max_services = 20
heartbeat_interval = 5000

[console]
colored = true
log_events = true
history_file = ".nimbus_history"

[paths]
templates = "templates"
services = "services"
logs = "logs"

[api]
enabled = true
bind = "127.0.0.1"
port = 8080
token = ""
allowed_origins = []

[database]
type = "sqlite"
host = "localhost"
port = 3306
name = "nimbus"
username = ""
password = ""

[loadbalancer]
enabled = false
bind = "0.0.0.0"
port = 25565
strategy = "least-players"
proxy_protocol = false
connection_timeout = 5000
buffer_size = 16384
max_connections = 10000
idle_timeout = 30000
health_check_interval = 10
health_check_timeout = 3000
unhealthy_threshold = 3
healthy_threshold = 2

[permissions]
deploy_plugin = true

[bedrock]
enabled = false
base_port = 19132

[cluster]
enabled = false
token = ""
agent_port = 8443
bind = "0.0.0.0"
heartbeat_interval = 5000
node_timeout = 15000
placement_strategy = "least-services"

[java]
java_8 = ""
java_11 = ""
java_16 = ""
java_17 = ""
java_21 = ""
```

---

## `[network]`

Network identity and bind settings.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `name` | String | `"Nimbus"` | Display name for the network. Used in logging and API responses. |
| `bind` | String | `"0.0.0.0"` | Default bind address for all managed servers. Use `0.0.0.0` to listen on all interfaces, or a specific IP to restrict. |

```toml
[network]
name = "MyNetwork"
bind = "0.0.0.0"
```

---

## `[controller]`

Resource limits and scaling engine settings.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `max_memory` | String | `"10G"` | Total memory budget for all running services. Format: number + `M` or `G` (e.g., `"512M"`, `"8G"`). Nimbus will not start new services if the total allocated memory would exceed this limit. |
| `max_services` | Int | `20` | Maximum number of concurrent services across all groups. |
| `heartbeat_interval` | Long | `5000` | Interval in milliseconds between scaling engine evaluation cycles. Each cycle checks player counts and applies scaling rules. |

```toml
[controller]
max_memory = "16G"
max_services = 50
heartbeat_interval = 3000
```

::: warning
Setting `heartbeat_interval` too low (below 1000ms) may cause excessive server list pings. The default of 5000ms is suitable for most setups.
:::

---

## `[console]`

Interactive console appearance and behavior.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `colored` | Boolean | `true` | Enable ANSI colored output in the console. Disable if your terminal doesn't support color codes. |
| `log_events` | Boolean | `true` | Log internal events (service start/stop, scaling decisions) to the console. |
| `history_file` | String | `".nimbus_history"` | File path for command history persistence. Supports up-arrow recall between sessions. Relative to the Nimbus root directory. |

```toml
[console]
colored = true
log_events = true
history_file = ".nimbus_history"
```

---

## `[paths]`

Directory paths for templates, running services, and logs. All paths are relative to the Nimbus root directory unless an absolute path is specified.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `templates` | String | `"templates"` | Directory containing server templates. Each group references a template by name (subdirectory). |
| `services` | String | `"services"` | Directory where running service instances are created. Each service gets its own subdirectory (e.g., `services/Lobby-1/`). |
| `logs` | String | `"logs"` | Directory for Nimbus log files. |

```toml
[paths]
templates = "templates"
services = "services"
logs = "logs"
```

::: info
Dynamic services have their working directories recreated from the template on every start. Static services preserve their directories across restarts — see [Templates](/config/templates) for details.
:::

---

## `[api]`

REST API and WebSocket server configuration.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `enabled` | Boolean | `true` | Enable the HTTP API server. Required for the bridge plugin and SDK to communicate with Nimbus. |
| `bind` | String | `"127.0.0.1"` | Bind address for the API server. Use `127.0.0.1` for local-only access (recommended) or `0.0.0.0` for remote access. |
| `port` | Int | `8080` | Port for the API server. |
| `token` | String | `""` | Bearer token for API authentication. If empty, Nimbus auto-generates a secure token on first launch and prints it to the console. |
| `allowed_origins` | List\<String\> | `[]` | CORS allowed origins. Only needed if accessing the API from a web browser. Empty list disables CORS headers. |

```toml
[api]
enabled = true
bind = "127.0.0.1"
port = 8080
token = "my-secret-token"
allowed_origins = ["http://localhost:3000"]
```

::: warning
The API token grants full control over your Nimbus instance. Keep it secret. If you expose the API beyond localhost, always use a strong token and consider placing it behind a reverse proxy with TLS.
:::

::: tip
The `/api/health` endpoint is always public (no authentication required) and can be used for external health checks.
:::

---

## `[database]`

Database backend configuration for permissions and cloud metrics.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `type` | String | `"sqlite"` | Database type. Supported values: `sqlite`, `mysql`, `mariadb`, `postgresql`, `postgres`. |
| `host` | String | `"localhost"` | Database server hostname. Only used for MySQL and PostgreSQL. |
| `port` | Int | `3306` | Database server port. Only used for MySQL and PostgreSQL. Automatically defaults to `5432` for PostgreSQL if set to `3306`. |
| `name` | String | `"nimbus"` | Database name. Only used for MySQL and PostgreSQL. For MySQL, the database is auto-created if it doesn't exist. |
| `username` | String | `""` | Database username. Only used for MySQL and PostgreSQL. |
| `password` | String | `""` | Database password. Only used for MySQL and PostgreSQL. |

### SQLite (default)

```toml
[database]
type = "sqlite"
```

No additional configuration needed. The database file is stored at `data/nimbus.db` inside your Nimbus directory.

### MySQL / MariaDB

```toml
[database]
type = "mysql"
host = "localhost"
port = 3306
name = "nimbus"
username = "nimbus"
password = "secret"
```

### PostgreSQL

```toml
[database]
type = "postgresql"
host = "localhost"
port = 5432
name = "nimbus"
username = "nimbus"
password = "secret"
```

::: tip
SQLite is recommended for single-node setups. Use MySQL or PostgreSQL if you need shared database access across multiple machines or want external tooling for querying metrics.
:::

::: info
The database stores permission groups, player assignments, and cloud metrics (service events, scaling events, player sessions). All tables are created automatically on first launch.
:::

---

## `[loadbalancer]`

TCP load balancer configuration. When enabled, Nimbus binds a Layer-4 TCP proxy on the configured port and distributes incoming connections across all running Velocity proxy instances.

::: info
The load balancer is **disabled by default**. Single-node setups do not need it. Enable it when running multiple Velocity proxy instances (e.g., for redundancy or multi-node setups).
:::

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `enabled` | Boolean | `false` | Enable the TCP load balancer. When enabled, player connections arrive at the LB port and are forwarded to a backend Velocity proxy. |
| `bind` | String | `"0.0.0.0"` | Bind address for the load balancer listener. |
| `port` | Int | `25565` | Port to listen on. Typically `25565` (the Minecraft default) so players connect directly to the LB. |
| `strategy` | String | `"least-players"` | Backend selection strategy. `"least-players"` routes to the proxy with the fewest players; `"round-robin"` distributes evenly. |
| `proxy_protocol` | Boolean | `false` | Send HAProxy PROXY protocol v2 headers to backend Velocity instances, preserving real client IPs. Velocity must have `haproxy-protocol = true` in its config. |
| `connection_timeout` | Int | `5000` | Timeout in milliseconds for connecting to a backend proxy. |
| `buffer_size` | Int | `16384` | TCP relay buffer size in bytes. |
| `max_connections` | Int | `10000` | Maximum number of simultaneous connections. Connections beyond this limit are rejected immediately. |
| `idle_timeout` | Int | `30000` | Idle timeout in milliseconds. Connections with no traffic in either direction for this duration are closed. Prevents half-open TCP connections from lingering. |
| `health_check_interval` | Int | `10` | Interval in seconds between health checks for each backend proxy. Uses Minecraft Server List Ping to verify the backend is responsive. |
| `health_check_timeout` | Int | `3000` | Timeout in milliseconds for each health check ping. |
| `unhealthy_threshold` | Int | `3` | Number of consecutive health check failures before a backend is marked `UNHEALTHY` and removed from rotation. |
| `healthy_threshold` | Int | `2` | Number of consecutive health check successes before an `UNHEALTHY` backend is restored to `HEALTHY`. |

```toml
[loadbalancer]
enabled = true
bind = "0.0.0.0"
port = 25565
strategy = "least-players"
proxy_protocol = true
max_connections = 10000
health_check_interval = 10
unhealthy_threshold = 3
```

### Health Checks

When the load balancer is running, it periodically pings each backend Velocity proxy using the Minecraft Server List Ping protocol. This verifies that the proxy is actually responding to game traffic, not just accepting TCP connections.

- A backend that fails `unhealthy_threshold` consecutive checks is marked **UNHEALTHY** and removed from the rotation — no new connections are routed to it.
- Once it passes `healthy_threshold` consecutive checks again, it is restored to **HEALTHY**.
- If **all** backends are unhealthy (e.g., during a transient network blip), the LB falls back to trying any `READY` backend to avoid a total outage.
- When a Velocity service is being stopped, the LB automatically marks it as **DRAINING** — existing connections continue but no new ones are routed there.

::: warning
When the load balancer is enabled, Velocity proxies are allocated backend ports (30000+) instead of 25565. The LB owns port 25565. Make sure your firewall allows connections to the LB port.
:::

---

## `[permissions]`

Controls deployment of the NimbusPerms plugin to backend servers.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `deploy_plugin` | Boolean | `true` | Deploy the NimbusPerms plugin to all backend servers via the global template. Disable if you prefer to manage permissions with LuckPerms or another system. |

```toml
[permissions]
deploy_plugin = true
```

::: tip
Even with `deploy_plugin = false`, the permission system on the controller (groups, players, API, console commands) remains fully functional. This setting only controls whether the NimbusPerms Paper plugin is auto-deployed to backend servers.
:::

---

## `[bedrock]`

Bedrock Edition crossplay support via [Geyser](https://geysermc.org/) + [Floodgate](https://geysermc.org/). When enabled, Bedrock Edition players (mobile, console, Windows 10/11) can join your Java Edition network.

::: info
Bedrock support is **disabled by default**. When enabled, Nimbus automatically downloads Geyser and Floodgate from the GeyserMC API and deploys them to the appropriate templates.
:::

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `enabled` | Boolean | `false` | Enable Bedrock Edition support. Nimbus auto-deploys Geyser (Velocity plugin) and Floodgate (Velocity + backend plugin). |
| `base_port` | Int | `19132` | Starting UDP port for Bedrock connections. Each proxy instance gets its own port, incrementing from this value (19132, 19133, ...). |

```toml
[bedrock]
enabled = true
base_port = 19132
```

### How it works

When Bedrock support is enabled, Nimbus:

1. **Downloads Geyser** (Velocity plugin) — Translates Bedrock protocol to Java protocol inside the proxy
2. **Downloads Floodgate** (Velocity + Paper plugin) — Allows Bedrock players to authenticate via Xbox Live without a Java account
3. **Manages `key.pem`** — Floodgate's encryption key is generated once and distributed to all proxy and backend instances automatically
4. **Configures Geyser** — Each proxy instance gets a unique Bedrock UDP port and auto-generated Geyser config with MOTD, player count, and server icon passthrough enabled
5. **Allocates UDP ports** — Separate from TCP ports, starting at `base_port`

### Architecture

```
Bedrock (UDP:19132) ──► Velocity + Geyser + Floodgate ──► Backends + Floodgate
Java    (TCP:25565) ──►                                ──►
```

After Geyser translates the connection, backends see all players as normal Java players. No backend configuration changes are needed.

### Plugin deployment

| Plugin | Deployed To | Purpose |
|--------|-------------|---------|
| **Geyser** | Proxy (Velocity) | Bedrock → Java protocol translation |
| **Floodgate** | Proxy + all Paper/Purpur/Folia backends | Xbox Live authentication, Bedrock player identification |
| **key.pem** | Proxy + all backends | Shared encryption key for Floodgate auth verification |

::: warning
Floodgate is deployed to **Paper/Purpur/Folia** backends only. Modded servers (Fabric, Forge, NeoForge) do not receive Floodgate automatically — Bedrock players can still connect through the proxy, but backend plugins won't be able to identify them as Bedrock players.
:::

::: tip
Bedrock player names are prefixed with `.` by default (e.g., `.BedrockPlayer123`) to avoid name collisions with Java players. This is configured in Floodgate's settings.
:::

---

## `[cluster]`

Multi-node cluster configuration. When enabled, Nimbus acts as a controller that accepts connections from remote agent nodes. Agents run services on behalf of the controller, allowing you to distribute Minecraft servers across multiple machines.

::: info
Cluster mode is **disabled by default**. Single-node setups do not need it. All features are gated behind `enabled = true` and existing single-node installations are completely unaffected.
:::

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `enabled` | Boolean | `false` | Enable cluster mode. When enabled, Nimbus accepts agent WebSocket connections and can place services on remote nodes. |
| `token` | String | `""` | Shared secret token for authenticating agents. Agents must present this token when connecting. |
| `agent_port` | Int | `8443` | WebSocket port for agent connections. Agents connect to this port on the controller. |
| `bind` | String | `"0.0.0.0"` | Bind address for the cluster WebSocket endpoint (served via the existing API server). |
| `heartbeat_interval` | Long | `5000` | Interval in milliseconds between heartbeat requests sent to agents. |
| `node_timeout` | Long | `15000` | Time in milliseconds after which a node is considered disconnected if no heartbeat response is received. |
| `placement_strategy` | String | `"least-services"` | Strategy for placing new services on nodes. `"least-services"` places on the node running the fewest services; `"least-memory"` uses the node with the most free memory. |

```toml
[cluster]
enabled = true
token = "my-cluster-secret"
agent_port = 8443
heartbeat_interval = 5000
node_timeout = 15000
placement_strategy = "least-services"
```

::: warning
The cluster token grants remote nodes the ability to run services on behalf of the controller. Use a strong, randomly generated token and keep it secret.
:::

::: tip
You can manage all cluster and load balancer settings from the console using the `cluster` command instead of editing this file manually. See [Commands Reference — cluster](/guide/commands#cluster).
:::

---

## `[java]`

Java installation paths for different versions. Nimbus uses these to launch servers that require specific Java versions.

**If a path is empty, Nimbus will auto-detect installed JDKs** by scanning environment variables (`JAVA_8_HOME`, `JAVA_17_HOME`, etc.), `JAVA_HOME`, and common directories (`/usr/lib/jvm`, `~/.sdkman/candidates/java`, `~/.jdks`). **If no compatible JDK is found, Nimbus downloads one from [Adoptium](https://adoptium.net/) automatically** and caches it in the `jdks/` directory.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `java_8` | String | `""` | Path to Java 8 binary. For legacy servers (1.8.x - 1.16.x). |
| `java_11` | String | `""` | Path to Java 11 binary. For servers 1.8.x - 1.12.x (upper limit). |
| `java_16` | String | `""` | Path to Java 16 binary. For Minecraft 1.17.x. |
| `java_17` | String | `""` | Path to Java 17 binary. For Minecraft 1.18.x - 1.20.4. |
| `java_21` | String | `""` | Path to Java 21 binary. For Minecraft 1.20.5+ and Velocity. Auto-detected if available on PATH. |

```toml
[java]
java_8 = "/usr/lib/jvm/java-8-openjdk/bin/java"
java_17 = "/usr/lib/jvm/java-17-openjdk/bin/java"
java_21 = "/usr/lib/jvm/java-21-openjdk/bin/java"
```

### Resolution order

When starting a server, Nimbus resolves the Java executable in this order:

1. **Per-group** `java_path` override (from the [group config](/config/groups))
2. **Configured paths** from this `[java]` section
3. **Auto-detected** installations on the system
4. **Auto-downloaded** JDK from Adoptium (cached in `jdks/`)

::: tip
You can leave all `[java]` paths empty. Nimbus will find or download what it needs. Only set explicit paths if you want to pin a specific JDK distribution (e.g., GraalVM instead of Temurin).
:::

::: info
For a detailed explanation of Java version requirements per Minecraft version, see [Automatic JDK Management](/guide/concepts#automatic-jdk-management).
:::
