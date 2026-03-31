# Permissions Guide

Nimbus includes a built-in permission system that works out of the box with zero extra configuration on backend servers. All permission data is stored centrally on the controller and synced to every server in real-time. This guide walks through practical setup -- for the full config reference, see [Permission System](/config/permissions).

## Built-in vs LuckPerms

Nimbus supports two providers:

| | Built-in | LuckPerms |
|---|---|---|
| **Setup** | Zero config -- works immediately | Requires LuckPerms + shared database |
| **Storage** | Controller database (SQLite/MySQL/PostgreSQL) | LuckPerms database |
| **Management** | Console `perms` commands, REST API, `/cloud perms` in-game | LuckPerms web editor + commands |
| **Proxy integration** | Automatic (tab list, chat prefixes) | Nimbus syncs display data from LuckPerms |
| **Best for** | New networks, simple setups | Migrating from LuckPerms, advanced features |

If you are starting fresh, use the built-in system. It handles everything from a single place.

## Quick walkthrough

### 1. Create permission groups

Every network needs at least a default group, a donor rank, and a staff rank:

```bash
# Default group -- auto-assigned to all new players
nimbus> perms group create Default
nimbus> perms group setdefault Default true
nimbus> perms group setprefix Default <gray>

# VIP rank
nimbus> perms group create VIP
nimbus> perms group setprefix VIP <gold>[VIP] </gold>
nimbus> perms group setpriority VIP 50
nimbus> perms group setweight VIP 50

# Admin rank
nimbus> perms group create Admin
nimbus> perms group setprefix Admin <red>[Admin] </red>
nimbus> perms group setpriority Admin 100
nimbus> perms group setweight Admin 100
```

::: info
**Priority** controls which prefix/suffix is displayed when a player has multiple groups (higher wins). **Weight** controls permission conflict resolution. In most cases, set them to the same value.
:::

### 2. Add permissions to groups

```bash
nimbus> perms group addperm Default essentials.spawn
nimbus> perms group addperm Default essentials.msg
nimbus> perms group addperm Default essentials.tpa

nimbus> perms group addperm VIP essentials.fly
nimbus> perms group addperm VIP essentials.hat
nimbus> perms group addperm VIP nimbus.join.full

nimbus> perms group addperm Admin *
```

### 3. Set up inheritance

VIP inherits all Default permissions. Admin inherits all VIP permissions (and therefore Default too):

```bash
nimbus> perms group addparent VIP Default
nimbus> perms group addparent Admin VIP
```

Now Admin has: all Default perms + all VIP perms + `*` (everything).

### 4. Verify with `group info`

```bash
nimbus> perms group info VIP
```

This shows the group's prefix, permissions, parents, meta, and weight.

## Prefixes and suffixes

Prefixes use [MiniMessage format](https://docs.advntr.dev/minimessage/format.html). Common patterns:

```bash
# Simple colored prefix
nimbus> perms group setprefix VIP <gold>[VIP] </gold>

# Gradient prefix
nimbus> perms group setprefix MVP <gradient:#ff6b6b:#ee5a24>[MVP] </gradient>

# Bold + color
nimbus> perms group setprefix Admin <red><bold>[Admin]</bold> </red>

# Suffix example
nimbus> perms group setsuffix VIP <gold> *</gold>
```

These prefixes appear in the tab list and chat automatically via the [proxy sync](/guide/proxy-setup#proxy-synchronization) system. No extra plugins needed on Velocity.

## Creating a promotion track

Tracks define an ordered path through groups for promotion and demotion:

```bash
nimbus> perms track create ranks Default,VIP,MVP,Admin
```

Now promote and demote players along this path:

```bash
nimbus> perms user promote Steve ranks    # Default -> VIP
nimbus> perms user promote Steve ranks    # VIP -> MVP
nimbus> perms user demote Steve ranks     # MVP -> VIP
```

A player can only hold one group per track at a time. Promoting removes the current track group and assigns the next one.

::: tip
You can create multiple tracks for different purposes -- e.g., a `ranks` track for donor ranks and a `staff` track for staff roles (Helper, Moderator, Admin).
:::

## Assigning players to groups

```bash
# Add a player to a group
nimbus> perms user addgroup Steve VIP

# Remove a player from a group
nimbus> perms user removegroup Steve VIP

# View a player's groups and permissions
nimbus> perms user info Steve
```

Players receive permission updates instantly -- no relog required.

## Wildcards and negation

### Wildcards

| Pattern | Matches |
|---------|---------|
| `*` | Every permission |
| `nimbus.*` | `nimbus.cloud.list`, `nimbus.cloud.start`, etc. |
| `essentials.teleport.*` | `essentials.teleport.tpa`, `essentials.teleport.tphere` |

### Negation

Prefix a permission with `-` to deny it, even if a wildcard or parent group grants it:

```bash
# Moderators get all nimbus commands except shutdown
nimbus> perms group addperm Moderator nimbus.*
nimbus> perms group addperm Moderator -nimbus.cloud.shutdown

# VIPs get all essentials perms except god mode
nimbus> perms group addperm VIP essentials.*
nimbus> perms group addperm VIP -essentials.god
```

Negation always wins over wildcards during permission resolution.

## In-game management with /cloud perms

The bridge plugin exposes all permission commands in-game via `/cloud perms`:

```
/cloud perms group list
/cloud perms group create Builder
/cloud perms group addperm Builder worldedit.*
/cloud perms group setprefix Builder <aqua>[Builder] </aqua>
/cloud perms user addgroup Steve Builder
/cloud perms user promote Steve ranks
```

Requires the `nimbus.cloud.perms` permission.

::: tip
The `/cloud` command is also aliased as `/nimbus`. All `perms` subcommands work identically to the console versions.
:::

## Debugging permissions

Use the `check` command to understand exactly why a permission is granted or denied:

```bash
nimbus> perms user check Steve essentials.fly
```

The output shows the full resolution chain -- which group granted it, whether it was an exact match or wildcard, and whether any negation was applied.

## Metadata

Groups and players can store arbitrary key-value metadata. Useful for custom plugin data accessible via the API:

```bash
# Group metadata
nimbus> perms group meta set VIP color gold
nimbus> perms group meta set VIP join-message <gold>A VIP has joined!

# Player metadata
nimbus> perms user meta set Steve coins 500
nimbus> perms user meta set Steve kit-cooldown 3600

# List metadata
nimbus> perms group meta list VIP
nimbus> perms user meta list Steve
```

Metadata is available via the REST API at `/api/permissions/groups/{name}/meta` and `/api/permissions/players/{uuid}/meta`.

## Switching to LuckPerms

If you prefer LuckPerms for its web editor, contexts, or verbose logging:

1. Install LuckPerms on all backend servers with a shared database (MySQL/PostgreSQL)
2. Edit the NimbusPerms config on each backend:

```yaml
# plugins/NimbusPerms/config.yml
provider: luckperms
```

3. Nimbus will read display data (prefix, suffix) from LuckPerms and sync it to the proxy for tab list and chat formatting

::: warning
When using LuckPerms, permission storage and resolution happens entirely in LuckPerms. Nimbus only syncs display data to the controller. The console `perms` commands manage the built-in system, not LuckPerms groups.
:::

Alternatively, disable NimbusPerms deployment entirely and manage everything through LuckPerms:

```toml
# config/nimbus.toml
[permissions]
deploy_plugin = false
```

## Next steps

- [Permission System Reference](/config/permissions) -- Full config reference, database schema, API endpoints
- [Proxy Setup](/guide/proxy-setup) -- Tab list and chat format configuration
- [Commands Reference](/guide/commands) -- All `perms` console commands
