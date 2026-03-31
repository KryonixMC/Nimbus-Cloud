# Quick Start

This guide walks you through setting up your first Nimbus network from scratch. By the end, you'll have a running Velocity proxy, lobby server, and game server that players can connect to.

## 1. Start Nimbus

Run the JAR file:

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-dim">$</span> java -jar nimbus-core-&lt;version&gt;-all.jar
</pre>
</div>

On first launch, the setup wizard starts automatically. You'll see the Nimbus banner followed by a version fetch:

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus — first run</span>
  </div>
  <pre class="terminal-body">
<span class="t-blue">   _  __ __ _   __ ___  _ __  ___</span>
<span class="t-bright-blue">  / |/ // // \,' // o.)/// /,' _/</span>
<span class="t-cyan"> / || // // \,' // o \/ U /_\ `. </span>
<span class="t-bright-cyan">/_/|_//_//_/ /_//___,'\_,'/___,'</span>
<span class="t-dim">            C L O U D</span>
  <span class="t-dim">Let's get your cloud ready.</span>
  <span class="t-dim">Fetching available versions...</span> <span class="t-green">✓</span>
</pre>
</div>

## 2. Choose a Network Name

The wizard asks for your network name. This is used in proxy MOTD and branding:

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus — setup wizard</span>
  </div>
  <pre class="terminal-body">
  <span class="t-cyan">[1]</span> <span class="t-bold">Network</span>
  Network name <span class="t-dim">[MyNetwork]</span><span class="t-dim">:</span> <span class="t-bold">MyNetwork</span>
</pre>
</div>

::: tip
You can change this later in `config/nimbus.toml` under `[network]`.
:::

## 3. Proxy Setup

Nimbus automatically selects the latest Velocity version. Velocity is backwards-compatible, so you always get the newest release:

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus — setup wizard</span>
  </div>
  <pre class="terminal-body">
  <span class="t-cyan">[2]</span> <span class="t-bold">Proxy</span>
  <span class="t-green">✓</span> Velocity 3.4.0-SNAPSHOT <span class="t-dim">(always latest — backwards compatible)</span>
</pre>
</div>

## 4. Bedrock & Permissions

The wizard asks about optional features:

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus — setup wizard</span>
  </div>
  <pre class="terminal-body">
  Enable Bedrock Edition? (Geyser + Floodgate) <span class="t-dim">[y/N]</span><span class="t-dim">:</span> <span class="t-bold">n</span>
  Install built-in permissions plugin? <span class="t-dim">(prefix, suffix, groups, tracks)</span> <span class="t-dim">[Y/n]</span><span class="t-dim">:</span> <span class="t-bold">y</span>
  <span class="t-green">✓</span> NimbusPerms will be deployed to all backend servers
</pre>
</div>

::: tip
**Bedrock Edition** enables [Geyser + Floodgate](/guide/bedrock) so mobile and console players can join. **NimbusPerms** provides a built-in [permission system](/guide/permissions) with groups, prefixes, and tracks. Both can be enabled later.
:::

## 5. Choose a Server Template

Pick a template that matches your network layout:

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus — setup wizard</span>
  </div>
  <pre class="terminal-body">
  <span class="t-cyan">[3]</span> <span class="t-bold">Server Groups</span>
  <span class="t-bold">Choose a template:</span>
    <span class="t-cyan">1</span>  Standard Lobby  <span class="t-dim">(Proxy + Lobby)</span>
    <span class="t-cyan">2</span>  Lobby + Games   <span class="t-dim">(Proxy + Lobby + Minigame server)</span>
    <span class="t-cyan">3</span>  Custom          <span class="t-dim">(configure everything yourself)</span>
  Template <span class="t-dim">[1]</span><span class="t-dim">:</span> <span class="t-bold">2</span>
</pre>
</div>

For this walkthrough, we'll choose **2** (Lobby + Games). This creates a proxy, lobby, and a game server group — the most common network setup.

## 6. Configure the Lobby

