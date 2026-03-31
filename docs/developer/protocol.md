# Protocol / Message Reference

The `nimbus-protocol` module defines the shared message types exchanged between the Nimbus controller and agent nodes over WebSocket. It is a dependency of both `nimbus-core` and `nimbus-agent`.

## Overview

All cluster communication uses JSON-serialized messages with a `type` discriminator field. The protocol is defined in a single file:

- `dev.nimbus.protocol.ClusterMessage` -- Sealed class containing all message types
- `dev.nimbus.protocol.ServiceHeartbeat` -- Data class for per-service status in heartbeats
- `dev.nimbus.protocol.clusterJson` -- Pre-configured `kotlinx.serialization.json.Json` instance

```kotlin
val clusterJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}
```

## Message types

### Controller to Agent

| Type | Class | Purpose |
|---|---|---|
| `AUTH_RESPONSE` | `AuthResponse` | Accept/reject agent authentication |
| `START_SERVICE` | `StartService` | Launch a new service on the agent |
| `STOP_SERVICE` | `StopService` | Stop a running service |
| `SEND_COMMAND` | `SendCommand` | Forward a console command to a service |
| `HEARTBEAT_REQUEST` | `HeartbeatRequest` | Request system metrics from agent |
| `TEMPLATE_INFO` | `TemplateInfo` | Template metadata (name, hash, download URL, size) |
| `SHUTDOWN_AGENT` | `ShutdownAgent` | Gracefully shut down the agent |

### Agent to Controller

| Type | Class | Purpose |
|---|---|---|
| `AUTH_REQUEST` | `AuthRequest` | Authenticate with token, node info, and resources |
| `HEARTBEAT_RESPONSE` | `HeartbeatResponse` | CPU, memory, and per-service status |
| `SERVICE_STATE_CHANGED` | `ServiceStateChanged` | Report service state transition |
| `SERVICE_STDOUT` | `ServiceStdout` | Forward a stdout line from a service |
| `SERVICE_PLAYER_COUNT` | `ServicePlayerCount` | Report player count for a service |
| `COMMAND_RESULT` | `CommandResult` | Result of a forwarded command |
| `TEMPLATE_REQUEST` | `TemplateRequest` | Request a template download |
| `LOG_MESSAGE` | `LogMessage` | Forward a log message to the controller |

## Key message details

### StartService

The most complex message -- contains everything needed to launch a service: `serviceName`, `groupName`, `port`, `templateName`, `templateHash`, `software`, `version`, `memory`, `jvmArgs`, `jarName`, `forwardingMode`, `forwardingSecret`, `isStatic`, `isModded`, `apiUrl`, `apiToken`, `nimbusProperties`, `javaVersion`, `bedrockPort`, `bedrockEnabled`, and more. See `ClusterMessage.kt` for the full field list.

### AuthRequest

Sent by the agent after connecting: `token`, `nodeName`, `maxMemory`, `maxServices`, `currentServices`, `agentVersion`, `os`, `arch`.

### ServiceHeartbeat

Included in `HeartbeatResponse.services` -- per-service status with `serviceName`, `groupName`, `state`, `port`, `pid`, `playerCount`, and optional `customState`.

## ServiceHandle interface

The `ServiceHandle` interface (`dev.nimbus.service.ServiceHandle` in `nimbus-core`) abstracts over local and remote processes with methods for `stdoutLines`, `isAlive()`, `pid()`, `sendCommand()`, `waitForReady()`, `stopGracefully()`, `awaitExit()`, and `destroy()`. For remote services, the controller translates these calls into `ClusterMessage` instances sent over the WebSocket.

## Message flow examples

### Starting a service on a remote node

```
Controller                          Agent
    │                                 │
    ├── StartService ───────────────→ │
    │                                 ├── ensureTemplate() (download if hash mismatch)
    │                                 ├── copyTemplate() + patchConfigs()
    │                                 ├── launch JVM process
    │   ←── ServiceStateChanged ──────┤  (state=STARTING)
    │                                 ├── monitor stdout for ready pattern
    │   ←── ServiceStdout ────────────┤  (forwarded log lines)
    │   ←── ServiceStateChanged ──────┤  (state=READY)
    │                                 │
```

### Heartbeat cycle

```
Controller                          Agent
    │                                 │
    ├── HeartbeatRequest ───────────→ │
    │   ←── HeartbeatResponse ────────┤  (cpu, memory, service list)
    │                                 │
```

### Graceful shutdown

```
Controller                          Agent
    │                                 │
    ├── ShutdownAgent ──────────────→ │
    │                                 ├── stopAll() (send "stop" to each service)
    │                                 ├── close connections
    │                                 └── exit
```

## Next steps

- [Architecture](/developer/architecture) -- System overview and module structure
- [Agent Node](/developer/agent) -- Agent runtime details
- [WebSocket Reference](/reference/websocket) -- Client-facing event stream protocol
