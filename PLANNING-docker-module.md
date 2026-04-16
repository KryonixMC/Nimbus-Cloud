# Docker Module — Feature Plan

> Status: **PLANNING** | Author: Jonas + Claude | Date: 2026-04-16

## Goal

Optional Docker support for Nimbus. Services can run as Docker containers instead of bare Java processes. Users without Docker keep the current behavior — zero breaking changes.

---

## Why

- **Hard resource limits** — Container memory/CPU caps are enforced by the kernel, not just JVM flags
- **Isolation** — A buggy plugin can't escape its container or affect other services
- **Clean cleanup** — Container stop = everything gone, no zombie processes or leftover temp files
- **Reproducibility** — Same image = same behavior on every node
- **Mixed Java versions** — Java 17 and Java 21 services side by side, no host conflicts

## Non-Goals

- Kubernetes support (Nimbus IS the orchestrator, k8s underneath would be redundant)
- Replacing the existing process-based backend (Docker is opt-in, not mandatory)
- Custom Dockerfile authoring by end users (Nimbus builds/manages images internally)

---

## Architecture

### How It Fits

```
ServiceManager
  ├── startLocalService()
  │     ├── ProcessHandle        ← existing (bare Java process)
  │     └── DockerServiceHandle  ← NEW (Docker container)
  └── startRemoteService()
        └── RemoteServiceHandle  ← existing (proxies to agent node)
                                    Agent decides locally: Process or Docker
```

The existing `ServiceHandle` interface is the integration point:

```kotlin
interface ServiceHandle {
    val stdoutLines: SharedFlow<String>
    fun isAlive(): Boolean
    fun pid(): Long?        // → returns container ID or null
    fun exitCode(): Int?
    suspend fun sendCommand(command: String)
    suspend fun waitForReady(timeout: Duration): Boolean
    suspend fun stopGracefully(timeout: Duration)
    suspend fun awaitExit(): Int?
    fun destroy()
}
```

A new `DockerServiceHandle` implements this interface. `ServiceManager` picks the right handle based on config. Everything above (scaling, health checks, console, API) stays untouched.

### New Components

| Component | Location | Purpose |
|-----------|----------|---------|
| `DockerServiceHandle` | `nimbus-core/.../service/docker/` | `ServiceHandle` impl using Docker API |
| `DockerClient` | `nimbus-core/.../service/docker/` | Thin wrapper around Docker Engine API (Unix socket / TCP) |
| `DockerImageManager` | `nimbus-core/.../service/docker/` | Builds/caches service images from templates |
| `DockerModule` | `modules/docker/` | Module entry point, config, commands, routes |
| `DockerConfig` | `modules/docker/` | TOML config for Docker settings |

### Decision: Docker Engine API vs. CLI

**Use the Docker Engine API** (REST over Unix socket `/var/run/docker.sock`), not `docker` CLI commands.

Reasons:
- No dependency on Docker CLI being installed/in PATH
- Structured JSON responses instead of parsing CLI output
- Streaming logs via HTTP chunked transfer (maps directly to `SharedFlow<String>`)
- Container lifecycle events via Docker Events API
- Ktor Client (already a dependency) can talk to Unix sockets

Fallback: If socket not available, try TCP (`tcp://localhost:2375`). Configurable.

---

## Configuration

### Global Docker Config

```toml
# config/modules/docker/docker.toml

[docker]
enabled = true
socket = "/var/run/docker.sock"       # or "tcp://localhost:2375"
# socket = "npipe:////./pipe/docker_engine"  # Windows named pipe

[docker.defaults]
memory_limit = "2g"                    # default per-container, overridable per group
cpu_limit = 2.0                        # CPU cores
network = "nimbus"                     # Docker network name (auto-created)
java_image = "eclipse-temurin:21-jre"  # default base image
restart_policy = "no"                  # Nimbus handles restarts, not Docker

[docker.images]
# Override base image per Java version
java_17 = "eclipse-temurin:17-jre"
java_21 = "eclipse-temurin:21-jre"
```

### Per-Group Override

```toml
# config/groups/BedWars.toml

[docker]
enabled = true            # opt-in per group (inherits global default)
memory_limit = "4g"       # override for this group
cpu_limit = 3.0
java_image = "eclipse-temurin:21-jre"
```

### Per-Dedicated Override

```toml
# config/dedicated/SMP.toml

[docker]
enabled = true
memory_limit = "8g"
cpu_limit = 4.0
```

