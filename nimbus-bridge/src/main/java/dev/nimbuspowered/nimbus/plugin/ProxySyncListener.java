package dev.nimbuspowered.nimbus.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Applies tab list (header/footer/player names), MOTD, and chat formatting
 * synced from Nimbus Core to all connected players.
 */
public class ProxySyncListener {

    private final ProxyServer server;
    private final Logger logger;
    private final NimbusApiClient apiClient;
    private final dev.nimbuspowered.nimbus.sdk.NimbusEventStream eventStream;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    // Maintenance handler (set externally after construction)
    private volatile MaintenanceHandler maintenanceHandler;

    // Tab list config
    private volatile String tabHeader = "";
    private volatile String tabFooter = "";
    private volatile String playerFormat = "{prefix}{player}{suffix}";
    private volatile int updateInterval = 5;

    // MOTD config
    private volatile String motdLine1 = "";
    private volatile String motdLine2 = "";
    private volatile int motdMaxPlayers = -1;
    private volatile int motdPlayerCountOffset = 0;

    // Chat config
    private volatile String chatFormat = "{prefix}{player}{suffix} <dark_gray>» <gray>{message}";
    private volatile boolean chatEnabled = true;

    // Nimbus version (from API)
    private volatile String nimbusVersion = "dev";

    // Stress test: simulated player count for MOTD/tab
    private volatile int stressSimulatedPlayers = 0;

    // Per-player tab overrides (UUID string -> MiniMessage format)
    private final ConcurrentHashMap<String, String> playerTabOverrides = new ConcurrentHashMap<>();

    // Per-player display info from permissions (UUID string -> prefix/suffix)
    private final ConcurrentHashMap<UUID, PlayerDisplayInfo> playerDisplayCache = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;

    public ProxySyncListener(ProxyServer server, Logger logger, NimbusApiClient apiClient,
                             dev.nimbuspowered.nimbus.sdk.NimbusEventStream eventStream) {
        this.server = server;
        this.logger = logger;
        this.apiClient = apiClient;
        this.eventStream = eventStream;
    }

    public void setMaintenanceHandler(MaintenanceHandler handler) {
        this.maintenanceHandler = handler;
    }

    public MaintenanceHandler getMaintenanceHandler() {
        return maintenanceHandler;
    }

    /**
     * Initialize: fetch config from API, register event handlers, start refresh task.
     * If the initial config fetch fails, event handlers and scheduler are still registered
     * and a retry is scheduled every 10 seconds until success.
     */
    public void init() {
        // Fetch initial config (graceful — continues with defaults on failure)
        boolean configLoaded = fetchConfigGraceful();
        if (configLoaded) {
            fetchPlayerOverrides();
        } else {
            scheduleConfigRetry();
        }

        // Register WebSocket event handlers
        eventStream.onEvent("TABLIST_UPDATED", e -> {
            tabHeader = getOrDefault(e, "header", tabHeader);
            tabFooter = getOrDefault(e, "footer", tabFooter);
            playerFormat = getOrDefault(e, "playerFormat", playerFormat);
            String interval = e.get("updateInterval");
            if (interval != null) {
                try { updateInterval = Integer.parseInt(interval); } catch (NumberFormatException ignored) {}
            }
            restartScheduler();
            refreshAllTabLists();
            logger.debug("Tab list config updated via event");
        });

        eventStream.onEvent("MOTD_UPDATED", e -> {
            motdLine1 = getOrDefault(e, "line1", motdLine1);
            motdLine2 = getOrDefault(e, "line2", motdLine2);
            String maxP = e.get("maxPlayers");
            if (maxP != null) {
                try { motdMaxPlayers = Integer.parseInt(maxP); } catch (NumberFormatException ignored) {}
            }
            String offset = e.get("playerCountOffset");
            if (offset != null) {
                try { motdPlayerCountOffset = Integer.parseInt(offset); } catch (NumberFormatException ignored) {}
            }
            logger.debug("MOTD config updated via event");
        });

        eventStream.onEvent("CHAT_FORMAT_UPDATED", e -> {
            chatFormat = getOrDefault(e, "format", chatFormat);
            String enabled = e.get("enabled");
            if (enabled != null) chatEnabled = Boolean.parseBoolean(enabled);
            logger.debug("Chat format updated via event");
        });

        eventStream.onEvent("PLAYER_TAB_UPDATED", e -> {
            String uuid = e.get("uuid");
            String format = e.get("format");
            if (uuid == null) return;
            if (format != null && !format.isEmpty()) {
                playerTabOverrides.put(uuid, format);
            } else {
                playerTabOverrides.remove(uuid);
            }
            refreshPlayerTab(uuid);
        });

        // Stress test: simulated player count for MOTD/tab
        eventStream.onEvent("STRESS_TEST_UPDATED", e -> {
            String simulated = e.get("simulatedPlayers");
            if (simulated != null) {
                try { stressSimulatedPlayers = Integer.parseInt(simulated); } catch (NumberFormatException ignored) {}
            }
            logger.debug("Stress test update: {} simulated players", stressSimulatedPlayers);
        });

        // Refresh display cache when permissions change
        eventStream.onEvent("PERMISSION_GROUP_UPDATED", e -> refreshAllDisplayInfo());
        eventStream.onEvent("PLAYER_PERMISSIONS_UPDATED", e -> {
            String uuid = e.get("uuid");
            if (uuid != null) {
                try {
                    fetchPlayerDisplayInfo(UUID.fromString(uuid));
                    refreshPlayerTab(uuid);
                } catch (IllegalArgumentException ignored) {}
            }
        });

        // Start periodic refresh
        startScheduler();
        logger.info("Proxy Sync initialized (tab + MOTD + chat)");
    }

