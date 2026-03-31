# Nimbus SDK

The Nimbus SDK is a Java plugin for Minecraft servers (Paper/Purpur) managed by Nimbus. It provides a simple API for interacting with the cloud system -- setting game states, routing players, tracking services, and listening to events.

::: warning Folia
The SDK is **not compatible with Folia** servers. Folia's regionized multithreading breaks the SDK's main-thread assumptions. Nimbus automatically excludes the SDK (and ProtocolLib) from Folia services.
:::

## Installation

The SDK is **automatically deployed** to all Paper and Purpur backend servers via the `templates/global/plugins/` directory. You don't need to install it manually. Folia servers are excluded automatically.

For development, add it as a compile-only dependency in your plugin project:

```kotlin
// build.gradle.kts
compileOnly(files("libs/nimbus-sdk.jar"))
```

## Initialization

Call `Nimbus.init()` once in your plugin's `onEnable()`:

```java
@Override
public void onEnable() {
    Nimbus.init();
}

@Override
public void onDisable() {
    Nimbus.shutdown();
}
```

The SDK auto-discovers its identity from JVM system properties injected by Nimbus Core:

| Property | Example | Description |
|---|---|---|
| `nimbus.service.name` | `BedWars-1` | Service name |
| `nimbus.service.group` | `BedWars` | Group name |
| `nimbus.service.port` | `30001` | Server port |
| `nimbus.api.url` | `http://127.0.0.1:8080` | API endpoint |
| `nimbus.api.token` | `abc123...` | Bearer token |

For external tools not running on a Nimbus server, use explicit initialization:

```java
Nimbus.init("http://127.0.0.1:8080", "your-api-token");
```

## Quick reference

```java
// Identity
String name = Nimbus.name();           // "BedWars-1"
String group = Nimbus.group();         // "BedWars"
boolean managed = Nimbus.isManaged();  // true

// Custom state
Nimbus.setState("INGAME");
Nimbus.clearState();

// Routing
Nimbus.route("Steve", "BedWars", RoutingStrategy.LEAST_PLAYERS);
NimbusService best = Nimbus.bestServer("BedWars", RoutingStrategy.FILL_FIRST);

// Queries (from cache, instant)
List<NimbusService> servers = Nimbus.services("BedWars");
List<NimbusService> routable = Nimbus.routable("BedWars");
int players = Nimbus.players("BedWars");
int total = Nimbus.players();

// Events
Nimbus.on(ServiceReadyEvent.class, e -> { ... });
Nimbus.onChange("BedWars", services -> { ... });

// Messaging
Nimbus.message("Lobby-1", "game_ended", Map.of("winner", "Steve"));

// Tab names
Nimbus.setTabName(player.getUniqueId(), "<red>[RED] {player}");
Nimbus.clearTabName(player.getUniqueId());

// Direct access
NimbusClient client = Nimbus.client();
ServiceCache cache = Nimbus.cache();
```

## Custom states

Custom states control how the scaling engine and router treat a service. A service with a custom state is **not routable** -- it won't receive new players and doesn't count toward scaling capacity.

```java
// Game lifecycle example:
public class BedWarsGame {

    public void onGameStart() {
        Nimbus.setState("INGAME");  // Stop sending new players here
    }

    public void onGameEnd() {
        Nimbus.setState("ENDING");  // Still not routable during cleanup

        // Route all players to a new BedWars game
        for (Player player : getPlayers()) {
            Nimbus.route(player.getName(), "BedWars", RoutingStrategy.FILL_FIRST);
        }
    }

    public void onCleanupComplete() {
        Nimbus.clearState();  // Server is routable again
    }
}
```

Common states:

| State | Meaning |
|---|---|
| `"WAITING"` | Waiting for players to join (still routable if you clear state) |
| `"STARTING"` | Game is starting, lobby phase ending |
| `"INGAME"` | Game in progress |
| `"ENDING"` | Game over, cleanup phase |

