package dev.kryonix.nimbus.display;

import dev.kryonix.nimbus.sdk.Nimbus;
import dev.kryonix.nimbus.sdk.NimbusDisplay;
import dev.kryonix.nimbus.sdk.NimbusGroup;
import dev.kryonix.nimbus.sdk.compat.SchedulerCompat;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ConcurrentHashMap;

public class NimbusDisplayPlugin extends JavaPlugin {

    private SignManager signManager;
    private NpcManager npcManager;

    // Shared caches — used by both sign and NPC managers
    private final ConcurrentHashMap<String, NimbusDisplay> displayCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NimbusGroup> groupCache = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        if (!Nimbus.isManaged()) {
            getLogger().warning("Not running in a Nimbus-managed service — display plugin will not work!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();

        // Signs
        SignConfig signConfig = new SignConfig(this);
        signManager = new SignManager(this, signConfig, displayCache, groupCache);
        signManager.load();

        // NPCs
        NpcConfig npcConfig = new NpcConfig(this);
        npcManager = new NpcManager(this, npcConfig, displayCache, groupCache);
        npcManager.load();

        // Listeners
        getServer().getPluginManager().registerEvents(new SignListener(signManager), this);
        getServer().getPluginManager().registerEvents(
                new NpcListener(this, npcManager, displayCache, groupCache), this);
        getServer().getPluginManager().registerEvents(new NpcInventory.ClickListener(), this);

        // Unified command
        var cmd = getCommand("ndisplay");
        if (cmd != null) {
            DisplayCommand displayCommand = new DisplayCommand(signManager, npcManager);
            cmd.setExecutor(displayCommand);
            cmd.setTabCompleter(displayCommand);
        }

        // Sign + hologram text update (every 2 seconds by default)
        int interval = signConfig.getUpdateInterval();
        SchedulerCompat.runTaskTimer(this, () -> {
            signManager.updateAll();
            npcManager.updateAll();
        }, interval, interval);

        // Periodic display + group cache refresh (every 5 minutes)
        long refreshInterval = 20L * 60 * 5;
        SchedulerCompat.runTaskTimerAsync(this, () -> {
            signManager.refreshDisplays();
            signManager.refreshGroups();
        }, refreshInterval, refreshInterval);

        getLogger().info("Nimbus Display loaded — " + signManager.getSignCount() + " sign(s), "
                + npcManager.getNpcCount() + " NPC(s)");
    }

    @Override
    public void onDisable() {
        if (npcManager != null) {
            npcManager.despawnAll();
        }
    }
}
