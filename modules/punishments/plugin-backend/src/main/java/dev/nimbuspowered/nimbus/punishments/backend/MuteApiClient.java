package dev.nimbuspowered.nimbus.punishments.backend;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Blocking HTTP client for {@code GET /api/punishments/mute/{uuid}?group=&service=}.
 *
 * <p>Called from {@link org.bukkit.event.player.AsyncPlayerChatEvent} which is
 * already off the main thread, so a synchronous HTTP call is fine. A short TTL
 * cache soaks up chat bursts (spam) without hammering the controller.
 */
public class MuteApiClient {

    private final String apiUrl;
    private final String token;
    private final String groupName;
    private final String serviceName;
    private final Logger logger;
    private final Gson gson = new Gson();

    private final ConcurrentHashMap<UUID, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 3_000;

    public MuteApiClient(String apiUrl, String token, String serviceName, String groupName, Logger logger) {
        this.apiUrl = apiUrl;
        this.token = token;
        this.serviceName = serviceName;
        this.groupName = groupName;
        this.logger = logger;
    }

    /** @return the mute record for this player on this backend, or null if not muted. */
    public JsonObject checkMute(UUID uuid) {
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(uuid);
        if (cached != null && now - cached.cachedAtMs < CACHE_TTL_MS) return cached.record;

        StringBuilder url = new StringBuilder(apiUrl)
                .append("/api/punishments/mute/").append(uuid);
        boolean q = false;
        if (groupName != null && !groupName.isEmpty()) {
            url.append('?').append("group=").append(URLEncoder.encode(groupName, StandardCharsets.UTF_8));
            q = true;
        }
        if (serviceName != null && !serviceName.isEmpty()) {
            url.append(q ? '&' : '?').append("service=").append(URLEncoder.encode(serviceName, StandardCharsets.UTF_8));
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url.toString()).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(3000);
            if (token != null && !token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                cache.put(uuid, new CacheEntry(null, now));
                return null;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) body.append(line);
                JsonObject obj = gson.fromJson(body.toString(), JsonObject.class);
                JsonObject record = (obj != null && obj.has("punished") && obj.get("punished").getAsBoolean()) ? obj : null;
                cache.put(uuid, new CacheEntry(record, now));
                return record;
            }
        } catch (Exception e) {
            logger.log(Level.FINE, "Mute check failed for " + uuid + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Purge cached answers for a player — called from a quit listener if needed. */
    public void invalidate(UUID uuid) {
        cache.remove(uuid);
    }

    private static class CacheEntry {
        final JsonObject record;
        final long cachedAtMs;
        CacheEntry(JsonObject record, long cachedAtMs) {
            this.record = record;
            this.cachedAtMs = cachedAtMs;
        }
    }
}
