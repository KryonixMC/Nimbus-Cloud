package dev.kryonix.nimbus.plugin;

import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks maintenance state received from Nimbus Core via API and WebSocket events.
 * Used by ProxySyncListener (MOTD/protocol) and ConnectionListener (join blocking).
 */
public class MaintenanceHandler {

    private final Logger logger;

    // Global maintenance state
    private volatile boolean globalEnabled = false;
    private volatile String motdLine1 = "  <gradient:#ff6b6b:#ee5a24><bold>MAINTENANCE</bold></gradient>";
    private volatile String motdLine2 = "  <gray>We are currently performing maintenance.</gray>";
    private volatile String protocolText = "<red><bold>Maintenance</bold></red>";
    private volatile String kickMessage = "<red><bold>Maintenance</bold></red>\n<gray>The server is currently under maintenance.\nPlease try again later.</gray>";
    private final Set<String> whitelist = ConcurrentHashMap.newKeySet();

    // Group maintenance: groupName -> kickMessage
    private final ConcurrentHashMap<String, String> maintenanceGroups = new ConcurrentHashMap<>();

    public MaintenanceHandler(Logger logger) {
        this.logger = logger;
    }

    // ── Global ─────────────────────────────────────────────────────

    public boolean isGlobalEnabled() { return globalEnabled; }
    public String getMotdLine1() { return motdLine1; }
    public String getMotdLine2() { return motdLine2; }
    public String getProtocolText() { return protocolText; }
    public String getKickMessage() { return kickMessage; }

    public boolean isWhitelisted(String nameOrUuid) {
        return whitelist.contains(nameOrUuid.toLowerCase());
    }

    // ── Group ──────────────────────────────────────────────────────

    public boolean isGroupInMaintenance(String groupName) {
        return maintenanceGroups.containsKey(groupName);
    }

    public String getGroupKickMessage(String groupName) {
        return maintenanceGroups.getOrDefault(groupName,
                "<red>This game mode is currently under maintenance.</red>");
    }

    public Set<String> getMaintenanceGroups() {
        return Collections.unmodifiableSet(maintenanceGroups.keySet());
    }

    // ── State Updates ──────────────────────────────────────────────

    /**
     * Load initial maintenance state from the proxy config API response.
     */
    public void loadFromProxyConfig(JsonObject maintenance) {
        if (maintenance == null) return;

        globalEnabled = maintenance.has("globalEnabled") && maintenance.get("globalEnabled").getAsBoolean();
        if (maintenance.has("motdLine1")) motdLine1 = maintenance.get("motdLine1").getAsString();
        if (maintenance.has("motdLine2")) motdLine2 = maintenance.get("motdLine2").getAsString();
        if (maintenance.has("protocolText")) protocolText = maintenance.get("protocolText").getAsString();
        if (maintenance.has("kickMessage")) kickMessage = maintenance.get("kickMessage").getAsString();

        whitelist.clear();
        if (maintenance.has("whitelist") && maintenance.get("whitelist").isJsonArray()) {
            for (var entry : maintenance.getAsJsonArray("whitelist")) {
                whitelist.add(entry.getAsString().toLowerCase());
            }
        }

        maintenanceGroups.clear();
        if (maintenance.has("groups") && maintenance.get("groups").isJsonObject()) {
            for (var entry : maintenance.getAsJsonObject("groups").entrySet()) {
                maintenanceGroups.put(entry.getKey(), entry.getValue().getAsString());
            }
        }

        logger.info("Loaded maintenance state: global={}, groups={}", globalEnabled, maintenanceGroups.keySet());
    }

    /**
     * Handle MAINTENANCE_ENABLED event from WebSocket.
     */
    public void onMaintenanceEnabled(Map<String, String> data) {
        String scope = data.get("scope");
        if (scope == null) return;

        if ("global".equals(scope)) {
            globalEnabled = true;
            logger.info("Global maintenance ENABLED");
            // Refresh full state from API for MOTD/protocol/whitelist
        } else {
            maintenanceGroups.put(scope, "<red>This game mode is currently under maintenance.</red>");
            logger.info("Group '{}' maintenance ENABLED", scope);
        }
    }

    /**
     * Handle MAINTENANCE_DISABLED event from WebSocket.
     */
    public void onMaintenanceDisabled(Map<String, String> data) {
        String scope = data.get("scope");
        if (scope == null) return;

        if ("global".equals(scope)) {
            globalEnabled = false;
            logger.info("Global maintenance DISABLED");
        } else {
            maintenanceGroups.remove(scope);
            logger.info("Group '{}' maintenance DISABLED", scope);
        }
    }

    /**
     * Refresh full state from the API (called after MAINTENANCE_ENABLED to get config details).
     */
    public void refreshFromApi(NimbusApiClient apiClient) {
        apiClient.get("/api/maintenance").thenAccept(result -> {
            if (!result.isSuccess()) return;
            try {
                JsonObject json = result.asJson();
                JsonObject global = json.getAsJsonObject("global");
                if (global != null) {
                    globalEnabled = global.has("enabled") && global.get("enabled").getAsBoolean();
                    if (global.has("motdLine1")) motdLine1 = global.get("motdLine1").getAsString();
                    if (global.has("motdLine2")) motdLine2 = global.get("motdLine2").getAsString();
                    if (global.has("protocolText")) protocolText = global.get("protocolText").getAsString();
                    if (global.has("kickMessage")) kickMessage = global.get("kickMessage").getAsString();

                    whitelist.clear();
                    if (global.has("whitelist") && global.get("whitelist").isJsonArray()) {
                        for (var entry : global.getAsJsonArray("whitelist")) {
                            whitelist.add(entry.getAsString().toLowerCase());
                        }
                    }
                }

                maintenanceGroups.clear();
                if (json.has("groups") && json.get("groups").isJsonObject()) {
                    for (var entry : json.getAsJsonObject("groups").entrySet()) {
                        JsonObject groupObj = entry.getValue().getAsJsonObject();
                        if (groupObj.has("enabled") && groupObj.get("enabled").getAsBoolean()) {
                            String msg = groupObj.has("kickMessage") ? groupObj.get("kickMessage").getAsString()
                                    : "<red>This game mode is currently under maintenance.</red>";
                            maintenanceGroups.put(entry.getKey(), msg);
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to parse maintenance state: {}", e.getMessage());
            }
        }).exceptionally(e -> {
            logger.debug("Failed to refresh maintenance state: {}", e.getMessage());
            return null;
        });
    }
}
