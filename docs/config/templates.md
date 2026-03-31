# Template System

Templates are the blueprints for server instances. Each template is a directory inside `templates/` that contains everything a server needs to run: the server JAR, plugins, configs, and world data.

## Directory Structure

```
templates/
  Proxy/              # Template for the Proxy group
    velocity.jar
    velocity.toml
    forwarding.secret
    plugins/
  Lobby/              # Template for the Lobby group
    server.jar
    plugins/
    server.properties
  BedWars/            # Template for the BedWars group
    server.jar
    plugins/
    world/
  global/             # Overlay applied to ALL backend servers
    plugins/
      shared-plugin.jar
  global_proxy/       # Overlay applied to ALL proxy servers
    plugins/
      proxy-plugin.jar
```

Each group config references a template by name via the `template` field in its [group configuration](/config/groups):

```toml
[group]
name = "BedWars"
template = "BedWars"
```

---

## How Templates Are Applied

When a service starts, Nimbus prepares its working directory in the following order:

1. **Copy group template** -- Files from `templates/<TemplateName>/` are copied to `services/<ServiceName>/`
2. **Apply global overlay** -- Files from `templates/global/` are copied on top (for backend servers) or `templates/global_proxy/` (for proxy servers), overwriting any conflicting files
3. **Patch configs** -- Server configs are automatically patched (ports, forwarding, etc.)
4. **Accept EULA** -- For Paper and Purpur servers, `eula.txt` is auto-accepted

### Static vs Dynamic Template Handling

| Behavior | DYNAMIC | STATIC |
|----------|---------|--------|
| Template copy | Full re-copy on every start | Copy only on first start; existing files preserved |
| World data | Fresh every time | Persisted across restarts |
| Config changes | Always from template | Manual edits in `services/` are kept |
| Use case | Minigame arenas, temporary instances | Survival worlds, persistent lobbies |

::: tip
For static services, changes to the template only affect files that don't already exist in the service directory. To force-update a file, delete it from the service directory and restart.
:::

---

## Symlink Optimization

Large directories that are read-only at runtime (such as `libraries/`) are symlinked instead of copied. This saves significant disk space and speeds up service startup, especially for modded servers where the `libraries/` directory can be hundreds of megabytes.

The following directories are automatically symlinked:
- `libraries/`

```
services/ModdedSMP-1/
  libraries/ -> /absolute/path/to/templates/ModdedSMP/libraries/
  server.jar
  mods/
  ...
```

::: warning
Because symlinked directories point to the template, do not modify files inside them from a running service. Changes would affect the template itself and all future instances.
:::

---

## Config Patching

Nimbus automatically patches server configuration files to ensure correct port binding and proxy forwarding. You do not need to manually set ports or forwarding secrets.

### server.properties (Paper/Purpur/Folia)

Nimbus sets:
- `server-port` -- Assigned port from the port allocator
- `online-mode=false` -- Required when behind a Velocity proxy

If `server.properties` doesn't exist, Nimbus creates a minimal one.

### velocity.toml (Velocity proxy)

Nimbus sets:
- `bind` -- Assigned proxy port (starting at 25565)
- `player-info-forwarding-mode` -- `modern` if all backends are 1.13+, otherwise `legacy`
- `online-mode=true` -- The proxy handles authentication

### Paper Velocity Forwarding

For Paper servers behind a Velocity proxy, Nimbus configures modern forwarding:

- Copies `forwarding.secret` from the Velocity template to each backend
- Patches `paper.yml` (Paper 1.13-1.18) or `config/paper-global.yml` (Paper 1.19+) to enable Velocity support
- If neither config file exists yet, creates both formats so whichever version picks it up

### BungeeCord/Legacy Forwarding

When pre-1.13 servers are in the network, Nimbus uses legacy forwarding:

- Sets `bungeecord: true` in `spigot.yml` on all backend servers
- Configures Velocity with `player-info-forwarding-mode = "legacy"`

### Fabric Proxy Forwarding

For Fabric servers, Nimbus configures FabricProxy-Lite:

- Creates/patches `config/FabricProxy-Lite.toml`
- Sets `hackOnlineMode` and `secret` based on the forwarding mode
- Auto-downloads FabricProxy-Lite and Fabric API from Modrinth if not present

### Forge/NeoForge Proxy Forwarding

For Forge and NeoForge servers, Nimbus configures proxy-compatible-forge:

- Creates `config/proxy-compatible-forge-server.toml`
- Sets forwarding mode (`MODERN` or `LEGACY`) and secret
- Auto-downloads the proxy-compatible-forge mod from Modrinth if not present

