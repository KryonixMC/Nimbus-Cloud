package dev.nimbuspowered.nimbus.display;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Caches Mojang skin data (UUID + texture) to avoid hitting API rate limits
 * when spawning multiple NPCs with the same skin.
 * <p>
 * Stores: playerName → {uuid, textureValue, textureSignature, timestamp}
 * In-memory + file-backed (plugins/NimbusDisplay/skin-cache.properties).
 * Cache entries expire after 24 hours.
 */
public class SkinCache {

    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L; // 24 hours

    private final JavaPlugin plugin;
    private final Path cacheFile;
    private final Map<String, CachedSkin> cache = new ConcurrentHashMap<>();
    private final HttpClient httpClient;

    public record CachedSkin(String uuid, String textureValue, String textureSignature, long timestamp) {
        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    public SkinCache(JavaPlugin plugin) {
        this.plugin = plugin;
        this.cacheFile = plugin.getDataFolder().toPath().resolve("skin-cache.properties");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        loadFromDisk();
    }

    /**
     * Get cached skin for a player name. Returns null if not cached or expired.
     */
    public CachedSkin get(String playerName) {
        CachedSkin skin = cache.get(playerName.toLowerCase());
        if (skin != null && !skin.isExpired()) return skin;
        return null;
    }

    /**
     * Resolve and cache skin data for a player name.
     * Fetches from Mojang API if not cached. Returns null on failure.
     */
    public CachedSkin resolve(String playerName) {
        CachedSkin cached = get(playerName);
        if (cached != null) return cached;

        try {
            // Step 1: Name → UUID
            HttpRequest uuidReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + playerName))
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            HttpResponse<String> uuidResp = httpClient.send(uuidReq, HttpResponse.BodyHandlers.ofString());
            if (uuidResp.statusCode() != 200) return null;

            JsonObject uuidJson = JsonParser.parseString(uuidResp.body()).getAsJsonObject();
            String uuid = uuidJson.get("id").getAsString();

            // Step 2: UUID → Profile (skin textures)
            HttpRequest profileReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false"))
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            HttpResponse<String> profileResp = httpClient.send(profileReq, HttpResponse.BodyHandlers.ofString());
            if (profileResp.statusCode() != 200) return null;

            JsonObject profileJson = JsonParser.parseString(profileResp.body()).getAsJsonObject();
            var properties = profileJson.getAsJsonArray("properties");
            for (var prop : properties) {
                JsonObject propObj = prop.getAsJsonObject();
                if ("textures".equals(propObj.get("name").getAsString())) {
                    String value = propObj.get("value").getAsString();
                    String signature = propObj.has("signature") ? propObj.get("signature").getAsString() : "";

                    CachedSkin skin = new CachedSkin(uuid, value, signature, System.currentTimeMillis());
                    cache.put(playerName.toLowerCase(), skin);
                    saveToDisk();
                    plugin.getLogger().fine("Cached skin for " + playerName);
                    return skin;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to resolve skin for " + playerName, e);
        }
        return null;
    }

    private void loadFromDisk() {
        if (!Files.exists(cacheFile)) return;
        try {
            Properties props = new Properties();
            try (Reader reader = Files.newBufferedReader(cacheFile)) {
                props.load(reader);
            }
            for (String key : props.stringPropertyNames()) {
                if (!key.endsWith(".uuid")) continue;
                String name = key.substring(0, key.length() - ".uuid".length());
                String uuid = props.getProperty(name + ".uuid", "");
                String value = props.getProperty(name + ".value", "");
                String sig = props.getProperty(name + ".signature", "");
                long ts = Long.parseLong(props.getProperty(name + ".timestamp", "0"));
                CachedSkin skin = new CachedSkin(uuid, value, sig, ts);
                if (!skin.isExpired()) {
                    cache.put(name, skin);
                }
            }
            plugin.getLogger().fine("Loaded " + cache.size() + " cached skin(s)");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load skin cache: " + e.getMessage());
        }
    }

    private void saveToDisk() {
        try {
            Files.createDirectories(cacheFile.getParent());
            Properties props = new Properties();
            for (var entry : cache.entrySet()) {
                String name = entry.getKey();
                CachedSkin skin = entry.getValue();
                if (skin.isExpired()) continue;
                props.setProperty(name + ".uuid", skin.uuid());
                props.setProperty(name + ".value", skin.textureValue());
                props.setProperty(name + ".signature", skin.textureSignature());
                props.setProperty(name + ".timestamp", String.valueOf(skin.timestamp()));
            }
            try (Writer writer = Files.newBufferedWriter(cacheFile)) {
                props.store(writer, "Nimbus NPC skin cache");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save skin cache: " + e.getMessage());
        }
    }
}
