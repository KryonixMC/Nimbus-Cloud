# Agent Node

The Nimbus Agent (`nimbus-agent`) is a standalone process that runs on remote machines to extend a Nimbus cluster across multiple nodes. It connects to the controller via WebSocket, receives service lifecycle commands, and manages local JVM processes.

## Overview

The agent handles:

- WebSocket connection to the controller with automatic reconnection
- Template downloading and caching (ZIP-based, hash-compared)
- Local JVM process management (start, stop, command forwarding, stdout capture)
- Java version resolution with automatic Adoptium downloads
- Service state persistence and crash recovery
- System metrics reporting (CPU, memory) via heartbeats

## Architecture

```
nimbus-agent/src/main/kotlin/dev/nimbus/agent/
├── Agent.kt              → Entry point (main function), CLI arg parsing
├── AgentConfig.kt        → TOML config model + loader
├── AgentRuntime.kt       → WebSocket connection loop, message dispatch
├── AgentStateStore.kt    → JSON persistence for crash recovery
├── JavaResolver.kt       → Java installation detection + Adoptium download
├── LocalProcessHandle.kt → Single JVM process wrapper (stdin/stdout/lifecycle)
├── LocalProcessManager.kt→ Service lifecycle, template copy, config patching
├── SetupWizard.kt        → First-run interactive setup
└── TemplateDownloader.kt → HTTP template download + ZIP extraction
```

### Bootstrap and connection

The `main()` function in `Agent.kt` parses CLI arguments (`--controller`, `--token`, `--name`, `--max-memory`, `--max-services`), loads `agent.toml` (or runs the setup wizard), then starts `AgentRuntime`.

`AgentRuntime` maintains a persistent WebSocket connection to the controller with automatic 5-second reconnection. On connect, it sends an `AuthRequest` with the node's token, name, resources, and OS info. After authentication, any recovered services from a previous run are reported, and the agent enters the message dispatch loop.

All message handling uses `kotlinx-coroutines` with a `SupervisorJob` scope -- individual service failures do not crash the agent. The agent handles `StartService`, `StopService`, `SendCommand`, `HeartbeatRequest`, and `ShutdownAgent` messages (see [Protocol](/developer/protocol) for details).

## Template sync

`TemplateDownloader` fetches templates from the controller's REST API (`GET /api/templates/{name}/download`). Templates are identified by name and content hash.

```
Agent requests template "Lobby" with hash "abc123"
  ├── Local cache has matching hash? → Skip download
  └── Hash mismatch or missing?
      ├── Download ZIP from controller (includes global plugins like SDK, ProtocolLib)
      ├── Extract to templates/{name}/
      └── Store hash for future comparisons
```

The `software` parameter is included in the download request so the controller can bundle the correct global plugins (e.g., SDK for Paper but not for Velocity).

## Local service management

`LocalProcessManager` handles service lifecycle: copying templates to work directories (`services/temp/{Name}_{uuid}` for dynamic, `services/static/{Name}` for static), patching `server.properties` and Velocity forwarding configs, building the JVM command line, and launching the process.

`LocalProcessHandle` wraps a single JVM process with stdout capture (`SharedFlow<String>`, buffer 4096), ready detection (watches for `Done (` regex), graceful shutdown (sends `stop` via stdin), and process adoption for crash recovery (`adopt(pid, serviceName)`).

## Crash recovery

`AgentStateStore` persists running service metadata to `state/services.json` using atomic file writes. On restart:

1. The agent loads persisted services from the state file
2. For each service, it attempts to adopt the OS process by PID via `LocalProcessHandle.adopt()`
3. Adoption verifies the process is alive and its command line contains `nimbus.service.name={serviceName}`
4. Successfully recovered services are reported to the controller as `READY`
5. Failed recoveries are removed from the state file

## Java version resolution

`JavaResolver` finds the correct Java version by checking configured paths (`[java]` section), cached downloads in `jdks/`, environment variables (`JAVA_8_HOME` through `JAVA_21_HOME`), `JAVA_HOME`, and common directories (`/usr/lib/jvm`, `~/.sdkman/candidates/java`, etc.). If no compatible version is found, it auto-downloads from the Adoptium API.

## Building and running

```bash
# Build the agent fat JAR
./gradlew :nimbus-agent:shadowJar

# Run (first run triggers setup wizard)
java -jar nimbus-agent/build/libs/nimbus-agent-<version>-all.jar

# Run with CLI args (skip wizard)
java -jar nimbus-agent-<version>-all.jar \
  --controller ws://10.0.0.1:8443/cluster \
  --token your-cluster-token \
  --name worker-1 \
  --max-memory 16G \
  --max-services 20
```

## Configuration

The agent is configured via `agent.toml` in the working directory:

```toml
[agent]
controller = "ws://127.0.0.1:8443/cluster"
token = "your-cluster-token"
node_name = "worker-1"
max_memory = "8G"
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

| Key | Default | Description |
|---|---|---|
| `controller` | `ws://127.0.0.1:8443/cluster` | Controller WebSocket URL |
| `token` | (required) | Cluster auth token (matches controller config) |
| `node_name` | hostname | Display name for this node |
| `max_memory` | auto-detected | Maximum memory this node can allocate to services |
| `max_services` | auto-detected | Maximum concurrent services on this node |

Memory and max services are auto-detected from the system if not specified (total RAM minus 2 GB for OS, one service per GB).

## Next steps

- [Architecture](/developer/architecture) -- How the agent fits into the cluster
- [Protocol](/developer/protocol) -- All cluster message types
- [SDK](/developer/sdk) -- Backend server plugin API
