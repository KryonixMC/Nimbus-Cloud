package dev.nimbuspowered.nimbus.resourcepacks;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.nimbuspowered.nimbus.sdk.Nimbus;
import dev.nimbuspowered.nimbus.sdk.NimbusSelfService;
import dev.nimbuspowered.nimbus.sdk.compat.SchedulerCompat;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * Applies network-wide resource packs to players on join.
 *
 * Queries the controller at {@code /api/resourcepacks/for-group/<group>?service=<name>}
 * to get the ordered stack of packs. On Paper 1.20.3+, multiple packs are applied via
 * {@link org.bukkit.entity.Player#setResourcePack(UUID, String, byte[], String, boolean)};
 * on older servers, only the highest-priority pack is applied (single-pack API).
 *
 * Reports player accept/decline/load events back to the controller for analytics.
 */
public class NimbusResourcePacksPlugin extends JavaPlugin implements Listener {

    private String apiUrl;
    private String token;
    private final Gson gson = new Gson();
    // Cache the resolved pack list per group to avoid hammering the controller on join spikes
    private final AtomicReference<List<ResolvedPack>> cachedPacks = new AtomicReference<>(List.of());
    private long cacheUntilMs = 0;
    private static final long CACHE_TTL_MS = 10_000;

    private String serviceName;
    private String groupName;
    private boolean multiPackApi;

    @Override
    public void onEnable() {
        if (!Nimbus.isManaged()) {
            getLogger().warning("Not running in a Nimbus-managed service — NimbusResourcePacks disabled");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        apiUrl = System.getProperty("nimbus.api.url");
        String envToken = System.getenv("NIMBUS_API_TOKEN");
        token = (envToken != null && !envToken.isEmpty()) ? envToken : System.getProperty("nimbus.api.token", "");
        if (apiUrl == null || apiUrl.isEmpty()) {
            getLogger().warning("No API URL configured — disabling");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (apiUrl.endsWith("/")) apiUrl = apiUrl.substring(0, apiUrl.length() - 1);

        serviceName = System.getProperty("nimbus.service.name", "");
        groupName = System.getProperty("nimbus.service.group", "");

        // Detect multi-pack API (Paper 1.20.3+) via reflection — avoids a compile-time hard dep
        multiPackApi = hasMultiPackApi();

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("NimbusResourcePacks enabled (multi-pack=" + multiPackApi + ", group=" + groupName + ")");
    }

    private boolean hasMultiPackApi() {
        try {
            org.bukkit.entity.Player.class.getMethod(
                "setResourcePack", UUID.class, String.class, byte[].class, String.class, boolean.class
            );
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        SchedulerCompat.runTaskAsync(this, () -> {
            List<ResolvedPack> packs = getPacks();
            if (packs.isEmpty()) return;
            SchedulerCompat.runTask(this, () -> applyPacks(player, packs));
        });
    }

    private synchronized List<ResolvedPack> getPacks() {
        long now = System.currentTimeMillis();
        if (now < cacheUntilMs) return cachedPacks.get();

        String path = "/api/resourcepacks/for-group/" + URLEncoder.encode(groupName, StandardCharsets.UTF_8)
                + "?service=" + URLEncoder.encode(serviceName, StandardCharsets.UTF_8);
        try {
            String body = httpGet(path);
            if (body == null) return cachedPacks.get();
            JsonObject obj = gson.fromJson(body, JsonObject.class);
            List<ResolvedPack> list = new ArrayList<>();
            if (obj != null && obj.has("packs")) {
                for (var el : obj.getAsJsonArray("packs")) {
                    JsonObject p = el.getAsJsonObject();
                    list.add(new ResolvedPack(
                            UUID.fromString(p.get("packUuid").getAsString()),
                            p.get("name").getAsString(),
                            p.get("url").getAsString(),
                            p.get("sha1Hash").getAsString(),
                            p.has("promptMessage") ? p.get("promptMessage").getAsString() : "",
                            p.has("force") && p.get("force").getAsBoolean()
                    ));
                }
            }
            cachedPacks.set(list);
            cacheUntilMs = now + CACHE_TTL_MS;
            return list;
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to fetch packs: " + e.getMessage());
            return cachedPacks.get();
        }
    }

    private void applyPacks(org.bukkit.entity.Player player, List<ResolvedPack> packs) {
        if (multiPackApi) {
            try {
                var method = org.bukkit.entity.Player.class.getMethod(
                        "setResourcePack", UUID.class, String.class, byte[].class, String.class, boolean.class
                );
                for (ResolvedPack pack : packs) {
                    byte[] hash = hexToBytes(pack.sha1);
                    method.invoke(player, pack.uuid, pack.url, hash, pack.prompt, pack.force);
                }
                return;
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Multi-pack API call failed, falling back: " + e.getMessage());
            }
        }
        // Legacy single-pack fallback
        ResolvedPack pick = packs.get(packs.size() - 1); // highest priority (last in ascending list)
        try {
            player.setResourcePack(pick.url, hexToBytes(pick.sha1), pick.prompt, pick.force);
        } catch (NoSuchMethodError e) {
            // Older APIs without prompt
            player.setResourcePack(pick.url, hexToBytes(pick.sha1));
        }
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent event) {
        String status = event.getStatus().name();
        UUID packUuid = null;
        // Paper 1.20.3+ exposes getID() — use reflection to stay compile-compatible
        try {
            var getId = event.getClass().getMethod("getID");
            Object val = getId.invoke(event);
            if (val instanceof UUID) packUuid = (UUID) val;
        } catch (Exception ignored) {}

        UUID player = event.getPlayer().getUniqueId();
        UUID finalPackUuid = packUuid;
        SchedulerCompat.runTaskAsync(this, () -> reportStatus(player, finalPackUuid, status));
    }

    private void reportStatus(UUID player, UUID pack, String status) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("playerUuid", player.toString());
            body.addProperty("packUuid", pack != null ? pack.toString() : "00000000-0000-0000-0000-000000000000");
            body.addProperty("status", status);
            httpPost("/api/resourcepacks/status", body.toString());
        } catch (Exception e) {
            getLogger().log(Level.FINE, "Failed to report pack status: " + e.getMessage());
        }
    }

    // ── HTTP helpers ───────────────────────────────────────────

    private String httpGet(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(apiUrl + path).toURL().openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            if (token != null && !token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        } finally {
            conn.disconnect();
        }
    }

    private void httpPost(String path, String jsonBody) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(apiUrl + path).toURL().openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            if (token != null && !token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
            conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }

    private record ResolvedPack(UUID uuid, String name, String url, String sha1, String prompt, boolean force) {}
}