The wizard walks you through lobby configuration — server software, version, and memory:

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus — setup wizard</span>
  </div>
  <pre class="terminal-body">
  <span class="t-dim">Setting up: Proxy + Lobby + Game server</span>
  <span class="t-bold">Lobby:</span>
  Server software <span class="t-dim">[paper]</span><span class="t-dim">:</span> <span class="t-bold">paper</span>
  <span class="t-dim">Stable: 1.21.4  1.21.3  1.21.1  1.20.6  1.20.4  1.20.2  1.20.1  1.19.4  ...</span>
  Version <span class="t-dim">[1.21.4]</span><span class="t-dim">:</span> <span class="t-bold">1.21.4</span>
  Lobby memory <span class="t-dim">[1G]</span><span class="t-dim">:</span> <span class="t-bold">1G</span>
</pre>
</div>

Next, the wizard offers **Via plugin** installation for protocol compatibility:

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus — setup wizard</span>
  </div>
  <pre class="terminal-body">
  <span class="t-bold">Protocol support:</span>
  <span class="t-dim">ViaVersion allows players with newer clients to join older servers.</span>
  <span class="t-dim">ViaBackwards allows players with older clients to join newer servers.</span>
  <span class="t-dim">ViaRewind extends backwards support to 1.7/1.8 clients.</span>
  Install <span class="t-cyan">ViaVersion</span>? <span class="t-dim">[y/N]</span><span class="t-dim">:</span> <span class="t-bold">n</span>
  Install <span class="t-cyan">ViaBackwards</span>? <span class="t-dim">(older clients can join)</span> <span class="t-dim">[Y/n]</span><span class="t-dim">:</span> <span class="t-bold">y</span>
  <span class="t-dim">→ ViaVersion auto-included (required by ViaBackwards)</span>
  Install <span class="t-cyan">ViaRewind</span>? <span class="t-dim">(extends support to 1.7/1.8)</span> <span class="t-dim">[y/N]</span><span class="t-dim">:</span> <span class="t-bold">n</span>
  <span class="t-green">✓</span> Via plugins: ViaVersion, ViaBackwards
  <span class="t-green">✓</span> Lobby <span class="t-dim">(PAPER 1.21.4, 1G)</span>
</pre>
</div>

::: info Via plugins
Via plugins are installed on **backend servers only**, never on the proxy. This is the recommended setup for Velocity networks.
:::

## 7. Configure the Game Server

Since we chose template 2, the wizard now asks for game server details:

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus — setup wizard</span>
  </div>
  <pre class="terminal-body">
  <span class="t-bold">Game server:</span>
  Group name: <span class="t-bold">BedWars</span>
  Server software <span class="t-dim">[paper]</span><span class="t-dim">:</span> <span class="t-bold">paper</span>
  <span class="t-dim">Stable: 1.21.4  1.21.3  1.21.1  ...</span>
  Version <span class="t-dim">[1.21.4]</span><span class="t-dim">:</span> <span class="t-bold">1.21.4</span>
  Memory per instance <span class="t-dim">[2G]</span><span class="t-dim">:</span> <span class="t-bold">2G</span>
  Max instances <span class="t-dim">[10]</span><span class="t-dim">:</span> <span class="t-bold">10</span>
  Install <span class="t-cyan">ViaVersion</span>? <span class="t-dim">[y/N]</span><span class="t-dim">:</span> <span class="t-bold">n</span>
  Install <span class="t-cyan">ViaBackwards</span>? <span class="t-dim">(older clients can join)</span> <span class="t-dim">[Y/n]</span><span class="t-dim">:</span> <span class="t-bold">y</span>
  <span class="t-green">✓</span> Via plugins: ViaBackwards
  <span class="t-green">✓</span> BedWars <span class="t-dim">(PAPER 1.21.4, 2G, max 10)</span>
</pre>
</div>

::: tip Same version = shared download
When your game server uses the same software and version as the lobby, Nimbus copies the JAR instead of downloading it again.
:::

## 8. Auto-Download

Nimbus downloads all required server JARs automatically:

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus — setup wizard</span>
  </div>
  <pre class="terminal-body">
  <span class="t-cyan">[4]</span> <span class="t-bold">Downloading</span>
  <span class="t-dim">↓</span> Velocity 3.4.0-SNAPSHOT <span class="t-green">✓</span>
  <span class="t-dim">↓</span> Paper 1.21.4 <span class="t-dim">(Lobby)</span> <span class="t-green">✓</span>
  <span class="t-dim">↓</span> ViaVersion <span class="t-dim">(Lobby)</span> <span class="t-green">✓</span>
  <span class="t-dim">↓</span> ViaBackwards <span class="t-dim">(Lobby)</span> <span class="t-green">✓</span>
  <span class="t-green">+</span> BedWars <span class="t-dim">(copied from Lobby)</span>
  <span class="t-dim">↓</span> ViaVersion <span class="t-dim">(BedWars)</span> <span class="t-green">✓</span>
  <span class="t-dim">↓</span> ViaBackwards <span class="t-dim">(BedWars)</span> <span class="t-green">✓</span>
