package dev.kryonix.nimbus.perms.display;

import com.google.gson.JsonObject;
import dev.kryonix.nimbus.perms.provider.PermissionProvider;
import dev.kryonix.nimbus.sdk.ColorUtil;
import dev.kryonix.nimbus.sdk.Nimbus;
import dev.kryonix.nimbus.sdk.compat.TextCompat;
import dev.kryonix.nimbus.sdk.compat.VersionHelper;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Handles chat formatting on backend servers using the proxy sync config
 * and permission group prefix/suffix from the permission provider.
 * <p>
 * Supports both Paper 1.16.5+ (AsyncChatEvent with Adventure) and
 * legacy Spigot 1.8+ (AsyncPlayerChatEvent with legacy color codes).
 */
public class ChatRenderer implements Listener {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final String apiUrl;
    private final String token;
    private final HttpClient httpClient;
    private final PermissionProvider provider;

    private volatile String chatFormat = "{prefix}{player}{suffix} &8» &7{message}";
    private volatile boolean chatEnabled = true;

    public ChatRenderer(JavaPlugin plugin, String apiUrl, String token, PermissionProvider provider) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.token = token;
        this.provider = provider;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public void start() {
        fetchChatConfig();

        // Register the appropriate chat handler based on server capabilities
        if (VersionHelper.hasAsyncChatEvent()) {
            // Paper 1.16.5+: use AsyncChatEvent with Adventure rendering
            new ModernChatHandler(this).register(plugin);
        } else {
            // Legacy Spigot 1.8+: use AsyncPlayerChatEvent
            plugin.getServer().getPluginManager().registerEvents(new LegacyChatHandler(this), plugin);
        }

        if (Nimbus.events() != null) {
            Nimbus.events().onEvent("CHAT_FORMAT_UPDATED", e -> {
                String fmt = e.get("format");
                if (fmt != null) chatFormat = fmt;
                String en = e.get("enabled");
                if (en != null) chatEnabled = Boolean.parseBoolean(en);
                logger.fine("Chat format updated via event");
            });

            Nimbus.events().onEvent("PERMISSION_GROUP_UPDATED", e -> provider.refreshAll());

            Nimbus.events().onEvent("PLAYER_PERMISSIONS_UPDATED", e -> {
                String uuid = e.get("uuid");
                if (uuid != null) {
                    try {
                        provider.refresh(UUID.fromString(uuid));
                    } catch (IllegalArgumentException ignored) {}
                }
            });
        }

        logger.info("Chat renderer started" + (VersionHelper.hasAsyncChatEvent() ? " (modern)" : " (legacy)"));
    }

    /** Format a chat message using the configured format and player's prefix/suffix. */
    String formatChat(Player source, String plainMessage) {
        if (!chatEnabled) return null;

        String prefix = provider.getPrefix(source.getUniqueId());
        String suffix = provider.getSuffix(source.getUniqueId());

        return chatFormat
                .replace("{prefix}", prefix != null ? prefix : "")
                .replace("{suffix}", suffix != null ? suffix : "")
                .replace("{player}", source.getName())
                .replace("{message}", plainMessage)
                .replace("{server}", Nimbus.isManaged() ? Nimbus.name() : "")
                .replace("{group}", Nimbus.isManaged() ? Nimbus.group() : "");
    }

    boolean isChatEnabled() { return chatEnabled; }

    public void removePlayer(UUID uuid) {
        // Display cache cleanup is handled by provider.onQuit()
    }

    private void fetchChatConfig() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/api/proxy/chat"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                logger.warning("Failed to fetch chat config: HTTP " + response.statusCode());
                return;
            }

            JsonObject json = new com.google.gson.Gson().fromJson(response.body(), JsonObject.class);
            if (json.has("format")) chatFormat = json.get("format").getAsString();
            if (json.has("enabled")) chatEnabled = json.get("enabled").getAsBoolean();
            logger.info("Loaded chat format from API");
        } catch (Exception e) {
            logger.warning("Failed to fetch chat config: " + e.getMessage());
        }
    }
}
