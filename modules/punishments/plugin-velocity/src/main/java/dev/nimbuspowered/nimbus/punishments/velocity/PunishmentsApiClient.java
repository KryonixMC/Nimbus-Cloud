package dev.nimbuspowered.nimbus.punishments.velocity;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin blocking HTTP client for the punishments API.
 *
 * Callers are responsible for running off the event thread — on Velocity,
 * the standard pattern is to return {@code EventTask.async(() -> ...)} from
 * {@code @Subscribe} handlers, which does exactly that.
 *
 * Per-UUID caches (login + connect) with a short TTL soak up reconnect spam
 * and chat-burst storms so the controller isn't hit on every packet.
 */
public class PunishmentsApiClient {

    private final String apiUrl;
    private final String token;
    private final Logger logger;
    private final Gson gson = new Gson();

    private final ConcurrentHashMap<String, CacheEntry> checkCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5_000;

    public PunishmentsApiClient(String apiUrl, String token, Logger logger) {
        this.apiUrl = apiUrl;
        this.token = token;
        this.logger = logger;
    }

    /** Network-wide login check (no group/service). Returns null if not punished. */
    public JsonObject checkLogin(UUID uuid, String ip) {
        return doCheck("check", uuid.toString(), ip, null, null, checkCache, "login:" + uuid);
    }

    /** Scoped connect check — called before a backend connection attempt. */
    public JsonObject checkConnect(UUID uuid, String ip, String group, String service) {
        String key = "connect:" + uuid + "|" + group + "|" + service;
        return doCheck("check", uuid.toString(), ip, group, service, checkCache, key);
    }

    /** Remove cached login/connect entries for a UUID — called after a PUNISHMENT_ISSUED event. */
    public void invalidate(UUID uuid) {
        String uuidStr = uuid.toString();
        checkCache.keySet().removeIf(k -> k.contains(uuidStr));
    }

    private JsonObject doCheck(
            String path, String uuid, String ip, String group, String service,
            ConcurrentHashMap<String, CacheEntry> cache, String cacheKey
    ) {
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && now - cached.cachedAtMs < CACHE_TTL_MS) {
            return cached.record;
        }

        StringBuilder url = new StringBuilder(apiUrl).append("/api/punishments/").append(path).append("/").append(uuid);
        boolean hasQuery = false;
        if (ip != null) {
            url.append('?').append("ip=").append(URLEncoder.encode(ip, StandardCharsets.UTF_8));
            hasQuery = true;
        }
        if (group != null) {
            url.append(hasQuery ? '&' : '?').append("group=").append(URLEncoder.encode(group, StandardCharsets.UTF_8));
            hasQuery = true;
        }
        if (service != null) {
            url.append(hasQuery ? '&' : '?').append("service=").append(URLEncoder.encode(service, StandardCharsets.UTF_8));
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url.toString()).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(4000);
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                logger.debug("Punishment check returned HTTP {} for {}", code, uuid);
                cache.put(cacheKey, new CacheEntry(null, now));
                return null;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) body.append(line);
                JsonObject obj = gson.fromJson(body.toString(), JsonObject.class);
                JsonObject record = (obj != null && obj.has("punished") && obj.get("punished").getAsBoolean()) ? obj : null;
                cache.put(cacheKey, new CacheEntry(record, now));
                return record;
            }
        } catch (Exception e) {
            logger.warn("Punishment {} check failed for {}: {}", path, uuid, e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
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
