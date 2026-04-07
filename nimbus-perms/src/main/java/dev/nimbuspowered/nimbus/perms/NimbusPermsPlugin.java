package dev.nimbuspowered.nimbus.perms;

import dev.nimbuspowered.nimbus.perms.display.ChatRenderer;
import dev.nimbuspowered.nimbus.perms.display.NameTagHandler;
import dev.nimbuspowered.nimbus.perms.provider.BuiltinProvider;
import dev.nimbuspowered.nimbus.perms.provider.LuckPermsProvider;
import dev.nimbuspowered.nimbus.perms.provider.PermissionProvider;
import dev.nimbuspowered.nimbus.sdk.Nimbus;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * NimbusPerms — Permission bridge plugin for Nimbus cloud networks.
 * Supports built-in permissions (via Nimbus API) or LuckPerms as provider.
 */
public class NimbusPermsPlugin extends JavaPlugin implements Listener {

    private PermissionProvider provider;
    private ChatRenderer chatRenderer;
    private NameTagHandler nameTagHandler;
    private String apiUrl;
    private String token;

    @Override
    public void onEnable() {
        if (!Nimbus.isManaged()) {
            getLogger().warning("Not running in a Nimbus-managed service — NimbusPerms disabled");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        apiUrl = System.getProperty("nimbus.api.url");
        // Token: prefer env var (hidden from ps), fall back to system property for backwards compat
        String envToken = System.getenv("NIMBUS_API_TOKEN");
        token = (envToken != null && !envToken.isEmpty())
                ? envToken
                : System.getProperty("nimbus.api.token", "");
        if (apiUrl == null || apiUrl.isEmpty()) {
            getLogger().warning("No API URL configured — NimbusPerms disabled");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (apiUrl.endsWith("/")) {
            apiUrl = apiUrl.substring(0, apiUrl.length() - 1);
        }

        // Load config
        saveDefaultConfig();
        String providerName = getConfig().getString("provider", "builtin").toLowerCase();

        // Select provider
        if ("luckperms".equals(providerName) && getServer().getPluginManager().getPlugin("LuckPerms") != null) {
            provider = new LuckPermsProvider();
            getLogger().info("Using LuckPerms permission provider");
        } else {
            if ("luckperms".equals(providerName)) {
                getLogger().warning("LuckPerms provider requested but LuckPerms not found — falling back to builtin");
            }
            provider = new BuiltinProvider();
            getLogger().info("Using built-in permission provider");
        }

        provider.enable(this);

        // Start display handlers
        chatRenderer = new ChatRenderer(this, apiUrl, token, provider);
        chatRenderer.start();

        nameTagHandler = new NameTagHandler(this, provider);
        nameTagHandler.start();

        // Refresh nametags when display data finishes loading from API
        provider.setDisplayLoadedCallback(nameTagHandler::refresh);

        // Register events
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("NimbusPerms enabled (provider: " + providerName + ")");
    }

    @Override
    public void onDisable() {
        if (provider != null) {
            provider.disable();
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (provider != null) {
            provider.onJoin(player);
        }
        if (nameTagHandler != null) {
            nameTagHandler.onJoin(player);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (provider != null) {
            provider.onQuit(player);
        }
        if (nameTagHandler != null) {
            nameTagHandler.onQuit(player);
        }
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public String getToken() {
        return token;
    }

    public PermissionProvider getProvider() {
        return provider;
    }
}
