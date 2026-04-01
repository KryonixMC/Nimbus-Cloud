package dev.kryonix.nimbus.perms.provider;

import com.google.gson.JsonObject;
import dev.kryonix.nimbus.perms.NimbusPermsPlugin;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.event.group.GroupDataRecalculateEvent;
import net.luckperms.api.model.user.User;
import dev.kryonix.nimbus.sdk.compat.SchedulerCompat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * LuckPerms permission provider. Reads permissions and display data from LuckPerms
 * and syncs it to the Nimbus API so the controller stays in sync.
 * LuckPerms handles the actual permission injection natively.
 */
public class LuckPermsProvider implements PermissionProvider {

    private NimbusPermsPlugin plugin;
    private Logger logger;
    private LuckPerms luckPerms;
    private HttpClient httpClient;
    private String apiUrl;
    private String token;

    private final Map<UUID, DisplayInfo> displayCache = new ConcurrentHashMap<>();
    private final List<EventSubscription<?>> subscriptions = new ArrayList<>();

    @Override
    public void enable(NimbusPermsPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.apiUrl = plugin.getApiUrl();
        this.token = plugin.getToken();
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        try {
            this.luckPerms = net.luckperms.api.LuckPermsProvider.get();
        } catch (IllegalStateException e) {
            logger.warning("LuckPerms API not available — falling back to builtin behavior");
            return;
        }

        // Subscribe to LuckPerms events
        subscriptions.add(luckPerms.getEventBus().subscribe(UserDataRecalculateEvent.class, this::onUserDataRecalculate));
        subscriptions.add(luckPerms.getEventBus().subscribe(GroupDataRecalculateEvent.class, this::onGroupDataRecalculate));

        logger.info("LuckPerms provider enabled — syncing to Nimbus API");
    }

    @Override
    public void disable() {
        for (EventSubscription<?> sub : subscriptions) {
            sub.close();
        }
        subscriptions.clear();
        displayCache.clear();
    }

    @Override
    public void onJoin(Player player) {
        // Load display data from LuckPerms and sync to Nimbus API
        SchedulerCompat.runTaskAsync(plugin, () -> {
            User user = luckPerms.getUserManager().getUser(player.getUniqueId());
            if (user == null) return;

            CachedMetaData meta = user.getCachedData().getMetaData();
            String prefix = meta.getPrefix() != null ? meta.getPrefix() : "";
            String suffix = meta.getSuffix() != null ? meta.getSuffix() : "";
            String primaryGroup = user.getPrimaryGroup();

            // Determine priority from primary group weight
            int priority = 0;
            var group = luckPerms.getGroupManager().getGroup(primaryGroup);
            if (group != null) {
                var weight = group.getWeight();
                priority = weight.isPresent() ? weight.getAsInt() : 0;
            }

            displayCache.put(player.getUniqueId(), new DisplayInfo(prefix, suffix, primaryGroup, priority));

            // Sync to Nimbus API
            syncPlayerToApi(player.getUniqueId(), player.getName(), prefix, suffix, primaryGroup);
        });
    }

    @Override
    public void onQuit(Player player) {
        displayCache.remove(player.getUniqueId());
    }

    @Override
    public void refresh(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            onJoin(player);
        }
    }

    @Override
    public void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            onJoin(player);
        }
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

    // ── LuckPerms Event Handlers ────────────────────────────

    private void onUserDataRecalculate(UserDataRecalculateEvent event) {
        UUID uuid = event.getUser().getUniqueId();
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            CachedMetaData meta = event.getUser().getCachedData().getMetaData();
            String prefix = meta.getPrefix() != null ? meta.getPrefix() : "";
            String suffix = meta.getSuffix() != null ? meta.getSuffix() : "";
            String primaryGroup = event.getUser().getPrimaryGroup();

            int priority = 0;
            var group = luckPerms.getGroupManager().getGroup(primaryGroup);
            if (group != null) {
                var weight = group.getWeight();
                priority = weight.isPresent() ? weight.getAsInt() : 0;
            }

            displayCache.put(uuid, new DisplayInfo(prefix, suffix, primaryGroup, priority));
            syncPlayerToApi(uuid, player.getName(), prefix, suffix, primaryGroup);
        }
    }

    private void onGroupDataRecalculate(GroupDataRecalculateEvent event) {
        // When a group changes, refresh all online players
        SchedulerCompat.runTaskAsync(plugin, this::refreshAll);
    }

    // ── Nimbus API Sync ─────────────────────────────────────

    private void syncPlayerToApi(UUID uuid, String name, String prefix, String suffix, String group) {
        try {
            // Register player with Nimbus API
            JsonObject body = new JsonObject();
            body.addProperty("name", name);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/api/permissions/players/" + uuid))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(5))
                    .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .exceptionally(e -> {
                        logger.fine("Failed to sync player " + name + " to Nimbus API: " + e.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            logger.fine("Failed to sync player " + name + " to Nimbus API: " + e.getMessage());
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