</pre>
</div>

::: warning Download failures
If a download fails, Nimbus shows a `✗` marker. You can manually place the JAR in the appropriate `templates/<group>/` directory and restart.
:::

## 9. Configuration Saved

The wizard writes all configuration files:

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus — setup wizard</span>
  </div>
  <pre class="terminal-body">
  <span class="t-cyan">[5]</span> <span class="t-bold">Saving configuration</span>
  <span class="t-green">+</span> config/nimbus.toml
  <span class="t-green">+</span> config/groups/proxy.toml
  <span class="t-green">+</span> config/groups/lobby.toml
  <span class="t-green">+</span> config/groups/bedwars.toml
<span class="t-dim">────────────────────────────────────────</span>
  <span class="t-green t-bold">Setup complete!</span> <span class="t-dim">3 group(s) configured.</span>
<span class="t-dim">────────────────────────────────────────</span>
  Start Nimbus now? <span class="t-dim">[Y/n]</span><span class="t-dim">:</span> <span class="t-bold">y</span>
</pre>
</div>

## 10. First Boot

Nimbus starts your services automatically — proxy first, then backends:

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-dim">[12:00:01]</span> <span class="t-yellow">▲ STARTING</span>  <span class="t-bold">Proxy-1</span> <span class="t-dim">(group=Proxy, port=25565)</span>
<span class="t-dim">[12:00:04]</span> <span class="t-green">● READY</span>     <span class="t-bold">Proxy-1</span> <span class="t-dim">(group=Proxy)</span>
<span class="t-dim">[12:00:04]</span> <span class="t-yellow">▲ STARTING</span>  <span class="t-bold">Lobby-1</span> <span class="t-dim">(group=Lobby, port=30000)</span>
<span class="t-dim">[12:00:08]</span> <span class="t-green">● READY</span>     <span class="t-bold">Lobby-1</span> <span class="t-dim">(group=Lobby)</span>
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span>
</pre>
</div>

BedWars instances don't start yet — they scale up automatically when players need them, or you can start one manually with `start BedWars`.

## 11. Connect

Open Minecraft and add a server:

