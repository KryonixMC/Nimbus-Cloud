# Bedrock / Geyser Setup

Nimbus supports **Bedrock Edition crossplay** out of the box. Players on mobile (iOS/Android), consoles (Xbox, PlayStation, Switch), and Windows 10/11 can join your Java Edition network alongside regular Java players. This is powered by [Geyser](https://geysermc.org/) (protocol translation) and [Floodgate](https://geysermc.org/) (Xbox Live authentication).

## Enabling Bedrock support

Add the `[bedrock]` section to `config/nimbus.toml`:

```toml
[bedrock]
enabled = true
base_port = 19132
```

Or enable it during the [setup wizard](/guide/quickstart) when prompted.

That is all you need to configure. Nimbus handles the rest.

## What Nimbus auto-configures

When Bedrock support is enabled, Nimbus performs the following on startup:

| Step | What happens |
|------|-------------|
| **Geyser download** | Downloads the Geyser Velocity plugin from the GeyserMC API and deploys it to the proxy template |
| **Floodgate download** | Downloads Floodgate for both Velocity and Paper, deploys to proxy + all Paper/Purpur/Folia backend templates |
| **key.pem distribution** | Generates Floodgate's shared encryption key on first proxy start, then copies it to every backend service automatically |
| **Geyser config** | Auto-generates `config.yml` for Geyser with the correct Java port, UDP port, and MOTD/player count passthrough |
| **UDP port allocation** | Assigns each proxy instance a unique Bedrock port starting from `base_port` |

::: tip
MOTD, server icon, and player counts are passed through from the Java proxy automatically. Bedrock players see the same server list information as Java players.
:::

## Port requirements

Both TCP and UDP ports must be open on your server:

| Protocol | Port | Transport | Purpose |
|----------|------|-----------|---------|
| Java Edition | 25565 | TCP | Standard Minecraft connections |
| Bedrock Edition | 19132 | UDP | Geyser Bedrock listener |

::: warning
Bedrock uses **UDP**, not TCP. Make sure your firewall and hosting provider allow inbound UDP traffic on the Bedrock port. Many providers block UDP by default.
:::

If you run multiple proxy instances, each gets an incremented Bedrock port:

| Instance | Java port | Bedrock port |
|----------|-----------|-------------|
| Proxy-1 | 25565 | 19132 |
| Proxy-2 | 25566 | 19133 |
| Proxy-3 | 25567 | 19134 |

## Testing connections

### Java Edition

Connect normally to `your-server-ip:25565`.

### Bedrock Edition

1. Open Minecraft Bedrock Edition on your device
2. Go to **Play** > **Servers** > **Add Server**
3. Enter your server IP and port `19132`
4. Connect -- Geyser translates the Bedrock protocol to Java automatically

::: info
Bedrock players authenticate via Xbox Live. Their names are prefixed with `.` by default (e.g., `.BedrockPlayer123`) to avoid name collisions with Java players. This is a Floodgate default.
:::

### Verifying in Nimbus

The `status` command shows Bedrock state, and `list` shows both TCP and UDP ports:

```bash
nimbus> status
# ... Bedrock: enabled (Geyser + Floodgate, base port 19132)

nimbus> list
# Proxy-1    Proxy    READY    25565/19132    0    12345    1h 30m
```

## Bedrock + Load Balancer

The built-in TCP load balancer handles **Java connections only**. Bedrock UDP traffic is not load-balanced.

When both are enabled:

- The **first proxy instance** gets the standard Bedrock port (19132) -- this is the port Bedrock players connect to
- Additional proxy instances get incremented ports (19133, 19134, ...) but only the first one typically matters
- **Java players** are distributed across all proxies by the TCP load balancer as usual

This works well because Bedrock player counts are typically much smaller than Java. If you need full Bedrock load balancing, use an external UDP-capable load balancer (e.g., HAProxy with UDP support).

## Architecture

```
Bedrock (UDP:19132) ---> Velocity + Geyser + Floodgate ---> Backends + Floodgate
Java    (TCP:25565) --->                               --->
```

After Geyser translates the connection, backends see all players as normal Java clients. No backend configuration changes are needed.

## Limitations

- **Modded backends** -- Floodgate is only deployed to Paper/Purpur/Folia backends. Fabric/Forge/NeoForge servers do not receive Floodgate, so backend plugins cannot identify Bedrock players on modded servers. Players can still connect through the proxy.
- **Gameplay differences** -- Bedrock and Java have different combat systems, redstone behavior, and some items/blocks are mapped to the closest equivalent. See [GeyserMC docs](https://geysermc.org/) for details.
- **Folia** -- Floodgate is deployed to Folia backends, but some Floodgate features may not work due to regionized threading.

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Bedrock clients cannot connect | Verify UDP port 19132 is open in your firewall. Many hosts block UDP by default. |
| "Unable to connect to world" | Check that the proxy is running and Geyser loaded successfully (`screen Proxy-1`). |
| Bedrock players appear as offline-mode | Ensure Floodgate is deployed to both proxy and backend servers. Check `key.pem` exists in both locations. |
| Skin issues | Geyser maps Bedrock skins to Java format, but some custom skins may not translate perfectly. This is a Geyser limitation. |
| High latency for Bedrock players | Expected -- Geyser performs real-time protocol translation. Keep your proxy on a low-latency host. |

## Next steps

- [Proxy Setup](/guide/proxy-setup) -- Full proxy configuration and forwarding modes
- [nimbus.toml Reference](/config/nimbus-toml) -- `[bedrock]` configuration details
- [Multi-Node & Load Balancer](/guide/multi-node) -- Load balancer setup for larger networks
