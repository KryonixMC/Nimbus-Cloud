package dev.kryonix.nimbus.plugin;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Optional;

@Plugin(
    id = "nimbus-bridge",
    name = "Nimbus Bridge",
    version = "0.2.0",
    description = "Hub commands & Cloud Bridge for Nimbus networks",
    authors = {"Nimbus"}
)
public class NimbusBridgePlugin {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    @Inject
    public NimbusBridgePlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        var commandManager = server.getCommandManager();

        // Register /hub, /lobby, /l
        HubCommand hubCommand = new HubCommand(server);
        for (String alias : new String[]{"hub", "lobby", "l"}) {
            var meta = commandManager.metaBuilder(alias)
                .plugin(this)
                .build();
            commandManager.register(meta, hubCommand);
        }
        logger.info("Nimbus Bridge loaded — /hub, /lobby, /l registered");

        // Register /cloud bridge command (if bridge config exists)
        registerBridge(commandManager);

        // Register event listeners (maintenance handler may be null if no bridge config)
        server.getEventManager().register(this, new ConnectionListener(server, logger, () -> maintenanceHandler));

        // Unsubscribe players from event feed on disconnect
        server.getEventManager().register(this, new Object() {
            @Subscribe
            public void onDisconnect(DisconnectEvent event) {
                if (cloudCommand != null) {
                    cloudCommand.unsubscribePlayer(event.getPlayer().getUniqueId());
                }
            }
        });

        // Register permission provider (if bridge config exists)
        registerPermissionProvider();

        // Register proxy sync (tab list + MOTD)
        registerProxySync();

        // Register event stream on cloud command for /cloud events
        if (cloudCommand != null && sharedEventStream != null) {
            cloudCommand.registerEventStream(sharedEventStream);
        }

        // Connect shared event stream (after all handlers are registered)
        connectSharedEventStream();

