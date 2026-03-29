---
layout: home
hero:
  name: "Nimbus"
  text: "Minecraft Cloud System"
  tagline: "Dynamic server management from a single JAR — auto-scaling, multi-node clusters, and a powerful API without the bloat."
  actions:
    - theme: brand
      text: Get Started
      link: /guide/quickstart
    - theme: alt
      text: Multi-Node Setup
      link: /guide/multi-node

features:
  - icon:
      src: /icons/network.svg
      width: 40
      height: 40
    title: "Multi-Node Cluster"
    details: "Distribute game servers across machines with automatic placement, failover, and a built-in TCP load balancer."
    link: /guide/multi-node
    linkText: Set up a cluster
  - icon:
      src: /icons/trending-up.svg
      width: 40
      height: 40
    title: "Smart Auto-Scaling"
    details: "Instances scale up and down based on player count. Configurable thresholds, idle timeouts, and custom game states."
    link: /guide/scaling
    linkText: How it works
  - icon:
      src: /icons/zap.svg
      width: 40
      height: 40
    title: "Zero-Config Proxy"
    details: "Velocity auto-managed — forwarding, server list, MOTD, tab list, and chat sync. ViaVersion for mixed versions."
    link: /guide/proxy-setup
    linkText: Proxy setup
  - icon:
      src: /icons/download.svg
      width: 40
      height: 40
    title: "Software Auto-Download"
    details: "Paper, Purpur, Velocity, Forge, Fabric, and NeoForge — server JARs downloaded and updated automatically."
    link: /guide/installation
    linkText: Get started
  - icon:
      src: /icons/package.svg
      width: 40
      height: 40
    title: "Modpack Import"
    details: "Import any Modrinth modpack in one command. Auto-configured with proxy forwarding and concurrent downloads."
    link: /guide/modpacks
    linkText: Learn more
  - icon:
      src: /icons/plug.svg
      width: 40
      height: 40
    title: "REST API + WebSocket"
    details: "Live event streams, bidirectional console access, file management, and permissions — all via API."
    link: /reference/api
    linkText: API docs
  - icon:
      src: /icons/gamepad.svg
      width: 40
      height: 40
    title: "Game-Server Ready"
    details: "Custom game states, smart routing, built-in permissions, and server-selector signs for minigame networks."
    link: /guide/concepts
    linkText: Core concepts
  - icon:
      src: /icons/rocket.svg
      width: 40
      height: 40
    title: "Lightweight & Fast"
    details: "Single JAR, no bloat. Coroutine-powered async, interactive console, and runs on just Java 21."
    link: /guide/installation
    linkText: Get started
---

<div class="terminal" style="max-width: 680px; margin: 3rem auto 0;">
  <div class="terminal-header">
    <span class="terminal-title">nimbus</span>
  </div>
  <pre class="terminal-body nimbus-banner">
<span class="t-blue">   _  __ __ _   __ ___  _ __  ___</span>
<span class="t-bright-blue">  / |/ // // \,' // o.)/// /,' _/</span>
<span class="t-cyan"> / || // // \,' // o \/ U /_\ \`. </span>
<span class="t-bright-cyan">/_/|_//_//_/ /_//___,'\_,'/___,'</span>
<span class="t-dim">            C L O U D</span>
<span class="t-dim">  Network:</span>  <span class="t-bold">MyNetwork</span>
<span class="t-dim">  Version:</span>  <span class="t-cyan">v0.2.0</span>
<span class="t-dim">──────────────────────────────</span>
<span class="t-dim">[12:00:01]</span> <span class="t-yellow">▲ STARTING</span>  <span class="t-bold">Proxy-1</span> <span class="t-dim">(port=25565)</span>
<span class="t-dim">[12:00:04]</span> <span class="t-green">● READY</span>     <span class="t-bold">Proxy-1</span>
<span class="t-dim">[12:00:04]</span> <span class="t-yellow">▲ STARTING</span>  <span class="t-bold">Lobby-1</span> <span class="t-dim">(port=30000)</span>
<span class="t-dim">[12:00:08]</span> <span class="t-green">● READY</span>     <span class="t-bold">Lobby-1</span>
<span class="t-dim">[12:00:15]</span> <span class="t-green">↑ SCALE UP</span>  <span class="t-bold">BedWars</span> 1 → 2
<span class="t-prompt">nimbus</span> <span class="t-cyan">»</span> <span class="t-white">_</span>
</pre>
</div>
