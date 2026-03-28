package dev.nimbus.sdk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Handles Bukkit-side permission injection for Nimbus-managed servers.
 * <p>
 * On player join, fetches effective permissions from the Nimbus API
 * and applies them via {@link PermissionAttachment}. Wildcards are expanded
 * by enumerating all registered server permissions.
 */
public class NimbusPermissionHandler implements Listener {

    private final JavaPlugin plugin;
    private final String apiUrl;
    private final String token;
    private final HttpClient httpClient;
    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();
    private NimbusEventStream eventStream;

    public NimbusPermissionHandler(JavaPlugin plugin, String apiUrl, String token) {
        this.plugin = plugin;
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.token = token;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startEventStream();
        plugin.getLogger().info("Nimbus permission handler started");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        loadAndApply(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        PermissionAttachment attachment = attachments.remove(event.getPlayer().getUniqueId());
        if (attachment != null) {
            try { event.getPlayer().removeAttachment(attachment); } catch (Exception ignored) {}
        }
    }

    public void loadAndApply(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();

        JsonObject body = new JsonObject();
        body.addProperty("name", name);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/api/permissions/players/" + uuid))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(10))
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        plugin.getLogger().warning("[Perms] Failed for " + name + ": HTTP " + response.statusCode() + " — " + response.body());
                        return;
                    }

                    try {
                        JsonObject json = new com.google.gson.Gson().fromJson(response.body(), JsonObject.class);
                        JsonArray permsArray = json.getAsJsonArray("effectivePermissions");

                        Set<String> granted = new HashSet<>();
                        Set<String> negated = new HashSet<>();
                        for (JsonElement elem : permsArray) {
                            String perm = elem.getAsString();
                            if (perm.startsWith("-")) {
                                negated.add(perm.substring(1).toLowerCase());
                            } else {
                                granted.add(perm.toLowerCase());
                            }
                        }

                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline()) return;
                            applyPermissions(player, granted, negated);
                        });
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING, "[Perms] Parse error for " + name, e);
                    }
                })
                .exceptionally(e -> {
                    plugin.getLogger().warning("[Perms] Connection error for " + name + ": " + e.getMessage());
                    return null;
                });
    }

    private void applyPermissions(Player player, Set<String> granted, Set<String> negated) {
        UUID uuid = player.getUniqueId();

        // Remove old attachment
        PermissionAttachment old = attachments.remove(uuid);
        if (old != null) {
            try { player.removeAttachment(old); } catch (Exception ignored) {}
        }

        PermissionAttachment attachment = player.addAttachment(plugin);
        boolean hasWildcard = granted.contains("*");
        int count = 0;

        if (hasWildcard) {
            // * means ALL permissions — enumerate every registered permission on the server
            for (Permission registeredPerm : plugin.getServer().getPluginManager().getPermissions()) {
                String name = registeredPerm.getName().toLowerCase();
                if (!negated.contains(name)) {
                    attachment.setPermission(registeredPerm, true);
                    count++;
                }
            }
            // Also set the literal * and common parent nodes
            attachment.setPermission("*", true);
            count++;
        } else {
            // Expand wildcards like "minecraft.command.*"
            for (String perm : granted) {
                if (perm.endsWith(".*")) {
                    String prefix = perm.substring(0, perm.length() - 1); // "minecraft.command."
                    for (Permission registeredPerm : plugin.getServer().getPluginManager().getPermissions()) {
                        String regName = registeredPerm.getName().toLowerCase();
                        if (regName.startsWith(prefix) && !negated.contains(regName)) {
                            attachment.setPermission(registeredPerm, true);
                            count++;
                        }
                    }
                } else {
                    attachment.setPermission(perm, true);
                    count++;
                }
            }
        }

        // Apply negations
        for (String neg : negated) {
            attachment.setPermission(neg, false);
        }

        attachments.put(uuid, attachment);
        player.recalculatePermissions();
        player.updateCommands();

        int registered = plugin.getServer().getPluginManager().getPermissions().size();
        plugin.getLogger().info("[Perms] " + player.getName() + ": " + count + " granted (of " + registered + " registered), " + negated.size() + " negated, wildcard=" + hasWildcard);
    }

    public void refreshAll() {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                loadAndApply(player);
            }
        });
    }

    public void refresh(UUID uuid) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && player.isOnline()) {
                loadAndApply(player);
            }
        });
    }

    private void startEventStream() {
        try {
            String wsUrl = apiUrl.replace("http://", "ws://").replace("https://", "wss://");
            String url = wsUrl + "/api/events";
            if (token != null && !token.isEmpty()) {
                url += "?token=" + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);
            }

            eventStream = new NimbusEventStream(URI.create(url));
            eventStream.onEvent("PERMISSION_GROUP_CREATED", e -> refreshAll());
            eventStream.onEvent("PERMISSION_GROUP_UPDATED", e -> refreshAll());
            eventStream.onEvent("PERMISSION_GROUP_DELETED", e -> refreshAll());
            eventStream.onEvent("PLAYER_PERMISSIONS_UPDATED", e -> {
                String uuidStr = e.get("uuid");
                if (uuidStr != null) {
                    try {
                        refresh(UUID.fromString(uuidStr));
                    } catch (IllegalArgumentException ignored) {
                        refreshAll();
                    }
                } else {
                    refreshAll();
                }
            });
            eventStream.connect();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to start permission event stream: " + e.getMessage());
        }
    }

    public void shutdown() {
        if (eventStream != null) {
            eventStream.close();
        }
        for (Map.Entry<UUID, PermissionAttachment> entry : attachments.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null) {
                try { player.removeAttachment(entry.getValue()); } catch (Exception ignored) {}
            }
        }
        attachments.clear();
    }
}