    // ── Velocity Event Handlers ─────────────────────────────────────

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        // Fetch display info for this player
        fetchPlayerDisplayInfo(player.getUniqueId());

        // Delay slightly to ensure tab list is ready
        server.getScheduler().buildTask(server.getPluginManager().getPlugin("nimbus-bridge").orElse(null), () -> {
            applyTabList(player);
            applyPlayerDisplayName(player);
        }).delay(500, TimeUnit.MILLISECONDS).schedule();
    }

    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        // Re-apply when switching servers (server/group placeholders change)
        Player player = event.getPlayer();
        server.getScheduler().buildTask(server.getPluginManager().getPlugin("nimbus-bridge").orElse(null), () -> {
            applyTabList(player);
            applyPlayerDisplayName(player);
        }).delay(250, TimeUnit.MILLISECONDS).schedule();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        playerTabOverrides.remove(uuid.toString());
        playerDisplayCache.remove(uuid);
    }

    @Subscribe
    public void onProxyPing(ProxyPingEvent event) {
        ServerPing ping = event.getPing();
        ServerPing.Builder builder = ping.asBuilder();

        MaintenanceHandler mh = maintenanceHandler;
        if (mh != null && mh.isGlobalEnabled()) {
            // Maintenance mode: custom MOTD, fake protocol version, hide player count
            int online = server.getPlayerCount() + motdPlayerCountOffset + stressSimulatedPlayers;
            int max = motdMaxPlayers > 0 ? motdMaxPlayers : ping.getPlayers().map(ServerPing.Players::getMax).orElse(0);

            String l1 = replacePlaceholders(mh.getMotdLine1(), null, online, max);
            String l2 = replacePlaceholders(mh.getMotdLine2(), null, online, max);

            Component description = parse(l1)
                    .append(Component.newline())
                    .append(parse(l2));

            builder.description(description);

            // Override version protocol to show maintenance text
            // Protocol -1 makes the client show a red "x" and the custom version name
            String protocolText = mh.getProtocolText();
            // Strip MiniMessage tags for protocol text (it doesn't support them)
            String cleanProtocolText = protocolText
                    .replaceAll("<[^>]+>", "")
                    .replace("&[0-9a-fk-or]", "");
            if (cleanProtocolText.isEmpty()) cleanProtocolText = "Maintenance";
            builder.version(new ServerPing.Version(-1, cleanProtocolText));

            // Clear sample players to hide who's online
            builder.clearSamplePlayers();

            event.setPing(builder.build());
            return;
        }

        // Normal mode — add stress test simulated players to the count
        int online = server.getPlayerCount() + motdPlayerCountOffset + stressSimulatedPlayers;
        int max = motdMaxPlayers > 0 ? motdMaxPlayers : ping.getPlayers().map(ServerPing.Players::getMax).orElse(0);

        String l1 = replacePlaceholders(motdLine1, null, online, max);
        String l2 = replacePlaceholders(motdLine2, null, online, max);

        Component description = parse(l1)
                .append(Component.newline())
                .append(parse(l2));

        builder.description(description);
        builder.onlinePlayers(online);
        if (motdMaxPlayers > 0) {
            builder.maximumPlayers(motdMaxPlayers);
        }

        event.setPing(builder.build());
    }

    // ── Tab List Application ────────────────────────────────────────

    private void applyTabList(Player player) {
        int online = server.getPlayerCount() + stressSimulatedPlayers;
        int max = server.getConfiguration().getShowMaxPlayers();

        String header = replacePlaceholders(tabHeader, player, online, max);
        String footer = replacePlaceholders(tabFooter, player, online, max);

        try {
            player.sendPlayerListHeaderAndFooter(
                    parse(header),
                    parse(footer)
            );
        } catch (Exception e) {
            logger.warn("Failed to apply tab header/footer to {}: {}", player.getUsername(), e.getMessage());
        }
    }

    private void applyPlayerDisplayName(Player player) {
        String uuid = player.getUniqueId().toString();
        String format = playerTabOverrides.getOrDefault(uuid, playerFormat);
        int online = server.getPlayerCount();
        int max = server.getConfiguration().getShowMaxPlayers();

        String resolved = replacePlaceholders(format, player, online, max);

        try {
            Component displayName = parse(resolved);

            // Update this player's tab entry for all online players
            for (Player viewer : server.getAllPlayers()) {
                viewer.getTabList().getEntries().stream()
                        .filter(entry -> entry.getProfile().getId().equals(player.getUniqueId()))
                        .findFirst()
                        .ifPresent(entry -> entry.setDisplayName(displayName));
            }
        } catch (Exception e) {
            logger.warn("Failed to apply tab display name for {}: {}", player.getUsername(), e.getMessage());
        }
    }

    private void refreshAllTabLists() {
        for (Player player : server.getAllPlayers()) {
            applyTabList(player);
            applyPlayerDisplayName(player);
        }
    }

    private void refreshPlayerTab(String uuid) {
        try {
            UUID playerUuid = UUID.fromString(uuid);
            server.getPlayer(playerUuid).ifPresent(this::applyPlayerDisplayName);
        } catch (IllegalArgumentException ignored) {}
    }

    // ── Placeholder Replacement ─────────────────────────────────────

    private String replacePlaceholders(String template, Player player, int online, int max) {
        String result = template
                .replace("{online}", String.valueOf(online))
                .replace("{max}", String.valueOf(max))
                .replace("{version}", nimbusVersion);

        if (player != null) {
            result = result.replace("{player}", player.getUsername());

            // Prefix/suffix from permission group
            PlayerDisplayInfo display = playerDisplayCache.get(player.getUniqueId());
            result = result.replace("{prefix}", display != null ? display.prefix : "");
            result = result.replace("{suffix}", display != null ? display.suffix : "");

            var currentServer = player.getCurrentServer().orElse(null);
            if (currentServer != null) {
                String serverName = currentServer.getServerInfo().getName();
                result = result.replace("{server}", serverName);
                result = result.replace("{group}", deriveGroupName(serverName));
            } else {
                result = result.replace("{server}", "");
                result = result.replace("{group}", "");
            }
        }

        return result;
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

    // ── Display Info (Prefix/Suffix from Permissions) ───────────────

    private void fetchPlayerDisplayInfo(UUID uuid) {
        apiClient.get("/api/permissions/players/" + uuid).thenAccept(result -> {
            if (!result.isSuccess()) return;
            try {
                JsonObject json = result.asJson();
                String prefix = getJsonString(json, "prefix", "");
                String suffix = getJsonString(json, "suffix", "");
                playerDisplayCache.put(uuid, new PlayerDisplayInfo(prefix, suffix));
                // Instant tab update after fetching new display info
                server.getPlayer(uuid).ifPresent(player -> {
                    applyPlayerDisplayName(player);
                    applyTabList(player);
                });
            } catch (Exception e) {
                logger.debug("Failed to fetch display info for {}: {}", uuid, e.getMessage());
            }
        }).exceptionally(e -> {
            logger.debug("Failed to fetch display info for {}: {}", uuid, e.getMessage());
            return null;
        });
    }

    private void refreshAllDisplayInfo() {
        for (Player player : server.getAllPlayers()) {
            fetchPlayerDisplayInfo(player.getUniqueId());
        }
    }

    // ── Scheduler ───────────────────────────────────────────────────

    private void startScheduler() {
        if (updateInterval <= 0) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nimbus-tab-refresh");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::refreshAllTabLists, updateInterval, updateInterval, TimeUnit.SECONDS);
    }

    private void restartScheduler() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        startScheduler();
    }

    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (configRetryScheduler != null) {
            configRetryScheduler.shutdownNow();
        }
    }

    // ── Config Retry / Refetch ──────────────────────────────────────

    private volatile ScheduledExecutorService configRetryScheduler;

    /**
     * Fetch config gracefully — returns true on success, false on failure.
     */
    private boolean fetchConfigGraceful() {
        try {
            fetchConfig();
            return true;
        } catch (Exception e) {
            logger.warn("Failed to fetch proxy config (will retry): {}", e.getMessage());
            return false;
        }
    }

    /**
     * Schedule periodic config retry every 10s until successful.
     */
    private void scheduleConfigRetry() {
        if (configRetryScheduler != null) return;
        configRetryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nimbus-config-retry");
            t.setDaemon(true);
            return t;
        });
        configRetryScheduler.scheduleAtFixedRate(() -> {
            try {
                fetchConfig();
                fetchPlayerOverrides();
                logger.info("Proxy sync config loaded after retry");
                // Success — stop retrying
                var retryExec = configRetryScheduler;
                configRetryScheduler = null;
                if (retryExec != null) retryExec.shutdownNow();
            } catch (Exception e) {
                logger.debug("Config retry failed, will try again: {}", e.getMessage());
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    /**
     * Public method to re-fetch config and maintenance state from the API.
     * Called from the reconnect callback when the controller becomes reachable again.
     */
    public void refetchConfig() {
        try {
            fetchConfig();
            fetchPlayerOverrides();
            refreshAllTabLists();
            logger.info("Proxy sync config re-fetched after reconnect");
        } catch (Exception e) {
            logger.warn("Failed to re-fetch proxy config: {}", e.getMessage());
        }
    }

    // ── API Fetching ────────────────────────────────────────────────

    private void fetchConfig() {
        var result = apiClient.get("/api/proxy/config").join();
        if (!result.isSuccess()) {
            throw new RuntimeException("Failed to fetch proxy config: HTTP " + result.statusCode());
        }
        JsonObject json = result.asJson();

        JsonObject tablist = json.getAsJsonObject("tablist");
        if (tablist != null) {
            tabHeader = getJsonString(tablist, "header", tabHeader);
            tabFooter = getJsonString(tablist, "footer", tabFooter);
            playerFormat = getJsonString(tablist, "playerFormat", playerFormat);
            if (tablist.has("updateInterval")) updateInterval = tablist.get("updateInterval").getAsInt();
        }

        JsonObject motd = json.getAsJsonObject("motd");
        if (motd != null) {
            motdLine1 = getJsonString(motd, "line1", motdLine1);
            motdLine2 = getJsonString(motd, "line2", motdLine2);
            if (motd.has("maxPlayers")) motdMaxPlayers = motd.get("maxPlayers").getAsInt();
            if (motd.has("playerCountOffset")) motdPlayerCountOffset = motd.get("playerCountOffset").getAsInt();
        }

        JsonObject chat = json.getAsJsonObject("chat");
        if (chat != null) {
            chatFormat = getJsonString(chat, "format", chatFormat);
            if (chat.has("enabled")) chatEnabled = chat.get("enabled").getAsBoolean();
        }

        if (json.has("version") && !json.get("version").isJsonNull()) {
            nimbusVersion = json.get("version").getAsString();
        }

        // Load maintenance state from proxy config
        MaintenanceHandler mh = maintenanceHandler;
        if (mh != null && json.has("maintenance") && json.get("maintenance").isJsonObject()) {
            mh.loadFromProxyConfig(json.getAsJsonObject("maintenance"));
        }

        logger.info("Loaded proxy sync config from API");
    }

    private void fetchPlayerOverrides() {
        try {
            var result = apiClient.get("/api/proxy/tablist/players").join();
            if (!result.isSuccess()) return;
            JsonObject json = result.asJson();
            JsonObject overrides = json.getAsJsonObject("overrides");
            if (overrides != null) {
                for (Map.Entry<String, JsonElement> entry : overrides.entrySet()) {
                    playerTabOverrides.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch player tab overrides: {}", e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static String getOrDefault(dev.nimbuspowered.nimbus.sdk.NimbusEvent event, String key, String defaultValue) {
        String value = event.get(key);
        return value != null ? value : defaultValue;
    }

    private static String getJsonString(JsonObject obj, String key, String defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return defaultValue;
    }

    /**
     * Parses a string with legacy {@code &} color codes and MiniMessage tags into a Component.
     */
    private Component parse(String input) {
        return miniMessage.deserialize(dev.nimbuspowered.nimbus.sdk.ColorUtil.translate(input));
    }

    record PlayerDisplayInfo(String prefix, String suffix) {}
}
