# Introduction

Nimbus is a lightweight, console-only Minecraft cloud system that manages dynamic server instances from a single JAR. It handles everything from downloading server software to auto-scaling game servers based on player count — no web UI, no bloat.

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body nimbus-banner">
<span class="t-blue">   _  __ __ _   __ ___  _ __  ___</span>
<span class="t-bright-blue">  / |/ // // \,' // o.)/// /,' _/</span>
<span class="t-cyan"> / || // // \,' // o \/ U /_\ \`. </span>
<span class="t-bright-cyan">/_/|_//_//_/ /_//___,'\_,'/___,'</span>
<span class="t-dim">            C L O U D</span>
</pre>
</div>

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus — in action</span>
  </div>
  <pre class="terminal-body">
<span class="t-dim">[12:00:01]</span> <span class="t-yellow">▲ STARTING</span>  <span class="t-bold">Proxy-1</span> <span class="t-dim">(group=Proxy, port=25565)</span>
<span class="t-dim">[12:00:04]</span> <span class="t-green">● READY</span>     <span class="t-bold">Proxy-1</span> <span class="t-dim">(group=Proxy)</span>
<span class="t-dim">[12:00:04]</span> <span class="t-yellow">▲ STARTING</span>  <span class="t-bold">Lobby-1</span> <span class="t-dim">(group=Lobby, port=30000)</span>
<span class="t-dim">[12:00:08]</span> <span class="t-green">● READY</span>     <span class="t-bold">Lobby-1</span> <span class="t-dim">(group=Lobby)</span>
<span class="t-dim">[12:00:08]</span> <span class="t-yellow">▲ STARTING</span>  <span class="t-bold">BedWars-1</span> <span class="t-dim">(group=BedWars, port=30001)</span>
<span class="t-dim">[12:00:12]</span> <span class="t-green">● READY</span>     <span class="t-bold">BedWars-1</span> <span class="t-dim">(group=BedWars)</span>
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> list
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Services</span> ──────────────────────────────────────</span>
<span class="t-bold t-bright-cyan">NAME            GROUP       STATE           PORT    PLAYERS  PID     UPTIME</span>
<span class="t-dim">──────────────────────────────────────────────────────────────────────────────</span>
<span class="t-bold">Proxy-1</span>         Proxy       <span class="t-green">● READY</span>          25565   12       12451   2h 15m
<span class="t-bold">Lobby-1</span>         Lobby       <span class="t-green">● READY</span>          30000   4        12488   2h 14m
<span class="t-bold">BedWars-1</span>       BedWars     <span class="t-green">● READY</span>          30001   8        12523   1h 32m
<span class="t-bold">BedWars-2</span>       BedWars     <span class="t-yellow">● STARTING</span>       30002   0        12567   0m 05s
<span class="t-dim">4 service(s)</span>
</pre>
</div>

## Why Nimbus?

Setting up a Minecraft network traditionally means manually configuring each server, writing startup scripts, managing proxy configs, and handling scaling yourself. Heavier cloud systems like CloudNet solve this but come with complexity and overhead.

Nimbus takes a different approach:

| | Manual Setup | Heavy Cloud Systems | Nimbus |
|---|---|---|---|
| **Setup time** | Hours | 30+ minutes | Under 5 minutes |
| **Dependencies** | Many | Database, web server | Just Java 21 |
| **Configuration** | Per-server | YAML/JSON + web UI | Simple TOML files |
| **Scaling** | Manual | Configurable | Automatic, player-based |
| **Server software** | Download yourself | Limited options | Auto-downloads Paper, Pufferfish, Purpur, Folia, Velocity, Forge, Fabric, NeoForge |
| **Proxy management** | Manual | Semi-automatic | Fully automatic |

## Key Features