        // Start health polling to track controller reachability
        startHealthPoller();
    }

    private void registerProxySync() {
        try {
            BridgeConfig config = BridgeConfig.load(dataDirectory);
            if (config == null) return;

            ensureSharedClients(config);

            // Initialize maintenance handler
            maintenanceHandler = new MaintenanceHandler(logger);
            if (cloudCommand != null) {
                cloudCommand.setMaintenanceHandler(maintenanceHandler);
            }

            proxySyncListener = new ProxySyncListener(server, logger, sharedApiClient, sharedEventStream);
            proxySyncListener.setMaintenanceHandler(maintenanceHandler);
            proxySyncListener.init();
            server.getEventManager().register(this, proxySyncListener);

            // Register maintenance event handlers
            registerMaintenanceEventHandlers();

            logger.info("Proxy Sync registered (tab list + MOTD + maintenance)");
        } catch (Exception e) {
            logger.warn("Failed to register proxy sync: {}", e.getMessage());
        }
    }

    private void registerMaintenanceEventHandlers() {
        sharedEventStream.onEvent("MAINTENANCE_ENABLED", e -> {
            maintenanceHandler.onMaintenanceEnabled(e.getData());
            // Refresh full state from API for MOTD/protocol/whitelist details
            maintenanceHandler.refreshFromApi(sharedApiClient);
        });
        sharedEventStream.onEvent("MAINTENANCE_DISABLED", e -> {
            maintenanceHandler.onMaintenanceDisabled(e.getData());
        });
    }

    private void connectSharedEventStream() {
        if (sharedEventStream != null) {
            // Register reconnect callback to re-sync state after controller comes back
            sharedEventStream.onReconnect(() -> {
                logger.info("Event stream reconnected — re-syncing proxy config and maintenance state");
                if (proxySyncListener != null) {
                    proxySyncListener.refetchConfig();
                }
                if (maintenanceHandler != null && sharedApiClient != null) {
                    maintenanceHandler.refreshFromApi(sharedApiClient);
                }
            });

            try {
                sharedEventStream.connect();
                logger.info("Shared event stream connected");
            } catch (Exception e) {
                logger.warn("Failed to connect shared event stream: {}", e.getMessage());
            }
        }
    }

    private void startHealthPoller() {
        if (sharedApiClient == null) return;
        server.getScheduler().buildTask(
            server.getPluginManager().getPlugin("nimbus-bridge").orElse(null),
            () -> {
                try {
                    var result = sharedApiClient.get("/api/health").join();
                    if (result.isSuccess()) {
                        if (!controllerReachable) {
                            logger.info("Controller is now reachable");
                        }
                        controllerReachable = true;
                    } else {
                        if (controllerReachable) {
                            logger.warn("Controller became unreachable (HTTP {})", result.statusCode());
                        }
                        controllerReachable = false;
                    }
                } catch (Exception e) {
                    if (controllerReachable) {
                        logger.warn("Controller became unreachable: {}", e.getMessage());
                    }
                    controllerReachable = false;
                    logger.debug("Health check failed: {}", e.getMessage());
                }
            }
        ).repeat(10, java.util.concurrent.TimeUnit.SECONDS).schedule();
    }

    private NimbusPermissionProvider permissionProvider;
    private dev.kryonix.nimbus.sdk.NimbusEventStream sharedEventStream;
    private dev.kryonix.nimbus.sdk.NimbusClient sharedSdkClient;
    private NimbusApiClient sharedApiClient;
    private ProxySyncListener proxySyncListener;
    private MaintenanceHandler maintenanceHandler;
    private CloudCommand cloudCommand;
    private volatile boolean controllerReachable = false;

    private void ensureSharedClients(BridgeConfig config) {
        if (sharedSdkClient == null) {
            sharedSdkClient = new dev.kryonix.nimbus.sdk.NimbusClient(config.getApiUrl(), config.getToken());
        }
        if (sharedApiClient == null) {
            sharedApiClient = new NimbusApiClient(config.getApiUrl(), config.getToken());
        }
        if (sharedEventStream == null) {
            sharedEventStream = sharedSdkClient.createEventStream();
        }
    }

    private void registerPermissionProvider() {
        try {
            BridgeConfig config = BridgeConfig.load(dataDirectory);
            if (config == null) return;

            ensureSharedClients(config);
            permissionProvider = new NimbusPermissionProvider(sharedApiClient, logger);

            // Register as Velocity's permission provider
            server.getEventManager().register(this, new PermissionListener(permissionProvider));

            // Listen for permission change events via shared WebSocket
            registerPermissionEventHandlers();

            logger.info("Nimbus Permission Provider registered");
        } catch (Exception e) {
            logger.warn("Failed to register permission provider: {}", e.getMessage());
        }
    }

    /**
     * Registers permission-related event handlers on the shared event stream.
     */
    private void registerPermissionEventHandlers() {
        sharedEventStream.onEvent("PERMISSION_GROUP_CREATED", e -> refreshAllPermissions());
        sharedEventStream.onEvent("PERMISSION_GROUP_UPDATED", e -> refreshAllPermissions());
        sharedEventStream.onEvent("PERMISSION_GROUP_DELETED", e -> refreshAllPermissions());
        sharedEventStream.onEvent("PLAYER_PERMISSIONS_UPDATED", e -> {
            String uuid = e.get("uuid");
            if (uuid != null) {
                try {
                    refreshPlayerPermissions(java.util.UUID.fromString(uuid));
                } catch (IllegalArgumentException ignored) {
                    refreshAllPermissions();
                }
            } else {
                refreshAllPermissions();
            }
        });
    }

    private void refreshAllPermissions() {
        if (permissionProvider == null) return;
        permissionProvider.invalidateAll();
        for (Player player : server.getAllPlayers()) {
            permissionProvider.loadPermissions(player.getUniqueId(), player.getUsername());
        }
        logger.debug("Refreshed permissions for {} online player(s)", server.getPlayerCount());
    }

    private void refreshPlayerPermissions(java.util.UUID uuid) {
        if (permissionProvider == null) return;
        server.getPlayer(uuid).ifPresent(player ->
            permissionProvider.loadPermissions(player.getUniqueId(), player.getUsername())
        );
    }

    private void registerBridge(com.velocitypowered.api.command.CommandManager commandManager) {
        try {
            BridgeConfig config = BridgeConfig.load(dataDirectory);
            if (config == null) {
                logger.info("No bridge.json found — /cloud commands disabled");
                return;
            }

            ensureSharedClients(config);
            cloudCommand = new CloudCommand(sharedApiClient, sharedSdkClient, server);

            // Register /cloud and /nimbus
            for (String alias : new String[]{"cloud", "nimbus"}) {
                var meta = commandManager.metaBuilder(alias)
                    .plugin(this)
                    .build();
                commandManager.register(meta, cloudCommand);
            }

            logger.info("Nimbus Bridge loaded — /cloud, /nimbus registered (API: {})", config.getApiUrl());
        } catch (Exception e) {
            logger.warn("Failed to load bridge config: {}", e.getMessage());
        }
    }

    private static Optional<RegisteredServer> findLobby(ProxyServer server) {
        return server.getAllServers().stream()
            .filter(s -> s.getServerInfo().getName().toLowerCase().startsWith("lobby"))
            .min((a, b) -> a.getServerInfo().getName().compareToIgnoreCase(b.getServerInfo().getName()));
    }

    /**
     * Handles initial connection (force lobby) and kicked-from-server (fallback to lobby).
     * Also enforces global and group maintenance mode.
     */
    private static class ConnectionListener {

        private final ProxyServer server;
        private final Logger logger;
        private final java.util.function.Supplier<MaintenanceHandler> maintenanceSupplier;
        private final net.kyori.adventure.text.minimessage.MiniMessage miniMessage = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
        private final boolean loadBalancerEnabled;
        private final int servicePort;

        ConnectionListener(ProxyServer server, Logger logger, java.util.function.Supplier<MaintenanceHandler> maintenanceSupplier) {
            this.server = server;
            this.logger = logger;
            this.maintenanceSupplier = maintenanceSupplier;
            this.loadBalancerEnabled = Boolean.getBoolean("nimbus.loadbalancer.enabled");
            this.servicePort = Integer.getInteger("nimbus.service.port", -1);
        }

        /**
         * Blocks direct connections that bypass the load balancer.
         * When the LB is active, the Minecraft handshake contains the port the client connected to.
         * If that port matches this proxy's service port, the player connected directly (not via LB).
         */
        @Subscribe
        public void onPreLogin(PreLoginEvent event) {
            if (!loadBalancerEnabled || servicePort <= 0) return;

            var virtualHost = event.getConnection().getVirtualHost();
            if (virtualHost.isPresent() && virtualHost.get().getPort() == servicePort) {
                var address = event.getConnection().getRemoteAddress().getAddress().getHostAddress();
                logger.info("Blocked direct connection from {} on port {} (bypassing load balancer)", address, servicePort);
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    Component.text("Please connect via the correct address.", NamedTextColor.RED)
                ));
            }
        }

        @Subscribe
        public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
            Player player = event.getPlayer();

            // Check global maintenance
            MaintenanceHandler mh = maintenanceSupplier.get();
            if (mh != null && mh.isGlobalEnabled()) {
                // Check bypass: whitelist by name or UUID
                boolean bypass = mh.isWhitelisted(player.getUsername())
                        || mh.isWhitelisted(player.getUniqueId().toString())
                        || player.hasPermission("nimbus.maintenance.bypass");
                if (!bypass) {
                    event.setInitialServer(null);
                    Component kickMsg = parseMiniMessage(mh.getKickMessage());
                    player.disconnect(kickMsg);
                    logger.info("Blocked {} from joining (global maintenance)", player.getUsername());
                    return;
                }
            }

            Optional<RegisteredServer> lobby = findLobby(server);
            if (lobby.isPresent()) {
                event.setInitialServer(lobby.get());
            } else {
                // No lobby available — kick with message
                event.setInitialServer(null);
                player.disconnect(
                    Component.text("No lobby server available. Please try again later.", NamedTextColor.RED)
                );
            }
        }

        @Subscribe
        public void onKickedFromServer(KickedFromServerEvent event) {
            Player player = event.getPlayer();
            String kickedFrom = event.getServer().getServerInfo().getName();

            // If kicked from a non-lobby server, try to send back to lobby
            if (!kickedFrom.toLowerCase().startsWith("lobby")) {
                Optional<RegisteredServer> lobby = findLobby(server);
                if (lobby.isPresent()) {
                    event.setResult(KickedFromServerEvent.RedirectPlayer.create(
                        lobby.get(),
                        Component.text("Sent back to lobby.", NamedTextColor.YELLOW)
                    ));
                    logger.info("Redirected {} to lobby after kick from {}", player.getUsername(), kickedFrom);
                    return;
                }
            }

            // Kicked from lobby or no lobby available — disconnect with message
            Component reason = event.getServerKickReason().orElse(
                Component.text("Connection lost.", NamedTextColor.RED)
            );
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(reason));
        }

        @Subscribe
        public void onServerPreConnect(com.velocitypowered.api.event.player.ServerPreConnectEvent event) {
            MaintenanceHandler mh = maintenanceSupplier.get();
            if (mh == null) return;

            Player player = event.getPlayer();
            var target = event.getOriginalServer();
            String serverName = target.getServerInfo().getName();
            String groupName = deriveGroupName(serverName);

            // Check group maintenance
            if (mh.isGroupInMaintenance(groupName)) {
                boolean bypass = mh.isWhitelisted(player.getUsername())
                        || mh.isWhitelisted(player.getUniqueId().toString())
                        || player.hasPermission("nimbus.maintenance.bypass");
                if (!bypass) {
                    event.setResult(com.velocitypowered.api.event.player.ServerPreConnectEvent.ServerResult.denied());
                    Component msg = parseMiniMessage(mh.getGroupKickMessage(groupName));
                    player.sendMessage(msg);
                    logger.info("Blocked {} from joining {} (group {} in maintenance)", player.getUsername(), serverName, groupName);
                }
            }
        }

        private static String deriveGroupName(String serverName) {
            int lastDash = serverName.lastIndexOf('-');
            if (lastDash > 0) {
                String suffix = serverName.substring(lastDash + 1);
                try {
                    Integer.parseInt(suffix);
                    return serverName.substring(0, lastDash);
                } catch (NumberFormatException e) {
                    return serverName;
                }
            }
            return serverName;
        }

        private Component parseMiniMessage(String input) {
            return miniMessage.deserialize(dev.kryonix.nimbus.sdk.ColorUtil.translate(input));
        }
    }

    /**
     * Injects the Nimbus permission provider into Velocity's permission system
     * and manages the permission cache lifecycle.
     */
    private static class PermissionListener {

        private final NimbusPermissionProvider provider;

        PermissionListener(NimbusPermissionProvider provider) {
            this.provider = provider;
        }

        @Subscribe
        public void onPermissionsSetup(PermissionsSetupEvent event) {
            if (event.getSubject() instanceof Player) {
                event.setProvider(provider);
            }
        }

        @Subscribe
        public void onLogin(LoginEvent event) {
            Player player = event.getPlayer();
            provider.loadPermissions(player.getUniqueId(), player.getUsername());
        }

        @Subscribe
        public void onDisconnect(DisconnectEvent event) {
            provider.invalidate(event.getPlayer().getUniqueId());
        }
    }

    private static class HubCommand implements SimpleCommand {

        private final ProxyServer server;

        HubCommand(ProxyServer server) {
            this.server = server;
        }

        @Override
        public void execute(Invocation invocation) {
            var source = invocation.source();
            if (!(source instanceof Player player)) {
                source.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
                return;
            }

            Optional<RegisteredServer> lobbyServer = findLobby(server);

            if (lobbyServer.isEmpty()) {
                player.sendMessage(Component.text("No lobby server available.", NamedTextColor.RED));
                return;
            }

            // Already on a lobby?
            var currentServer = player.getCurrentServer().orElse(null);
            if (currentServer != null &&
                currentServer.getServerInfo().getName().equalsIgnoreCase(lobbyServer.get().getServerInfo().getName())) {
                player.sendMessage(Component.text("You are already on the lobby.", NamedTextColor.YELLOW));
                return;
            }

            String name = lobbyServer.get().getServerInfo().getName();
            player.sendMessage(
                Component.text("Connecting to ", NamedTextColor.GREEN)
                    .append(Component.text(name, NamedTextColor.WHITE))
                    .append(Component.text("...", NamedTextColor.GREEN))
            );
            player.createConnectionRequest(lobbyServer.get()).fireAndForget();
        }
    }

}
