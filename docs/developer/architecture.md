# Architecture

This page covers Nimbus's internal architecture for developers who want to understand how the system works, contribute to the codebase, or build integrations.

## Module structure

Nimbus is organized into eleven modules:

```
nimbus-core (Controller)
├── api/           → Ktor REST + WebSocket server
├── cluster/       → NodeManager, NodeConnection, PlacementStrategy, ClusterWebSocketHandler
├── config/        → TOML configuration loading
├── console/       → JLine3 interactive REPL
├── event/         → Coroutine-based EventBus
├── group/         → Server group state management
├── loadbalancer/  → Layer-4 TCP load balancer for Velocity proxies
├── scaling/       → Auto-scaling engine + Velocity updater
├── service/       → Service lifecycle, process management
├── template/      → Template copying, software download
├── setup/         → First-run wizard
├── module/        → ModuleManager, ModuleContextImpl (dynamic module loading)
├── proxy/         → Proxy sync (tab list, MOTD, chat format, maintenance)
└── velocity/      → Velocity config generation

nimbus-module-api (Module API)
├── NimbusModule          → Module interface
├── ModuleContext          → Service access for modules
├── ModuleCommand          → Console command interface
└── AuthLevel              → Route auth levels

nimbus-protocol (Shared Protocol)
├── ClusterMessage        → Sealed class for all controller ↔ agent messages
└── ServiceHandle         → Interface for local and remote service process handles

nimbus-agent (Agent Node)
├── NimbusAgent           → Entry point, connects to controller
├── AgentConfig           → TOML config (controller host, token, resources)
├── ControllerConnection  → WebSocket client, message dispatch
├── LocalServiceManager   → Launches/stops JVM processes on the agent
└── TemplateSync          → Downloads and caches templates from controller

nimbus-bridge (Velocity Plugin)
├── CloudCommand          → /cloud subcommands (status, list, start, stop, etc.)
├── NimbusBridgePlugin    → Plugin entry point, event listeners
├── NimbusApiClient       → HTTP client for Nimbus API
├── NimbusPermissionProvider → Velocity permission integration
├── ProxySyncListener     → Tab list, MOTD, chat sync
└── BridgeConfig          → API connection config

nimbus-sdk (Paper Plugin)
├── Nimbus                → Static facade (main entry point)
├── NimbusClient          → HTTP client for REST API
├── NimbusSelfService     → Self-identity from JVM properties
├── ServiceCache          → Reactive service cache via WebSocket
├── ServiceRouter         → Smart player routing
├── PlayerTracker         → Real-time player count tracking
├── NimbusEventStream     → WebSocket event subscription
├── NimbusPermissible     → Wildcard permission injection
├── NimbusDisplay         → Display config access
├── NimbusChatRenderer    → Chat color rendering
└── RoutingStrategy       → LEAST_PLAYERS, FILL_FIRST, RANDOM

nimbus-perms (Paper Plugin)
├── NimbusPermsPlugin      → Plugin entry point
├── NimbusPermProvider     → Built-in permission provider
├── LuckPermsProvider      → Optional LuckPerms integration
├── ChatRenderer           → Prefix/suffix chat display
└── NameTagHandler         → Tab list name tag formatting

nimbus-display (Paper Plugin)
├── DisplayCommand         → /ndisplay command (signs + NPCs)
├── NpcRenderer            → FancyNpcs integration, holograms, floating items
├── NpcManager             → NPC lifecycle + hologram updates
├── NpcListener            → FancyNpcs NpcInteractEvent handling
├── NpcInventory           → Server selector GUI (InventoryHolder)
├── SignManager            → Sign lifecycle + rendering
├── SignConfig             → YAML persistence
└── SignListener           → Click-to-join handling

nimbus-module-perms (Permissions Module)
├── PermissionManager, PermissionGroup, PermissionTrack
├── PermissionRoutes, PermsCommand
└── PermissionTables (Exposed DB tables)

nimbus-module-display (Display Module)
├── DisplayManager, DisplayConfig
└── DisplayRoutes

```

## Bootstrap flow

When Nimbus starts (`Nimbus.kt` → `nimbusMain()`), components are initialized in this order:

```
1. Log rotation           → Rotate latest.log to dated archives
2. Setup wizard           → First-run interactive setup (if needed)
3. Config loading         → Parse config/nimbus.toml + config/groups/*.toml
4. API token generation   → Auto-generate if missing
5. Directory creation     → Ensure templates/, services/, logs/, etc. exist
6. Plugin deployment      → Extract nimbus-bridge.jar, nimbus-sdk.jar
7. Component init         → EventBus, ServiceRegistry, PortAllocator,
                            TemplateManager, GroupManager, ProxySyncManager
8. Group loading          → Parse group configs into GroupManager
9. Module loading         → ModuleManager scans modules/, loads JARs via
                            ServiceLoader, calls init() then enable().
                            Modules register their own commands and API routes
                            during init.
10. ServiceManager        → Wire up all dependencies
11. ScalingEngine         → Start periodic scaling loop
12. NimbusApi             → Create (but don't start) Ktor server
13. NodeManager           → If cluster.enabled: init cluster coordination
14. ClusterServer         → If cluster.enabled: separate Ktor server on agent_port for agent WebSocket connections
15. TcpLoadBalancer       → If loadbalancer.enabled: init Layer-4 TCP proxy
                            with BackendHealthManager (health checks, circuit breaker,
                            connection draining, idle timeout, connection limit)
16. Shutdown hook         → Register SIGTERM/SIGINT handler
17. NimbusConsole.init()  → Banner, event listener
18. Api.start()           → Start Ktor HTTP server (+ /cluster WS if cluster enabled)
19. LoadBalancer.start()  → If enabled: start TCP listener on LB port + health check loop
20. VelocityUpdater       → Start periodic update check (first check after 60s)
21. startMinimumInstances → Start min_instances for all groups
22. Console.start()       → JLine3 REPL (blocks until shutdown)
```

::: info
The shutdown hook and console REPL provide two paths to shutdown: external signals (SIGTERM) use the hook, while the `shutdown` command exits the REPL.
:::

## Coroutine architecture

Nimbus uses `kotlinx-coroutines` for all async work. No raw threads are created in the core module.

```kotlin
// Root scope with SupervisorJob (child failures don't cancel siblings)
val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

// EventBus uses the shared scope for event dispatch
val eventBus = EventBus(scope)

// Scaling engine launches a long-running coroutine
val scalingJob = scalingEngine.start()  // Returns a Job
```

Key patterns:

- **SupervisorJob** -- A failing coroutine (e.g., scaling error) doesn't bring down the whole system
- **Dispatchers.Default** -- CPU-bound work (most coordination)
- **Dispatchers.IO** -- Used in ServiceManager for process I/O and file operations
- **MutableSharedFlow** -- EventBus uses a shared flow with `extraBufferCapacity = 64`

## Event bus

The event bus is the central communication backbone. All state changes are published as sealed class events.

```kotlin
// Emitting an event (suspend function)
eventBus.emit(NimbusEvent.ServiceReady(serviceName = "BedWars-1", groupName = "BedWars"))

// Subscribing to a specific event type
eventBus.on<NimbusEvent.ServiceReady> { event ->
    println("${event.serviceName} is ready!")
}

// Getting the raw SharedFlow
val flow: SharedFlow<NimbusEvent> = eventBus.subscribe()
```

### Event types

Events are modeled as a `sealed class NimbusEvent`:

| Category | Events |
|---|---|
| Service lifecycle | `ServiceStarting`, `ServiceReady`, `ServiceStopping`, `ServiceStopped`, `ServiceCrashed` |
| Scaling | `ScaleUp`, `ScaleDown` |
| Custom state | `ServiceCustomStateChanged` |
| Players | `PlayerConnected`, `PlayerDisconnected` |
| Groups | `GroupCreated`, `GroupUpdated`, `GroupDeleted` |
| Messaging | `ServiceMessage` |
| Permissions | `PermissionGroupCreated`, `PermissionGroupUpdated`, `PermissionGroupDeleted`, `PlayerPermissionsUpdated` |
| Proxy updates | `ProxyUpdateAvailable`, `ProxyUpdateApplied` |
| Proxy sync | `TabListUpdated`, `MotdUpdated`, `PlayerTabUpdated`, `ChatFormatUpdated` |
| Config | `ConfigReloaded` |
| API | `ApiStarted`, `ApiStopped`, `ApiWarning`, `ApiError` |
| Cluster | `NodeConnected`, `NodeDisconnected`, `NodeHeartbeat` |
| Load balancer | `LoadBalancerStarted`, `LoadBalancerStopped`, `LoadBalancerBackendHealthChanged` |

Events are broadcast via the REST API's WebSocket endpoint (`/api/events`) so external systems and plugins can react to them.

## Process management

Each running service is wrapped in a `ServiceHandle` interface that abstracts process management across local and remote nodes:

- **`ProcessHandle`** -- Local JVM subprocess started via `ProcessBuilder` with inherited environment
- **`RemoteServiceHandle`** -- Proxy for services running on remote agent nodes, communicating via the cluster WebSocket protocol

**Static services always run on the controller.** They have persistent data (worlds, configs) stored in `services/static/` and are never placed on remote nodes. Dynamic and proxy services can be distributed across any node.

Both implementations provide:

- **stdout/stderr** -- Captured asynchronously via `SharedFlow<String>` for ready detection and logging
- **Ready detection** -- Regex matching against stdout (default pattern: `Done`)
- **Graceful shutdown** -- Sends `stop` command via stdin, then waits, then force-kills

### Service states

```
PREPARING → STARTING → READY → STOPPING → STOPPED
                 ↓                              ↑
               CRASHED ─── (restart?) ──────────┘
```

| State | Description |
|---|---|
| `PREPARING` | Template being copied to service directory |
| `STARTING` | JVM started, scanning stdout for ready pattern |
| `READY` | Server accepting players |
| `STOPPING` | Graceful shutdown in progress |
| `STOPPED` | Clean shutdown complete |
| `CRASHED` | Unexpected exit (triggers restart if `restart_on_crash = true`) |

## Velocity config generation

The `VelocityConfigGen` class keeps the proxy's `velocity.toml` in sync with running backend services:

1. Finds all `READY` backend services (non-VELOCITY groups)
2. Builds the `[servers]` section with `ServiceName = "127.0.0.1:port"` entries
3. Sets the `try` list to lobby servers (groups containing "lobby")
4. Replaces the `[servers]` and `[forced-hosts]` sections in `velocity.toml`

This runs automatically whenever a backend service becomes `READY` or stops.

## Plugin deployment

Nimbus embeds plugin JARs as classpath resources and deploys them at boot:

| Plugin | Target | Auto-deployed |
|---|---|---|
| `nimbus-bridge.jar` | `templates/global_proxy/plugins/` | Always |
| `nimbus-sdk.jar` | `templates/global/plugins/` | Always |
| `nimbus-display.jar` | `plugins/` (root) | Extracted for manual use |
| `FancyNpcs.jar` | `templates/global/plugins/` | Always (required by nimbus-display) |

Plugin tracking (`.nimbus-plugins` file) ensures:
- If a user removes a plugin JAR, Nimbus won't re-deploy it
- If a plugin exists, Nimbus overwrites it with the latest version

## Shutdown order

When Nimbus shuts down:

```
1. Cancel scaling engine job
2. Send graceful shutdown to all agents (if cluster enabled, wait up to 30s)
3. Stop TCP load balancer (if enabled)
4. Stop REST API server
5. Disconnect cluster nodes (if enabled)
6. Stop all local services (via ServiceManager.stopAll()):
   a. Dynamic (game) services first
   b. Static backend (lobby) services
   c. Proxy services last
7. Stop cluster WebSocket server (if enabled)
8. Cancel coroutine scope
```

This order ensures players are moved to lobbies before lobbies shut down, and proxies stay alive as long as possible to handle redirects.

## Technology choices

| Component | Technology | Rationale |
|---|---|---|
| Language | Kotlin 2.1 | Coroutines, sealed classes, null safety |
| Build | Gradle + Shadow | Fat JAR packaging with embedded plugins |
| Config | ktoml | Native TOML parsing for Kotlin |
| Console | JLine 3 | Rich terminal with history, completion, ANSI colors |
| Async | kotlinx-coroutines | Structured concurrency, no callback hell |
| HTTP server | Ktor (CIO) | Lightweight, coroutine-native |
| HTTP client | Ktor Client (CIO) | Server JAR downloads |
| SDK client | Java HttpClient | Zero dependencies for Paper/Velocity plugins |
| Cluster protocol | kotlinx-serialization JSON | Typed messages between controller and agents |
| Load balancer | Java NIO (ServerSocketChannel) | Non-blocking TCP proxy with zero dependencies |
| Module system | ServiceLoader + URLClassLoader | Dynamic module loading without framework overhead |

The core design principle is **no frameworks** -- no Spring, no dependency injection containers. Components are wired directly in `Nimbus.kt`, making the startup path explicit and debuggable.

## Next steps

- [Module Development](/developer/modules) -- Building custom modules
- [SDK](/developer/sdk) -- Backend server plugin API
- [Bridge Plugin](/developer/bridge) -- Velocity proxy plugin
- [Display Plugin](/developer/display) -- Signs + NPC management
- [WebSocket Reference](/reference/websocket) -- Event stream protocol