---

## Service Lifecycle with Docker

### 1. Prepare (ServiceFactory — minimal changes)

Existing flow stays the same:
1. Allocate port + slot
2. Resolve template stack → copy to work directory
3. Download server JAR if needed
4. Deploy module plugins
5. Patch configs (server.properties, forwarding, etc.)
6. Generate JVM command

**Change:** When Docker is enabled for this group, `ServiceFactory` additionally returns a `DockerConfig` in `PreparedService` with resource limits and image info.

### 2. Start (ServiceManager — handle selection)

```
if (dockerEnabled for this service)
    → DockerServiceHandle.start(preparedService)
else
    → ProcessHandle.start(workDir, command, env)
```

**DockerServiceHandle.start():**
1. Create container via Docker API:
   - Image: `eclipse-temurin:21-jre` (or configured)
   - Bind mount: `workDir` → `/server` in container
   - Port mapping: allocated port → `25565` inside container
   - Memory/CPU limits from config
   - Environment variables (NIMBUS_API_TOKEN, etc.)
   - Working directory: `/server`
   - Command: `java -jar server.jar ...` (JVM args from ServiceFactory)
2. Start container
3. Attach to stdout stream → pipe into `SharedFlow<String>`
4. Return handle

### 3. Console I/O

- **Stdout:** Docker API `GET /containers/{id}/logs?follow=true&stdout=true` → stream to `SharedFlow`
- **Stdin (sendCommand):** Docker API `POST /containers/{id}/exec` + attach → write command
  - Alternative: If server reads stdin, use `POST /containers/{id}/attach` with stdin stream

### 4. Ready Detection

Same as current: watch `stdoutLines` for "Done (" pattern. No change needed — the abstraction already handles this.

### 5. Stop

```kotlin
suspend fun stopGracefully(timeout: Duration) {
    sendCommand("stop")                          // MC server graceful stop
    val exited = waitForExit(timeout - 5.seconds)
    if (!exited) {
        dockerClient.stopContainer(id, timeout = 5) // SIGTERM → SIGKILL
    }
}
```

### 6. Crash Recovery

Current `ProcessHandle.adopt(pid)` won't work. Instead:
- On startup, `DockerServiceHandle` queries Docker for containers with label `nimbus.service=<name>`
- Reconnects to running containers (reattach stdout, resume monitoring)
- Removes dead containers from previous crashes

### 7. Memory Reading

Current: reads `/proc/<pid>/status` for RSS.
Docker: `GET /containers/{id}/stats` → `memory_stats.usage` from Docker API. More accurate than `/proc` parsing.

---

## Networking

### Docker Network

Auto-create a `nimbus` bridge network on first use:
```
POST /networks/create { "Name": "nimbus", "Driver": "bridge" }
```

All Nimbus containers join this network → can reach each other by container name.

### Port Mapping

- **Backend servers:** Map `allocatedPort` (host) → `25565` (container)
- **Proxy servers:** Map `25565` (host) → `25577` (container, Velocity default)
- **API access:** Container reaches controller API via Docker network or host.docker.internal

Port allocation stays with Nimbus `PortAllocator` — no change needed.

---

## Agent Node Support

Agents already run services via `RemoteServiceHandle` (cluster protocol). For Docker on agents:

1. Agent checks if Docker is available locally
2. Agent uses `DockerServiceHandle` instead of `ProcessHandle` when configured
3. Controller doesn't care — it talks to `RemoteServiceHandle` either way
4. Agent reports container stats in heartbeat (memory from Docker API instead of `/proc`)

No cluster protocol changes needed.

---

## Module Structure

```
modules/docker/
├── build.gradle.kts
└── src/main/kotlin/dev/nimbuspowered/nimbus/module/docker/
    ├── DockerModule.kt            # Module entry point (init, enable, disable)
    ├── DockerConfig.kt            # TOML config data classes
    ├── DockerClient.kt            # Docker Engine API client (Ktor over Unix socket)
    ├── DockerServiceHandle.kt     # ServiceHandle implementation
    ├── DockerImageManager.kt      # Image pull/cache management
    ├── DockerNetworkManager.kt    # Bridge network setup
    └── commands/
        └── DockerCommand.kt       # Console commands (docker status, docker ps, etc.)
```

### Console Commands