::: tip
State names are arbitrary strings -- use whatever makes sense for your game mode. The scaling engine only cares whether a custom state is set (`!= null`) or not.
:::

## Routing

The SDK provides smart player routing with three strategies:

```java
// Send player to the server with the fewest players (spread evenly)
Nimbus.route("Steve", "BedWars", RoutingStrategy.LEAST_PLAYERS);

// Send player to the fullest server (pack tightly, better game feel)
Nimbus.route("Steve", "BedWars", RoutingStrategy.FILL_FIRST);

// Send player to a random server
Nimbus.route("Steve", "BedWars", RoutingStrategy.RANDOM);
```

`route()` returns a `CompletableFuture<NimbusService>` with the service the player was sent to:

```java
Nimbus.route("Steve", "BedWars", RoutingStrategy.LEAST_PLAYERS)
    .thenAccept(service -> {
        if (service != null) {
            System.out.println("Sent Steve to " + service.getName());
        } else {
            System.out.println("No BedWars server available!");
        }
    });
```

For cache-based routing (no API call, instant):

```java
NimbusService best = Nimbus.bestServer("BedWars", RoutingStrategy.LEAST_PLAYERS);
if (best != null) {
    Nimbus.client().sendPlayer("Steve", best.getName());
}
```

### Advanced routing with filters

Use the router directly for custom filtering:

```java
ServiceRouter router = Nimbus.router();

// Only route to servers with fewer than 15 players
router.routePlayer("Steve", "BedWars", RoutingStrategy.FILL_FIRST,
    service -> service.getPlayerCount() < 15);

// Find best across all groups
router.findBestGlobal(RoutingStrategy.LEAST_PLAYERS,
    service -> service.getGroupName().startsWith("Game"));
```

## Service cache

The `ServiceCache` maintains a real-time view of all services, updated via WebSocket events. All `Nimbus.services()` and `Nimbus.routable()` calls read from this cache -- no HTTP call required.

```java
ServiceCache cache = Nimbus.cache();

// Query (instant, from cache)
List<NimbusService> all = cache.getAll();
List<NimbusService> bedwars = cache.getByGroup("BedWars");
List<NimbusService> routable = cache.getRoutable("BedWars");
int total = cache.getTotalPlayers();
List<String> groups = cache.getGroupNames();

// React to changes
cache.onChange("BedWars", services -> {
    // Called when any BedWars service starts, stops, or changes state
    updateSigns(services);
});

cache.onAnyChange(services -> {
    // Called on any service change
    updateScoreboard(services);
});
```

## Player tracking

The `PlayerTracker` polls player counts and fires callbacks when they change:

```java
// React to player count changes
Nimbus.onPlayers("BedWars", (group, count) -> {
    updateHologram("BedWars: " + count + " playing");
});
```

Or use the tracker directly for more control:

```java
PlayerTracker tracker = new PlayerTracker(Nimbus.client(), 2); // poll every 2 seconds
tracker.start();

tracker.onPlayerCountChange("BedWars", (group, count) -> {
    updateHologram("BedWars: " + count + " playing");
});

tracker.onTotalPlayersChange(total -> {
    updateScoreboard("Online: " + total);
});

int count = tracker.getPlayerCount("BedWars");
int total = tracker.getTotalPlayers();
```

## Events

### Typed events

Type-safe event handling with auto-deserialization:

```java
import dev.nimbus.sdk.event.*;

Nimbus.on(ServiceReadyEvent.class, event -> {
    System.out.println(event.getServiceName() + " is ready!");
});

Nimbus.on(CustomStateChangedEvent.class, event -> {
    System.out.println(event.getServiceName() + ": "
        + event.getOldState() + " -> " + event.getNewState());
});

Nimbus.on(ScaleUpEvent.class, event -> {
    System.out.println("Scaling up " + event.getGroupName()
        + ": " + event.getCurrentInstances() + " -> " + event.getTargetInstances());
});

Nimbus.on(PlayerConnectedEvent.class, event -> {
    System.out.println(event.getPlayerName() + " joined " + event.getServiceName());
});
```

