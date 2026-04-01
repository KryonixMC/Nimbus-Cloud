# Installation

## One-Command Install (Recommended)

The fastest way to get Nimbus running. The installer handles everything — Java 21, the latest release, start scripts, and an optional system service.

**Linux / macOS:**

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">terminal</span>
  </div>
  <pre class="terminal-body">
<span class="t-dim">$</span> curl -fsSL https://raw.githubusercontent.com/kryonixmc/Nimbus/main/install.sh | bash
</pre>
</div>

**Windows (PowerShell):**

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">powershell</span>
  </div>
  <pre class="terminal-body">
<span class="t-dim">&gt;</span> irm https://raw.githubusercontent.com/kryonixmc/Nimbus/main/install.ps1 | iex
</pre>
</div>

The installer will:

1. **Check for Java 21** — installs Eclipse Temurin automatically if missing
2. **Download the latest Nimbus release** from GitHub
3. **Create a start script** (`nimbus` command on Linux, `nimbus.bat` on Windows)
4. **Optionally create a system service** (systemd on Linux, Windows Service)

After installation, run `nimbus` to start the setup wizard.

::: tip Already have Java 21?
The installer detects it and skips the Java step. Run `java -version` to check.
:::

## Auto-Updates

Nimbus checks for updates automatically on every startup by querying GitHub Releases.

| Update type | Behavior |
|---|---|
| **Patch / Minor** (e.g. `0.1.0` → `0.1.3` or `0.2.0`) | Downloads and applies automatically. A backup of the previous JAR is kept. |
| **Major** (e.g. `0.x` → `1.0.0`) | Shows changelog and prompts `Upgrade now? [y/N]` — you decide. |

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus — auto-update</span>
  </div>
  <pre class="terminal-body">
<span class="t-cyan">ℹ</span> Update available: v0.1.0 -> v0.2.0 <span class="t-dim">(minor)</span>
<span class="t-dim">  Downloading v0.2.0...</span> <span class="t-green">done</span>
<span class="t-green">✓</span> Updated to v0.2.0 <span class="t-dim">(backup: nimbus-backup.jar)</span>
<span class="t-yellow">  Restart Nimbus to apply the update.</span>
</pre>
</div>

::: info Dev builds
When running from source (version = `dev`), the auto-updater is skipped.
:::

## Prerequisites

If you prefer to install manually, make sure you have:

- **Java 21** or later (required)
- **Git** (for cloning the repository)
- **2 GB RAM** minimum for the controller plus a few server instances

::: tip Checking your Java version
Run `java -version` in your terminal. You should see version 21 or higher:

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">terminal</span>
  </div>
  <pre class="terminal-body">
<span class="t-dim">$</span> java -version
openjdk version "21.0.2" 2024-01-16
</pre>
</div>

If you don't have Java 21, download it from [Adoptium](https://adoptium.net/) or use your system package manager.
:::

::: warning Java version matters
Nimbus requires Java 21. It will not run on older versions. The Minecraft servers it manages may also need Java 21 (Paper 1.20.5+, Purpur 1.20.5+).
:::

## Building from Source

Clone the repository and build the fat JAR:

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">terminal</span>
  </div>
  <pre class="terminal-body">
<span class="t-dim">$</span> git clone https://github.com/kryonixmc/Nimbus.git
<span class="t-dim">$</span> cd Nimbus
<span class="t-dim">$</span> ./gradlew shadowJar
<span class="t-dim">&gt; Task :nimbus-bridge:shadowJar</span>
<span class="t-dim">&gt; Task :nimbus-core:processResources</span>
<span class="t-dim">&gt; Task :nimbus-core:shadowJar</span>
<span class="t-green t-bold">BUILD SUCCESSFUL</span> <span class="t-dim">in 12s</span>
</pre>
</div>

The output JAR will be at:

```
nimbus-core/build/libs/nimbus-core-&lt;version&gt;-all.jar
```

::: tip Quick compile check
To verify the code compiles without building the full JAR:

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">terminal</span>
  </div>
  <pre class="terminal-body">
<span class="t-dim">$</span> ./gradlew :nimbus-core:compileKotlin
<span class="t-green t-bold">BUILD SUCCESSFUL</span> <span class="t-dim">in 4s</span>
</pre>
</div>

:::

## Running Nimbus

Copy the JAR to your desired directory and run it:

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">terminal</span>
  </div>
  <pre class="terminal-body">
<span class="t-dim">$</span> mkdir my-network
<span class="t-dim">$</span> cp nimbus-core/build/libs/nimbus-core-&lt;version&gt;-all.jar my-network/
<span class="t-dim">$</span> cd my-network
<span class="t-dim">$</span> java -jar nimbus-core-&lt;version&gt;-all.jar
</pre>
</div>

On first launch, the **Setup Wizard** starts automatically and walks you through initial configuration.

::: info JLine native access
On Java 21+, Nimbus automatically relaunches itself with the `--enable-native-access=ALL-UNNAMED` flag to suppress JLine terminal warnings. This is handled transparently — you don't need to add any JVM flags yourself.
:::

## Directory Structure

After the setup wizard completes, your directory will look like this:

```
my-network/
├── nimbus-core-&lt;version&gt;-all.jar    # The Nimbus application
├── config/                       # All configuration files
│   ├── nimbus.toml               #   Main configuration
│   ├── groups/                   #   Server group definitions
│   │   ├── proxy.toml            #     Velocity proxy group
│   │   ├── lobby.toml            #     Lobby server group
│   │   └── bedwars.toml          #     (example game mode)
│   └── modules/                  #   Module-specific configs
│       ├── display/              #     Sign/NPC display configs
│       └── syncproxy/            #     Proxy sync (MOTD, tab list, chat, maintenance)
├── templates/                    # File templates for services
│   ├── global/                   #   Shared across all backends
│   │   └── plugins/              #     nimbus-sdk.jar (auto-deployed)
│   ├── global_proxy/             #   Shared across all proxies
│   │   └── plugins/              #     nimbus-bridge.jar (auto-deployed)
│   ├── proxy/                    #   Velocity template files
│   ├── lobby/                    #   Lobby template files
│   └── bedwars/                  #   Game mode template files
├── services/                     # Running service instances
│   ├── static/                   #   Persistent services (proxy)
│   └── temp/                     #   Ephemeral services (lobbies, games)
├── permissions/                  # Permission data files
├── plugins/                      # Optional plugins (SDK, Signs)
└── logs/                         # Log files
    └── latest.log                #   Current session log
```

### Key Directories

| Directory | Purpose |
|---|---|
| `config/nimbus.toml` | Main configuration file for Nimbus. |
| `config/groups/` | One TOML file per server group. This is where you define your server types. |
| `templates/` | Template files that get copied to each service instance on startup. |
| `templates/global/` | Files shared across **all backend servers** (Paper, Purpur, etc.). |
| `templates/global_proxy/` | Files shared across **all proxy servers** (Velocity). |
| `services/static/` | Persistent service instances that survive restarts (e.g., proxy). |
| `services/temp/` | Ephemeral instances created and destroyed by the scaling engine. |
| `permissions/` | Built-in permission system data. |
| `plugins/` | Optional Nimbus plugins available for manual installation. |

## What's Next?

Follow the [Quick Start](./quickstart.md) guide to walk through your first network setup, or read about [Core Concepts](./concepts.md) to understand how Nimbus works.