```
docker status          # Docker connection status, version, running containers
docker ps              # List Nimbus-managed containers
docker inspect <name>  # Container details (resource usage, ports, image)
docker logs <name>     # Tail container logs (alternative to screen)
docker prune           # Remove stopped Nimbus containers + unused images
```

### API Routes

```
GET  /api/docker/status              # Docker daemon status + version
GET  /api/docker/containers          # All Nimbus-managed containers
GET  /api/docker/containers/{name}   # Container details
POST /api/docker/prune               # Cleanup
```

### Dashboard Integration

- Service detail page: show "Docker" badge + container info (image, resource usage, container ID)
- Docker status in Doctor page
- Resource usage graphs from Docker stats (more accurate than /proc)

---

## Core Changes Required

### Minimal, non-breaking changes to nimbus-core:

1. **`ServiceHandle` interface** — No changes needed (already perfect)

2. **`PreparedService`** — Add optional Docker config:
   ```kotlin
   data class PreparedService(
       // ... existing fields ...
       val dockerConfig: DockerServiceConfig? = null  // null = use ProcessHandle
   )
   ```

3. **`ServiceFactory`** — Populate `dockerConfig` from group/dedicated config when Docker module is active

4. **`ServiceManager.startLocalService()`** — Handle selection:
   ```kotlin
   val handle = if (prepared.dockerConfig != null) {
       dockerModule.createHandle(prepared)
   } else {
       ProcessHandle().also { it.start(prepared.workDir, prepared.command, prepared.env) }
   }
   ```

5. **`ProcessMemoryReader`** — Docker module registers an alternative memory source; `ServiceMemoryResolver` checks Docker stats first if available

6. **`ServiceManager.recoverLocalServices()`** — Call Docker module for container recovery instead of `ProcessHandle.adopt()`

---

## Rollout Plan

### Phase 1: Foundation
- [ ] `DockerClient` — Engine API communication (create, start, stop, logs, stats, exec)
- [ ] `DockerServiceHandle` — Full `ServiceHandle` implementation
- [ ] `DockerModule` — Config loading, Docker availability detection
- [ ] Core changes: `PreparedService.dockerConfig`, handle selection in `ServiceManager`
- [ ] Basic `docker status` + `docker ps` commands

### Phase 2: Production Ready
- [ ] Container crash recovery (reconnect on controller restart)
- [ ] Memory/CPU stats via Docker API (replace /proc reading)
- [ ] Docker network auto-setup
- [ ] Agent node Docker support
- [ ] Image pull progress reporting
- [ ] `docker prune` command

### Phase 3: Polish
- [ ] API routes for dashboard
- [ ] Dashboard UI integration (container badges, stats)
- [ ] Per-group Java image override
- [ ] Docker health check integration (alternative to stdout pattern matching)
- [ ] Documentation + setup guide

---

## Open Questions

1. **Image strategy:** Pull public images (eclipse-temurin) on demand, or pre-build custom Nimbus images with server JAR baked in? Pre-built = faster startup but more disk. On-demand + bind mount = simpler, current approach just works in a container.
   → **Leaning toward: bind mount (Phase 1), optional pre-built images (later)**

2. **Windows support:** Docker Desktop on Windows uses WSL2 backend. Unix socket path differs (`npipe:////./pipe/docker_engine`). Needs testing.
   → **Leaning toward: support it, but Linux-first**

3. **Rootless Docker:** Some setups use rootless Docker (socket at `$XDG_RUNTIME_DIR/docker.sock`). Auto-detect?
   → **Leaning toward: configurable socket path, document common locations**

4. **Podman compatibility:** Podman is Docker API-compatible. Should work out of the box, but needs testing.
   → **Leaning toward: "best effort" Podman support, not officially guaranteed**

---

## Dependencies

- **Ktor Client** — Already in project, needs Unix Domain Socket support (available via `ktor-client-cio` on JVM 16+)
- **No new libraries needed** — Docker Engine API is plain HTTP + JSON over a Unix socket

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Docker not installed on user's system | High | None | Feature is opt-in, graceful fallback to processes |
| Docker API version incompatibility | Low | Medium | Target API v1.41+ (Docker 20.10+, ~2021), wide compat |
| Performance overhead of containers | Low | Low | Negligible for long-running Java processes |
| Stdout streaming lag | Low | Medium | Docker logs API is real-time, same as process stdout |
| Windows Docker Desktop quirks | Medium | Medium | Linux-first, Windows "best effort" with testing |