Available typed events:

| Class | When fired |
|---|---|
| `ServiceStartingEvent` | Service is starting up |
| `ServiceReadyEvent` | Service is ready for players |
| `ServiceStoppedEvent` | Service has stopped |
| `ServiceCrashedEvent` | Service crashed unexpectedly |
| `CustomStateChangedEvent` | Custom state changed |
| `ScaleUpEvent` | Scaling engine started a new instance |
| `ScaleDownEvent` | Scaling engine stopped an instance |
| `PlayerConnectedEvent` | Player connected to a service |
| `PlayerDisconnectedEvent` | Player disconnected from a service |
| `ServiceMessageEvent` | Service-to-service message received |
| `TabListUpdatedEvent` | Tab list config changed |
| `MotdUpdatedEvent` | MOTD config changed |
| `PlayerTabUpdatedEvent` | Per-player tab override set or cleared |
| `ChatFormatUpdatedEvent` | Chat format changed |

### Raw events

For events without a typed wrapper:

```java
Nimbus.on("GROUP_CREATED", event -> {
    String groupName = event.get("group");
    System.out.println("New group: " + groupName);
});
```

### Change listeners

React to group-level changes:

```java
// Called when BedWars services change (start, stop, state change)
Nimbus.onChange("BedWars", services -> {
    int available = (int) services.stream()
        .filter(NimbusService::isRoutable)
        .count();
    System.out.println(available + " BedWars servers available");
});
```

## Service-to-service messaging

Send messages between services on named channels:

```java
// Send a message to a specific service
Nimbus.message("Lobby-1", "game_ended", Map.of(
    "winner", "Steve",
    "map", "Castle",
    "duration", "340"
));

// Send without data
Nimbus.message("Lobby-1", "restart_warning");
```

Listen for messages on the receiving service:

```java
Nimbus.on(ServiceMessageEvent.class, event -> {
    if ("game_ended".equals(event.getChannel())) {
        String winner = event.getData().get("winner");
        announceWinner(winner);
    }
});
```

## Permission integration

The SDK includes `NimbusPermissible`, which injects into Paper's permission system to support wildcard matching:

- `nimbus.cloud.*` matches `nimbus.cloud.list`, `nimbus.cloud.status`, etc.
- `*` matches all permissions
- Exact matches are checked first, then wildcards

This is handled automatically by the SDK plugin -- no code needed in your game plugin. Permissions are loaded from the Nimbus [permission system](/config/permissions) and synced in real-time.

## Display configuration

Access group display configs (used by signs and NPCs) from the API:

```java
NimbusDisplay display = Nimbus.client().getDisplay("BedWars").join();

String line1 = display.getSignLine1();         // "&1&l★ BedWars ★"
String state = display.resolveState("INGAME"); // "In Game" (from display config)
```

## NimbusService object

All service queries return `NimbusService` instances:

```java
NimbusService service = Nimbus.cache().get("BedWars-1");

service.getName();         // "BedWars-1"
service.getGroupName();    // "BedWars"
service.getPort();         // 30001
service.getState();        // "READY"
service.getCustomState();  // "INGAME" or null
service.getPlayerCount();  // 12
service.getStartedAt();    // ISO timestamp
service.getUptime();       // "2h 15m"
service.isReady();         // true if state == "READY"
service.isRoutable();      // true if READY and no custom state
```

## Next steps

- [Architecture](/developer/architecture) -- How the system fits together
- [Bridge Plugin](/developer/bridge) -- Proxy-side plugin reference
- [Auto-Scaling](/guide/scaling) -- How custom states affect scaling
- [API Reference](/reference/api) -- REST API documentation