- **Server Address:** `localhost:25565` (or your machine's IP)
- You'll be connected to the Velocity proxy and sent to `Lobby-1` automatically

::: warning Port conflicts
If port 25565 is already in use (e.g., by another Minecraft server), Nimbus will fail to bind the proxy. Stop the conflicting server first, or change the proxy port in `config/groups/proxy.toml`.
:::

## 12. Explore the Console

With Nimbus running, you have a full interactive console. Try these commands:

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> list
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Services</span> ──────────────────────────────────────</span>
<span class="t-bold t-bright-cyan">NAME            GROUP       STATE           PORT    PLAYERS  PID     UPTIME</span>
<span class="t-dim">──────────────────────────────────────────────────────────────────────────────</span>
<span class="t-bold">Proxy-1</span>         Proxy       <span class="t-green">● READY</span>          25565   0        12451   3m 22s
<span class="t-bold">Lobby-1</span>         Lobby       <span class="t-green">● READY</span>          30000   0        12488   3m 18s
<span class="t-dim">2 service(s)</span>
</pre>
</div>

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> status
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Network: MyNetwork</span> ────────────────────────────</span>
<span class="t-dim">Services:</span> <span class="t-green">2 ready</span> <span class="t-dim">/ 2 total</span>    <span class="t-dim">Players:</span> <span class="t-bold">0</span>
<span class="t-bold t-bright-cyan">GROUP       TYPE      INSTANCES  MIN/MAX  PLAYERS  STATUS</span>
<span class="t-dim">────────────────────────────────────────────────────────────────</span>
Proxy       STATIC    1          1/1      0        <span class="t-green">healthy</span>
Lobby       DYNAMIC   1          1/4      0        <span class="t-green">healthy</span>
<span class="t-dim">Capacity:</span> <span class="t-green">██████░░░░░░░░░░░░░░░░░░░░░░░░</span> 2/20 services
</pre>
</div>

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus — screen Lobby-1</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> screen Lobby-1
<span class="t-cyan">ℹ</span> Attached to Lobby-1 <span class="t-dim">(ESC or Ctrl+Q to detach)</span>
<span class="t-dim">────────────────────────────────────────────────────────</span>
<span class="t-dim">[14:23:01 INFO]:</span> Done (2.341s)! For help, type "help"
<span class="t-dim">[14:23:05 INFO]:</span> Player Steve joined the game
<span class="t-yellow">&gt;</span> say Hello from Nimbus!
<span class="t-dim">[14:23:12 INFO]:</span> [Server] Hello from Nimbus!
<span class="t-dim">────────────────────────────────────────────────────────</span>
<span class="t-cyan">ℹ</span> Detached from Lobby-1
</pre>
</div>

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> help
<span class="t-cyan">── <span class="t-bold" style="color:#c0caf5">Commands</span> ──────────────────────────────────────</span>
  <span class="t-cyan">list</span>          <span class="t-dim">List all running services</span>
  <span class="t-cyan">start</span>         <span class="t-dim">Start a new service instance</span>
  <span class="t-cyan">stop</span>          <span class="t-dim">Stop a running service</span>
  <span class="t-cyan">restart</span>       <span class="t-dim">Restart a running service</span>
  <span class="t-cyan">screen</span>        <span class="t-dim">Attach to a service console</span>
  <span class="t-cyan">exec</span>          <span class="t-dim">Execute a command on a service</span>
  <span class="t-cyan">logs</span>          <span class="t-dim">Show recent log output</span>
  <span class="t-cyan">players</span>       <span class="t-dim">List connected players</span>
  <span class="t-cyan">send</span>          <span class="t-dim">Transfer a player to a service</span>
  <span class="t-cyan">groups</span>        <span class="t-dim">List all server groups</span>
  <span class="t-cyan">info</span>          <span class="t-dim">Show group configuration</span>
  <span class="t-cyan">create</span>        <span class="t-dim">Create a new server group</span>
  <span class="t-cyan">import</span>        <span class="t-dim">Import a Modrinth modpack</span>
  <span class="t-cyan">static</span>        <span class="t-dim">Set a group or service to static</span>
  <span class="t-cyan">dynamic</span>       <span class="t-dim">Set a group to dynamic mode</span>
  <span class="t-cyan">perms</span>         <span class="t-dim">Manage permissions</span>
  <span class="t-cyan">reload</span>        <span class="t-dim">Reload group configurations</span>
  <span class="t-cyan">api</span>           <span class="t-dim">Manage the REST API</span>
  <span class="t-cyan">status</span>        <span class="t-dim">Show network status overview</span>
  <span class="t-cyan">shutdown</span>      <span class="t-dim">Graceful shutdown and exit</span>
  <span class="t-cyan">clear</span>         <span class="t-dim">Clear the console</span>
  <span class="t-cyan">help</span>          <span class="t-dim">Show available commands</span>
<span class="t-dim">Type 'help &lt;command&gt;' for detailed usage.</span>
</pre>
</div>

::: tip Tab completion
All commands support tab completion. Press `Tab` to autocomplete command names and service names.
:::

## 13. Add More Game Modes

Want to add another game server? Use the interactive `create` command:

<div class="terminal">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body">
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> create
</pre>
</div>

This launches a guided setup for a new server group, similar to the setup wizard. It will:

1. Ask for a group name (e.g., `SkyWars`)
2. Let you pick server software and version
3. Configure scaling rules (min/max instances, players per instance)
4. Set memory allocation
5. Download the server JAR automatically
6. Write the group config to `config/groups/skywars.toml`
7. Start the minimum instances

## Next Steps

Now that your network is running:

- **[Core Concepts](./concepts.md)** — Understand groups, services, templates, and scaling
- **[Configuration](../config/nimbus-toml.md)** — Customize `config/nimbus.toml` and group configs
- **[Commands](commands.md)** — Full console command reference
- **[API Reference](../reference/api.md)** — REST API and WebSocket documentation
