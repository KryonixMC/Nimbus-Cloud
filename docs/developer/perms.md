# NimbusPerms Plugin

NimbusPerms is a Java plugin for Paper/Purpur backend servers that bridges the Nimbus permission system to in-game functionality. It handles permission injection, chat formatting, and name tag display using prefix/suffix data from the controller.

::: warning Folia
NimbusPerms is **not compatible with Folia** servers. Nimbus automatically excludes it from Folia services due to Folia's regionized threading model.
:::

## Architecture

The plugin entry point is `NimbusPermsPlugin` (`dev.nimbus.perms.NimbusPermsPlugin`), a standard `JavaPlugin` that reads its API connection from JVM system properties (`nimbus.api.url`, `nimbus.api.token`). It requires the [Nimbus SDK](/developer/sdk) and only activates on Nimbus-managed services.

On enable, it selects a `PermissionProvider` implementation, then starts the `ChatRenderer` and `NameTagHandler` display components. Player join/quit events are forwarded to all three systems.

```
NimbusPermsPlugin
├── PermissionProvider (interface)
│   ├── BuiltinProvider   → fetches from Nimbus REST API
│   └── LuckPermsProvider → delegates to LuckPerms, syncs display to API
├── ChatRenderer          → Paper AsyncChatEvent renderer
└── NameTagHandler        → Scoreboard Team prefix/suffix
```

## Permission providers

### PermissionProvider interface

All providers implement `dev.nimbus.perms.provider.PermissionProvider`:

```java
public interface PermissionProvider {
    void enable(NimbusPermsPlugin plugin);
    void disable();
    void onJoin(Player player);
    void onQuit(Player player);
    void refresh(UUID uuid);
    void refreshAll();
    String getPrefix(UUID uuid);
    String getSuffix(UUID uuid);
    int getPriority(UUID uuid);
}
```

### Built-in provider

The default provider (`BuiltinProvider`) fetches permissions directly from the Nimbus REST API:

1. On player join, sends `PUT /api/permissions/players/{uuid}` with the player's name
2. Parses the response for `effectivePermissions`, `prefix`, `suffix`, and `displayGroup`
3. Applies permissions via `PermissionAttachment`, supporting wildcards (`*`, `nimbus.cloud.*`) and negations (`-some.permission`)
4. Listens on the WebSocket event stream for `PERMISSION_GROUP_CREATED`, `PERMISSION_GROUP_UPDATED`, `PERMISSION_GROUP_DELETED`, and `PLAYER_PERMISSIONS_UPDATED` events to trigger live refreshes

### LuckPerms provider

When `provider: luckperms` is set in config and LuckPerms is installed, the `LuckPermsProvider` activates:

- LuckPerms handles all permission injection natively -- NimbusPerms does **not** manage `PermissionAttachment` objects
- Display data (prefix, suffix, primary group, weight) is read from LuckPerms `CachedMetaData`
- On join and on LuckPerms `UserDataRecalculateEvent`, display info is cached locally and synced to the Nimbus API so the controller has current data for proxy features (tab list, MOTD)
- On `GroupDataRecalculateEvent`, all online players are refreshed

## Chat rendering

`ChatRenderer` implements Paper's `io.papermc.paper.chat.ChatRenderer` interface and listens on `AsyncChatEvent` at `LOWEST` priority.

The format string is fetched from `GET /api/proxy/chat` at startup and updated live via `CHAT_FORMAT_UPDATED` WebSocket events. Available placeholders:

| Placeholder | Value |
|---|---|
| `{prefix}` | Player's permission group prefix |
| `{suffix}` | Player's permission group suffix |
| `{player}` | Player name |
| `{message}` | Chat message content |
| `{server}` | Current service name (e.g. `Lobby-1`) |
| `{group}` | Current group name (e.g. `Lobby`) |

The format string supports MiniMessage tags and legacy `&` color codes (translated via `ColorUtil.translate()`).

## Name tag handling

`NameTagHandler` uses Scoreboard Teams to display prefix/suffix above player heads and in the tab list. Teams are named with the `nimbus_` prefix followed by a priority-based sort key (`9999 - priority`, zero-padded to 4 digits), ensuring higher-priority groups appear first in the tab list.

Key behaviors:

- Tags are applied 5 ticks after join to allow the provider to load display data
- Per-player tab overrides are supported via `PLAYER_TAB_UPDATED` events (set through the SDK's `Nimbus.setTabName()`)
- When a player quits, their team entry is removed and empty teams are unregistered
- `PERMISSION_GROUP_UPDATED` events trigger a full refresh of all online players

## Wildcard permission injection

`PermissibleInjector` (`dev.nimbus.perms.handler.PermissibleInjector`) extends `PermissibleBase` to add wildcard matching that Bukkit does not support natively:

- `*` grants all permissions
- `some.node.*` grants all permissions starting with `some.node.`
- Exact matches are checked first, then progressively broader wildcard patterns

## Configuration

NimbusPerms uses a standard Bukkit `config.yml` with one key setting:

```yaml
# Permission provider: "builtin" or "luckperms"
provider: builtin
```

The plugin is auto-deployed to backend servers when `[permissions].deploy_plugin = true` in the Nimbus controller config. No manual installation is needed.

## How permissions sync

```
Controller (nimbus-core)
  │
  ├── REST API: /api/permissions/players/{uuid}
  │     ← BuiltinProvider fetches on join
  │     ← LuckPermsProvider syncs display data back
  │
  └── WebSocket: /api/events
        → PERMISSION_GROUP_UPDATED  → refreshAll()
        → PLAYER_PERMISSIONS_UPDATED → refresh(uuid)
        → CHAT_FORMAT_UPDATED       → update chat format
        → PLAYER_TAB_UPDATED        → update tab override
```

The controller is the single source of truth for built-in permissions. With LuckPerms, the LuckPerms database is authoritative for permissions, but display data is synced back to the controller so proxy-level features (tab list formatting, MOTD player counts) have access to prefix/suffix information.

## Next steps

- [Architecture](/developer/architecture) -- System overview and module structure
- [SDK](/developer/sdk) -- Backend server plugin API
- [Permissions Config](/config/permissions) -- Setting up permission groups on the controller
