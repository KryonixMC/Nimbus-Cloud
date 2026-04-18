package dev.nimbuspowered.nimbus.perms.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.nimbuspowered.nimbus.perms.NimbusPermsPlugin;
import dev.nimbuspowered.nimbus.sdk.Nimbus;
import dev.nimbuspowered.nimbus.sdk.compat.SchedulerCompat;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;

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
import java.util.function.Consumer;

/**
 * Built-in permission provider that fetches permissions from the Nimbus API.
 * This is the default provider when LuckPerms is not installed.
 */
public class BuiltinProvider implements PermissionProvider {

    private NimbusPermsPlugin plugin;
    private String apiUrl;
    private String token;
    private HttpClient httpClient;

    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();
    private final Map<UUID, DisplayInfo> displayCache = new ConcurrentHashMap<>();
    private Consumer<Player> displayLoadedCallback;

    @Override
    public void enable(NimbusPermsPlugin plugin) {
        this.plugin = plugin;
        this.apiUrl = plugin.getApiUrl();
        this.token = plugin.getToken();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        startEventStream();
        plugin.getLogger().info("Built-in permission provider enabled");
    }

    @Override
    public void disable() {
        // Event stream is managed by the SDK — no need to close it here
        for (Map.Entry<UUID, PermissionAttachment> entry : attachments.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null) {
                try { player.removeAttachment(entry.getValue()); } catch (Exception ignored) {}
            }
        }
        attachments.clear();
        displayCache.clear();
    }

    @Override
    public void onJoin(Player player) {
        loadAndApply(player);
    }

    @Override
    public void onQuit(Player player) {
        PermissionAttachment attachment = attachments.remove(player.getUniqueId());
        if (attachment != null) {
            try { player.removeAttachment(attachment); } catch (Exception ignored) {}
        }
        displayCache.remove(player.getUniqueId());
    }

    @Override
    public void refresh(UUID uuid) {
        SchedulerCompat.runTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && player.isOnline()) {
                loadAndApply(player);
            }
        });
    }

    @Override
    public void refreshAll() {
        SchedulerCompat.runTask(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                loadAndApply(player);
            }
        });
    }

    @Override
    public String getPrefix(UUID uuid) {
        DisplayInfo info = displayCache.get(uuid);
        return info != null ? info.prefix : "";
    }

    @Override
    public String getSuffix(UUID uuid) {
        DisplayInfo info = displayCache.get(uuid);
        return info != null ? info.suffix : "";
    }

    @Override
    public int getPriority(UUID uuid) {
        DisplayInfo info = displayCache.get(uuid);
        return info != null ? info.priority : 0;
    }

    /** Set a callback that is invoked on the main thread after display data is loaded for a player. */
    @Override
    public void setDisplayLoadedCallback(Consumer<Player> callback) {
        this.displayLoadedCallback = callback;
    }

    private void loadAndApply(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();

        JsonObject body = new JsonObject();
        body.addProperty("name", name);

        // Pass server name for context-aware permission resolution
        String urlStr = apiUrl + "/api/permissions/players/" + uuid;
        String serverName = System.getProperty("nimbus.service.name", "");
        if (!serverName.isEmpty()) {
            urlStr += "?server=" + java.net.URLEncoder.encode(serverName, java.nio.charset.StandardCharsets.UTF_8);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlStr))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(10))
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        plugin.getLogger().warning("[Perms] Failed for " + name + ": HTTP " + response.statusCode());
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

                        // Cache display info
                        String prefix = json.has("prefix") && !json.get("prefix").isJsonNull() ? json.get("prefix").getAsString() : "";
                        String suffix = json.has("suffix") && !json.get("suffix").isJsonNull() ? json.get("suffix").getAsString() : "";
                        String displayGroup = json.has("displayGroup") && !json.get("displayGroup").isJsonNull() ? json.get("displayGroup").getAsString() : "";
                        int priority = json.has("priority") && !json.get("priority").isJsonNull() ? json.get("priority").getAsInt() : 0;
                        displayCache.put(uuid, new DisplayInfo(prefix, suffix, displayGroup, priority));

                        SchedulerCompat.runForEntity(plugin, player, () -> {
                            if (!player.isOnline()) return;
                            applyPermissions(player, granted, negated);
                            if (displayLoadedCallback != null) {
                                displayLoadedCallback.accept(player);
                            }
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

        PermissionAttachment old = attachments.remove(uuid);
        if (old != null) {
            try { player.removeAttachment(old); } catch (Exception ignored) {}
        }

        PermissionAttachment attachment = player.addAttachment(plugin);
        boolean hasWildcard = granted.contains("*");
        int count = 0;

        if (hasWildcard) {
            for (Permission registeredPerm : plugin.getServer().getPluginManager().getPermissions()) {
                String pname = registeredPerm.getName().toLowerCase();
                if (!negated.contains(pname)) {
                    attachment.setPermission(registeredPerm, true);
                    count++;
                }
            }
            attachment.setPermission("*", true);
            count++;
        } else {
            for (String perm : granted) {
                if (perm.endsWith(".*")) {
                    String prefix = perm.substring(0, perm.length() - 1);
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

        for (String neg : negated) {
            attachment.setPermission(neg, false);
        }

        attachments.put(uuid, attachment);
        player.recalculatePermissions();
        // updateCommands() is only available on Paper 1.13+ — use safely
        try { player.updateCommands(); } catch (NoSuchMethodError ignored) {}

        int registered = plugin.getServer().getPluginManager().getPermissions().size();
        plugin.getLogger().info("[Perms] " + player.getName() + ": " + count + " granted (of " + registered + " registered), " + negated.size() + " negated, wildcard=" + hasWildcard);
    }

    private void startEventStream() {
        // Reuse the SDK's event stream instead of creating a duplicate connection
        var sdkStream = Nimbus.events();
        if (sdkStream != null) {
            sdkStream.onEvent("PERMISSION_GROUP_CREATED", e -> refreshAll());
            sdkStream.onEvent("PERMISSION_GROUP_UPDATED", e -> refreshAll());
            sdkStream.onEvent("PERMISSION_GROUP_DELETED", e -> refreshAll());
            sdkStream.onEvent("PLAYER_PERMISSIONS_UPDATED", e -> {
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
            plugin.getLogger().info("Using SDK event stream for permission updates");
        } else {
            plugin.getLogger().warning("SDK event stream not available — permission updates will only apply on player join");
        }
    }

    private static final class DisplayInfo {
        final String prefix, suffix, group;
        final int priority;
        DisplayInfo(String prefix, String suffix, String group, int priority) {
            this.prefix = prefix; this.suffix = suffix; this.group = group; this.priority = priority;
        }
    }
}