---

## Software Resolver

Nimbus automatically downloads server software when it's not already present in the template directory. No manual JAR downloads needed.

### Supported Software

| Software | Source | JAR Name | Download Method |
|----------|--------|----------|-----------------|
| `PAPER` | PaperMC API | `server.jar` | Latest build for the configured version |
| `PURPUR` | Purpur API | `server.jar` | Latest build for the configured version |
| `FOLIA` | PaperMC API | `server.jar` | Latest build for the configured version |
| `VELOCITY` | PaperMC API | `velocity.jar` | Latest build for the configured version |
| `FORGE` | MinecraftForge Maven | `server.jar` | Installer downloaded and run automatically |
| `NEOFORGE` | NeoForged Maven | `server.jar` | Installer downloaded and run automatically |
| `FABRIC` | FabricMC Meta | `server.jar` | Server launcher JAR (loader + installer bundled) |
| `CUSTOM` | -- | Configurable | Must be placed manually in the template |

::: info
For Forge and NeoForge, the installer is downloaded, executed with `--installServer`, and then cleaned up automatically. This may take a minute on first setup.
:::

### Version Resolution

For modded servers, if `modloader_version` is left empty in the group config, Nimbus fetches the latest stable version:

- **Forge** -- Latest promoted version from the Forge promotions API
- **NeoForge** -- Latest stable version matching the Minecraft version
- **Fabric** -- Latest stable loader version (always uses the newest stable loader since it's backwards compatible)

---

## Via Plugin Auto-Deployment

When your network includes servers running different Minecraft versions, Nimbus can auto-download ViaVersion and ViaBackwards to backend server templates.

| Plugin | Purpose |
|--------|---------|
| ViaVersion | Allow newer clients to join older servers |
| ViaBackwards | Allow older clients to join newer servers |
| ViaRewind | Extends ViaBackwards support to 1.7-1.8 clients |

Via plugins are downloaded from the Hangar repository (PaperMC's plugin repository) and placed in the template's `plugins/` directory. They are only installed on **backend servers**, never on the proxy.

::: tip
If a Via plugin JAR already exists in the `plugins/` directory, Nimbus skips the download. To update, delete the old JAR and restart.
:::

---

## EULA Auto-Acceptance

For Paper, Purpur, and Folia servers, Nimbus automatically accepts the Minecraft EULA by creating `eula.txt` with `eula=true`. This prevents the server from halting on first launch waiting for manual EULA acceptance.

---

## Modpack Installation

Nimbus supports installing Modrinth modpacks (`.mrpack` format) into templates. Modpacks can be specified by:

- **Local file path** -- A `.mrpack` file on disk
- **Modrinth URL** -- e.g., `https://modrinth.com/modpack/cobblemon`
- **Modrinth slug** -- e.g., `cobblemon`

### How It Works

1. The `.mrpack` is downloaded (if not local) and its `modrinth.index.json` is parsed
2. The modloader and Minecraft version are detected from the pack's dependencies
3. Server-side mods are downloaded in parallel (up to 8 concurrent downloads)
4. File integrity is verified using SHA-1 hashes
5. The `overrides/` and `server-overrides/` directories from the pack are extracted to the template

### Supported Modloaders

The modpack installer automatically detects and configures the correct modloader:

| Dependency Key | Maps To |
|----------------|---------|
| `fabric-loader` | `FABRIC` |
| `forge` | `FORGE` |
| `neoforge` | `NEOFORGE` |
| `quilt-loader` | `FABRIC` (fallback -- most Quilt mods are Fabric-compatible) |

::: info
Client-only files (where `env.server = "unsupported"`) are automatically skipped during installation.
:::

---

## Global Template Overlays

Global templates let you share plugins and configs across all servers of a given type.

| Directory | Applied To |
|-----------|-----------|
| `templates/global/` | All backend servers (Paper, Purpur, Folia, Forge, Fabric, etc.) |
| `templates/global_proxy/` | All proxy servers (Velocity) |

Global overlays are applied **after** the group-specific template, so they overwrite any conflicting files. This is useful for:

- Shared plugins that every server needs
- Common configuration files
- Network-wide resource packs

```
templates/
  global/
    plugins/
      NimbusSDK.jar          # SDK plugin for all backends
      ChatFormat.jar          # Shared chat plugin
    server.properties         # Common server settings
  global_proxy/
    plugins/
      NimbusBridge.jar        # Bridge plugin for all proxies
```

::: tip
The global template is optional. If the `global/` or `global_proxy/` directory doesn't exist, Nimbus simply skips the overlay step.
:::