- **Multi-Node Cluster** — [Distribute services across multiple machines](/guide/multi-node). Agent nodes connect via WebSocket with automatic placement, failover, and template distribution. Built-in TCP load balancer for proxy redundancy.
- **Single JAR** — One file runs your entire cloud. No external services needed. Scale to multi-node when you're ready.
- **Auto-scaling** — Dynamically starts and stops server instances based on player count and configurable thresholds — across one or many machines.
- **Software auto-download** — Automatically fetches Paper, Pufferfish, Purpur, Folia, Velocity, Forge, Fabric, and NeoForge server JARs.
- **Performance optimization** — Automatically applies [Aikar's JVM flags](https://docs.papermc.io/paper/aikars-flags) and optimized server configs out of the box. Tuned for both standard and large heaps (12G+).
- **Bedrock Edition support** — Optional [Geyser + Floodgate](/guide/proxy-setup#bedrock-support) integration lets Bedrock players (mobile, console, Windows) join your Java network. Plugins are auto-downloaded and configured.
- **Full modded server support** — Forge, Fabric, NeoForge, and Quilt (via Fabric) all work out of the box. Proxy forwarding mods are [auto-installed](/guide/proxy-setup#auto-forwarding-mods) so players connect through Velocity seamlessly.
- **Cardboard for Fabric** — Optionally install [Cardboard](https://modrinth.com/mod/cardboard) (BETA) on Fabric servers to run Bukkit/Paper plugins alongside mods. Auto-downloaded with its iCommon dependency.
- **Automatic JDK management** — Nimbus [detects installed Java versions](/guide/concepts#automatic-jdk-management) and downloads missing ones from Adoptium automatically. No manual JDK setup needed.
- **Velocity auto-patching** — Checks for new Velocity versions [every 6 hours](/guide/proxy-setup#auto-patching) and stages updates automatically. No downtime.
- **Adaptive forwarding** — [Automatically selects](/guide/proxy-setup#adaptive-forwarding) modern or legacy forwarding based on your server versions. Configures every backend (Paper, Fabric, Forge, NeoForge) with zero manual work.
- **REST API + WebSocket** — Full API for integration with external tools, bots, and panels.
- **Velocity proxy auto-management** — Proxy server list is automatically kept in sync as services start and stop.
- **Built-in permissions** — Permission system included out of the box.
- **Interactive console** — JLine3-powered REPL with tab completion, command history, and live event streaming.
- **Template system** — Global and per-group templates for consistent server configuration.
- **Via plugin support** — ViaVersion, ViaBackwards, and ViaRewind managed automatically on backend servers. Dependencies are enforced (ViaBackwards auto-includes ViaVersion).

## Architecture

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus — architecture</span>
  </div>
  <pre class="terminal-body">
<span class="t-cyan t-bold">┌─────────────────────────────────────┐</span>
<span class="t-cyan t-bold">│</span>       <span class="t-bold">Nimbus Controller</span>              <span class="t-cyan t-bold">│</span>
<span class="t-cyan t-bold">│</span>  <span class="t-bright-cyan">┌─────────┐</span> <span class="t-bright-cyan">┌──────┐</span> <span class="t-bright-cyan">┌──────────┐</span> <span class="t-cyan t-bold">│</span>
<span class="t-cyan t-bold">│</span>  <span class="t-bright-cyan">│</span> Console  <span class="t-bright-cyan">│</span> <span class="t-bright-cyan">│</span>  API  <span class="t-bright-cyan">│</span> <span class="t-bright-cyan">│</span> Scaling  <span class="t-bright-cyan">│</span> <span class="t-cyan t-bold">│</span>
<span class="t-cyan t-bold">│</span>  <span class="t-bright-cyan">│</span> <span class="t-dim">(JLine)</span>  <span class="t-bright-cyan">│</span> <span class="t-bright-cyan">│</span><span class="t-dim">(Ktor)</span><span class="t-bright-cyan">│</span> <span class="t-bright-cyan">│</span> <span class="t-dim">Engine</span>   <span class="t-bright-cyan">│</span> <span class="t-cyan t-bold">│</span>
<span class="t-cyan t-bold">│</span>  <span class="t-bright-cyan">└─────────┘</span> <span class="t-bright-cyan">└──────┘</span> <span class="t-bright-cyan">└──────────┘</span> <span class="t-cyan t-bold">│</span>
<span class="t-cyan t-bold">├─────────────────────────────────────┤</span>
<span class="t-cyan t-bold">│</span>          <span class="t-bold">Server Groups</span>              <span class="t-cyan t-bold">│</span>
<span class="t-cyan t-bold">│</span>  <span class="t-green">┌─────────┐</span> <span class="t-green">┌───────┐</span> <span class="t-green">┌────────┐</span> <span class="t-cyan t-bold">│</span>
<span class="t-cyan t-bold">│</span>  <span class="t-green">│</span> Proxy-1 <span class="t-green">│</span> <span class="t-green">│</span>Lobby-1<span class="t-green">│</span> <span class="t-green">│</span>BedWars <span class="t-green">│</span> <span class="t-cyan t-bold">│</span>
<span class="t-cyan t-bold">│</span>  <span class="t-green">│</span><span class="t-dim">(Velocity)</span><span class="t-green">│</span> <span class="t-green">│</span><span class="t-dim">(Paper)</span> <span class="t-green">│</span> <span class="t-green">│</span> <span class="t-dim">1..N</span>   <span class="t-green">│</span> <span class="t-cyan t-bold">│</span>
<span class="t-cyan t-bold">│</span>  <span class="t-green">└─────────┘</span> <span class="t-green">└───────┘</span> <span class="t-green">└────────┘</span> <span class="t-cyan t-bold">│</span>
<span class="t-cyan t-bold">└─────────────────────────────────────┘</span>
</pre>
</div>

The **Nimbus Controller** is the main process. It runs three core systems:

- **Console** — Interactive command line powered by JLine3. Manage everything from here.
- **API** — Ktor-based REST API and WebSocket server for external integrations.
- **Scaling Engine** — Periodically evaluates player counts and starts/stops instances automatically.

Below the controller sit your **Server Groups** — definitions for each type of server (proxy, lobby, game modes). Nimbus creates running **Services** from these groups as needed.

## Modules

Nimbus is built from several modules:

| Module | Description |
|---|---|
| **nimbus-core** | The main application and cluster controller. Console, API, scaling, service management, load balancer. |
| **nimbus-agent** | Headless agent that runs on worker nodes. Connects to the controller, runs services locally, streams state back. |
| **nimbus-protocol** | Shared message definitions for controller-agent communication (internal, not user-facing). |
| **nimbus-bridge** | Velocity plugin that provides hub commands and connects the proxy to the Nimbus API. Auto-deployed to all proxy instances. |
| **nimbus-sdk** | Backend server plugin (Paper/Purpur) that connects game servers to the Nimbus API. Auto-deployed to all backend instances (excluded on Folia). |
| **nimbus-signs** | Optional plugin for dynamic server-selector signs on lobby servers. |

## What's Next?

Ready to get started? Head to the [Installation](./installation.md) guide to set up Nimbus, or jump straight to the [Quick Start](./quickstart.md) for a step-by-step walkthrough.

Running multiple machines? Check out the [Multi-Node & Load Balancer](./multi-node.md) guide to distribute your network across a cluster.
