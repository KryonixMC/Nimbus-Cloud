# Display Plugin

The Nimbus Display plugin adds clickable server signs and interactive NPCs to lobby and hub servers. Players can click a sign or NPC to join a server, open a server selector inventory, or execute custom commands.

::: info Compatibility
The Display plugin supports **Spigot 1.8.8** (signs only) through the **latest Paper/Folia** versions. NPCs require **Paper 1.20+** with FancyNpcs. See [Compatibility](#compatibility) for details.
:::

## Installation

The display plugin is auto-deployed to all backend servers along with its dependency **FancyNpcs**. Both JARs are embedded in nimbus-core and deployed on startup:

```
templates/global/plugins/
  nimbus-display.jar     # Auto-deployed
  FancyNpcs.jar          # Auto-deployed (required for NPCs)
  nimbus-sdk.jar         # Auto-deployed (required)
```

::: tip
Nimbus auto-updates `nimbus-display.jar` in templates and static services on every boot.
:::

## Commands

The unified command is `/ndisplay` (alias: `/nimbusdisplay`).

### Signs

| Command | Permission | Description |
|---|---|---|
| `/ndisplay sign set <target> [strategy]` | `nimbus.display.sign` | Create a sign (look at a sign block) |
| `/ndisplay sign remove` | `nimbus.display.sign` | Remove the sign you're looking at |

### NPCs

| Command | Permission | Description |
|---|---|---|
| `/ndisplay npc set <target> [strategy] [type] [skin]` | `nimbus.display.npc` | Spawn NPC at your position |
| `/ndisplay npc remove` | `nimbus.display.npc` | Remove nearest NPC (5 blocks) |
| `/ndisplay npc edit <property> <value>` | `nimbus.display.npc` | Live-edit NPC property |
| `/ndisplay npc info` | `nimbus.display.npc` | Show all properties of nearest NPC |

### Global

| Command | Permission | Description |
|---|---|---|
| `/ndisplay list` | `nimbus.display.list` | List all signs and NPCs |
| `/ndisplay reload` | `nimbus.display.reload` | Reload config and respawn all |

## Creating NPCs

```
/ndisplay npc set BedWars                         # Villager NPC
/ndisplay npc set BedWars least PLAYER Notch       # Player NPC with skin
/ndisplay npc set BedWars fill ZOMBIE              # Zombie NPC
```

**Entity types:** `PLAYER`, `VILLAGER`, `ZOMBIE`, `SKELETON`, `PILLAGER`, `WANDERING_TRADER`, `IRON_GOLEM`, `ALLAY`, `ARMOR_STAND`, and any other living entity type.

For `PLAYER` type, the optional skin argument sets the player name whose skin to use.

## Live-editing NPCs

All properties can be changed live with `/ndisplay npc edit`. The NPC respawns immediately with new settings and the config is saved.

| Property | Example | Description |
|---|---|---|
| `type` | `PLAYER`, `ZOMBIE` | Entity type |
| `skin` | `Notch` | Player skin (PLAYER type) |
| `target` | `BedWars` | Target group or service |
| `strategy` | `least`, `fill`, `random` | Routing strategy |
| `lookat` | `true`, `false` | Turn head toward player |
| `left_click` | `CONNECT`, `COMMAND /menu`, `INVENTORY`, `NONE` | Left-click action |
| `right_click` | `INVENTORY`, `CONNECT`, `NONE` | Right-click action |
| `hologram` | `&b&lBedWars\|&7{players} online` | Hologram lines (`\|` = line break) |
| `floating_item` | `true`, `RED_BED`, `off` | Floating item (true = from display config) |
| `mainhand` | `DIAMOND_SWORD`, `none` | Main hand item |
| `offhand` | `SHIELD`, `none` | Off hand item |
| `head` | `DIAMOND_HELMET`, `none` | Helmet slot |
| `chest` | `DIAMOND_CHESTPLATE`, `none` | Chestplate slot |
| `legs` | `DIAMOND_LEGGINGS`, `none` | Leggings slot |
| `feet` | `DIAMOND_BOOTS`, `none` | Boots slot |
| `burning` | `true`, `false` | Visual fire effect |
| `pose` | `crouching`, `sleeping`, `swimming`, `spin_attack`, `none` | Entity pose |
| `position` | `here` | Move NPC to your position |

## Click actions

| Action | Description |
|---|---|
| `CONNECT` | Route player to best server in group (or specific service) |
| `INVENTORY` | Open server selector GUI with all services as clickable items |
| `COMMAND <cmd>` | Execute a command as the player (e.g., `COMMAND /menu open`) |
| `NONE` | Do nothing |

## Hologram placeholders

| Placeholder | Description |
|---|---|
| `{name}` / `{target}` | Target name (group or service) |
| `{players}` | Current player count |
| `{max_players}` | Maximum players per instance |
| `{servers}` | Number of running services |
| `{state}` | Current state (resolved through display config) |

## Display config integration

Signs and NPC holograms pull their templates from `config/modules/display/<group>.toml`. The floating item material is also defined per group:

```toml
[display.npc]
display_name = "&b&lBedWars"
subtitle = "&7{players}/{max_players} online &8| &7{state}"
subtitle_offline = "&c✖ Offline"
floating_item = "RED_BED"

[display.npc.status_items]
ONLINE = "LIME_WOOL"
OFFLINE = "GRAY_WOOL"
INGAME = "ORANGE_WOOL"
```

## Server selector inventory

When a player triggers the `INVENTORY` action, a chest GUI opens showing all services in the target group. Each service is represented by a wool item colored by status (configurable via `status_items`). Clicking a service connects the player.

Inventory appearance is configurable in the display config:

```toml
[display.npc.inventory]
title = "&8» &b&lBedWars Servers"
size = 27
item_name = "&b{name}"
item_lore = ["&7Players: &f{players}/{max_players}", "&7State: &f{state}", "", "&aClick to join!"]
```

## Data storage

NPC and sign configurations are stored in the plugin's `config.yml`:

```yaml
npcs:
  bedwars-npc-10-65-5:
    target: BedWars
    strategy: least
    entity_type: PLAYER
    skin: Notch
    look_at_player: true
    left_click: CONNECT
    right_click: INVENTORY
    hologram:
      - "&b&lBedWars"
      - "&7{players}/{max_players} online"
      - "&7{state}"
    floating_item: "true"
    equipment:
      mainhand: DIAMOND_SWORD
      offhand: SHIELD
    burning: false
    pose: null
    location:
      world: world
      x: 10.5
      y: 65.0
      z: 5.5
      yaw: 90.0
      pitch: 0.0

signs:
  bedwars-100-64-200:
    target: BedWars
    strategy: least
    location:
      world: world
      x: 100
      y: 64
      z: 200
```

## Permissions

| Permission | Description |
|---|---|
| `nimbus.display.sign` | Create and manage signs |
| `nimbus.display.npc` | Create, edit, and remove NPCs |
| `nimbus.display.list` | List all signs and NPCs |
| `nimbus.display.reload` | Reload display config |

## Compatibility

The Display plugin runs on **Spigot 1.8.8** through the **latest Paper and Folia** versions.

### Feature availability by version

| Feature | Minimum version | Notes |
|---|---|---|
| Signs (create, update, click) | Spigot 1.8.8 | Full functionality, uses `TextCompat` for cross-version sign rendering |
| Sign text (Adventure) | Paper 1.16.5 | Rich text via `sign.line(Component)` |
| Sign text (Legacy) | Spigot 1.8.8 | `sign.setLine(String)` with `&` color codes |
| NPCs (FancyNpcs) | Paper 1.20+ | Requires FancyNpcs plugin (soft dependency, graceful degradation) |
| NPC holograms (ArmorStands) | Spigot 1.8.8 | Custom names via `TextCompat.setCustomName()` |
| NPC floating items | Spigot 1.8.8 | `setUnlimitedLifetime()` / `setCanMobPickup()` silently skipped on older versions |
| PersistentDataContainer (hologram tags) | Bukkit 1.14 | Falls back to scoreboard tags, then proximity cleanup on 1.8 |
| Server selector inventory | Spigot 1.8.8 | Cross-version inventory creation via `TextCompat.createInventory()` |
| Folia support (Signs) | Folia 1.19.4+ | Each sign updated on its block's region thread via `SchedulerCompat.runAtLocation()` |
| Folia support (NPCs) | Folia 1.19.4+ | NPC spawn/despawn/update on the NPC location's region thread |

### Folia details

On Folia, all block and entity operations are region-aware:

- **Sign updates** — Each sign is updated on the region thread that owns its block location
- **NPC spawning** — ArmorStand holograms, floating items, and FancyNpc entities are spawned on the correct region thread
- **NPC updates** — Hologram text updates are dispatched per-NPC to the correct region
- **FancyNpcs** — FancyNpcs 2.9.2+ has native Folia support for NPC rendering and interaction

### Graceful degradation

On servers where FancyNpcs is not installed (or on Spigot versions below 1.20), the NPC system is silently disabled. Signs continue to work on all versions. The plugin logs a warning on startup if FancyNpcs is missing.

## Next steps

- [SDK](/developer/sdk) — Backend server plugin API
- [Display Config](/config/display) — Customize sign + NPC appearance
- [Server Groups](/guide/server-groups) — Group configuration
