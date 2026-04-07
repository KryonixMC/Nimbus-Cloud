package dev.nimbuspowered.nimbus.sdk;

import dev.nimbuspowered.nimbus.sdk.compat.SchedulerCompat;
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
 * <p>
 * Permission handling, chat rendering, and name tag management have been moved
 * to the NimbusPerms plugin (nimbus-perms module).
 */
public class NimbusSdkPlugin extends JavaPlugin implements Listener {

    private static NimbusSdkPlugin instance;

    @Override
    public void onEnable() {
        instance = this;

        if (NimbusSelfService.isNimbusManaged()) {
            try {
                Nimbus.init();
                getLogger().info("Nimbus SDK initialized — service: " + Nimbus.name() + " (group: " + Nimbus.group() + ")");

                String apiUrl = System.getProperty("nimbus.api.url");
                if (apiUrl != null && !apiUrl.isEmpty()) {
                    getServer().getPluginManager().registerEvents(this, this);

                    // Report player count every 10 seconds to keep controller's data fresh
                    SchedulerCompat.runTaskTimerAsync(this, this::reportPlayerCount, 200L, 200L);

                    // Tick the TPS tracker every tick for accurate TPS measurement
                    SchedulerCompat.runTaskTimer(this, () -> Nimbus.tpsTracker().onTick(), 1L, 1L);
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
        Nimbus.shutdown();
        instance = null;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        reportPlayerCount();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Schedule with 1 tick delay — the quitting player is still in getOnlinePlayers() during the event
        SchedulerCompat.runTaskLaterAsync(this, this::reportPlayerCount, 1L);
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
