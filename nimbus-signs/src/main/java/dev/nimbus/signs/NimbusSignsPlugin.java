package dev.nimbus.signs;

import dev.nimbus.sdk.Nimbus;
import org.bukkit.plugin.java.JavaPlugin;

public class NimbusSignsPlugin extends JavaPlugin {

    private SignManager signManager;

    @Override
    public void onEnable() {
        if (!Nimbus.isManaged()) {
            getLogger().warning("Not running in a Nimbus-managed service — signs will not work!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();

        SignConfig signConfig = new SignConfig(this);
        signManager = new SignManager(this, signConfig);
        signManager.load();

        getServer().getPluginManager().registerEvents(new SignListener(signManager), this);

        var cmd = getCommand("nsign");
        if (cmd != null) {
            SignCommand signCommand = new SignCommand(signManager);
            cmd.setExecutor(signCommand);
            cmd.setTabCompleter(signCommand);
        }

        // Sync update loop — updateAll() directly modifies sign block states
        int interval = signConfig.getUpdateInterval();
        getServer().getScheduler().runTaskTimer(this, signManager::updateAll, interval, interval);

        // Periodic display + group cache refresh (every 5 minutes)
        long refreshInterval = 20L * 60 * 5;
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            signManager.refreshDisplays();
            signManager.refreshGroups();
        }, refreshInterval, refreshInterval);

        getLogger().info("Nimbus Signs loaded — " + signManager.getSignCount() + " sign(s)");
    }
}
