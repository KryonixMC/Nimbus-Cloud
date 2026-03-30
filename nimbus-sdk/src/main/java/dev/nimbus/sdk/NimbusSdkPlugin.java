package dev.nimbus.sdk;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Nimbus SDK Paper plugin.
 * <p>
 * Automatically initializes {@link Nimbus} on startup if running in a
 * Nimbus-managed service. Other plugins just call {@code Nimbus.setState("WAITING")}
 * etc. — no setup required.
 */
public class NimbusSdkPlugin extends JavaPlugin implements Listener {

    private static NimbusSdkPlugin instance;
    private NimbusPermissionHandler permissionHandler;
    private NimbusChatRenderer chatRenderer;
    private NimbusNameTagHandler nameTagHandler;
    private Object stressBotManager; // StressBotManager — stored as Object to avoid loading ProtocolLib classes early

    @Override
    public void onEnable() {
        instance = this;

        if (NimbusSelfService.isNimbusManaged()) {
            try {
                Nimbus.init();
                getLogger().info("Nimbus SDK initialized — service: " + Nimbus.name() + " (group: " + Nimbus.group() + ")");

                // Start permission handler
                String apiUrl = System.getProperty("nimbus.api.url");
                String token = System.getProperty("nimbus.api.token", "");
                if (apiUrl != null && !apiUrl.isEmpty()) {
                    permissionHandler = new NimbusPermissionHandler(this, apiUrl, token);
                    permissionHandler.start();

                    // Start chat renderer
                    chatRenderer = new NimbusChatRenderer(this, apiUrl, token);
                    chatRenderer.start();

                    // Start name tag handler
                    nameTagHandler = new NimbusNameTagHandler(this, apiUrl, token);
                    nameTagHandler.start();

                    // Start stress bot manager (spawns fake players during stress tests)
                    // Only initialize if ProtocolLib is present — use reflection to avoid
                    // loading ProtocolLib classes when it's not installed
                    if (getServer().getPluginManager().getPlugin("ProtocolLib") != null) {
                        try {
                            // Use our own ClassLoader to avoid Paper's reflection rewriter
                            // intercepting and trying to remap ProtocolLib classes
                            Class<?> sbmClass = getClassLoader().loadClass("dev.nimbus.sdk.StressBotManager");
                            Object sbm = sbmClass.getConstructor(JavaPlugin.class).newInstance(this);
                            sbmClass.getMethod("start").invoke(sbm);
                            stressBotManager = sbm;
                        } catch (Exception e) {
                            getLogger().warning("Failed to initialize StressBotManager: " + e.getMessage());
                        }
                    } else {
                        getLogger().info("ProtocolLib not found — stress test bots disabled");
                    }

                    getServer().getPluginManager().registerEvents(this, this);

                    // Report player count every 10 seconds to keep controller's data fresh
                    getServer().getScheduler().runTaskTimerAsynchronously(this, this::reportPlayerCount, 200L, 200L);
                }
            } catch (Exception e) {
                getLogger().warning("Failed to initialize Nimbus SDK: " + e.getMessage());
            }
        } else {
            getLogger().info("Nimbus SDK loaded — not running in a Nimbus-managed service");
        }
    }

    @Override
    public void onDisable() {
        if (stressBotManager != null) {
            try { stressBotManager.getClass().getMethod("shutdown").invoke(stressBotManager); } catch (Exception ignored) {}
        }
        if (permissionHandler != null) {
            permissionHandler.shutdown();
        }
        Nimbus.shutdown();
        instance = null;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (chatRenderer != null) {
            chatRenderer.fetchPlayerDisplay(event.getPlayer().getUniqueId());
        }
        if (nameTagHandler != null) {
            nameTagHandler.onJoin(event.getPlayer());
        }
        if (stressBotManager != null) {
            try { stressBotManager.getClass().getMethod("onPlayerJoin", org.bukkit.entity.Player.class).invoke(stressBotManager, event.getPlayer()); } catch (Exception ignored) {}
        }
        reportPlayerCount();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (chatRenderer != null) {
            chatRenderer.removePlayer(event.getPlayer().getUniqueId());
        }
        if (nameTagHandler != null) {
            nameTagHandler.onQuit(event.getPlayer());
        }
        // Schedule with 1 tick delay — the quitting player is still in getOnlinePlayers() during the event
        getServer().getScheduler().runTaskLaterAsynchronously(this, this::reportPlayerCount, 1L);
    }

    private void reportPlayerCount() {
        try {
            int count = getServer().getOnlinePlayers().size();
            Nimbus.reportPlayerCount(count);
        } catch (Exception e) {
            getLogger().fine("Failed to report player count: " + e.getMessage());
        }
    }

    public static NimbusSdkPlugin getInstance() {
        return instance;
    }
}
