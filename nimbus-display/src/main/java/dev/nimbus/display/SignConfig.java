package dev.nimbus.display;

import dev.nimbus.sdk.RoutingStrategy;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Loads and saves sign definitions from config.yml.
 * Only stores target, strategy, and location — lines come from display config.
 */
public class SignConfig {

    private final JavaPlugin plugin;
    private final Logger logger;

    public SignConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public int getUpdateInterval() {
        return plugin.getConfig().getInt("update-interval", 40);
    }

    public List<NimbusSign> loadSigns() {
        List<NimbusSign> signs = new ArrayList<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("signs");
        if (section == null) return signs;

        for (String key : section.getKeys(false)) {
            ConfigurationSection signSection = section.getConfigurationSection(key);
            if (signSection == null) continue;

            try {
                NimbusSign sign = parseSign(key, signSection);
                if (sign != null) {
                    signs.add(sign);
                }
            } catch (Exception e) {
                logger.warning("Failed to load sign '" + key + "': " + e.getMessage());
            }
        }

        return signs;
    }

    private NimbusSign parseSign(String id, ConfigurationSection section) {
        String target = section.getString("target");
        if (target == null || target.isBlank()) {
            logger.warning("Sign '" + id + "' has no target defined");
            return null;
        }

        ConfigurationSection locSection = section.getConfigurationSection("location");
        if (locSection == null) {
            logger.warning("Sign '" + id + "' has no location defined");
            return null;
        }

        String worldName = locSection.getString("world", "world");
        var world = plugin.getServer().getWorld(worldName);
        if (world == null) {
            logger.warning("Sign '" + id + "': world '" + worldName + "' not loaded");
            return null;
        }

        Location location = new Location(world,
                locSection.getInt("x"), locSection.getInt("y"), locSection.getInt("z"));

        String strategyStr = section.getString("strategy", "least");
        RoutingStrategy strategy = switch (strategyStr.toLowerCase()) {
            case "fill", "fill_first" -> RoutingStrategy.FILL_FIRST;
            case "random" -> RoutingStrategy.RANDOM;
            default -> RoutingStrategy.LEAST_PLAYERS;
        };

        boolean isService = target.matches(".*-\\d+$");

        return new NimbusSign(id, location, target, isService, strategy);
    }

    public void addSign(NimbusSign sign) {
        String path = "signs." + sign.id();
        plugin.getConfig().set(path + ".target", sign.target());
        if (!sign.serviceTarget()) {
            plugin.getConfig().set(path + ".strategy", sign.strategy().name().toLowerCase());
        }
        plugin.getConfig().set(path + ".location.world", sign.location().getWorld().getName());
        plugin.getConfig().set(path + ".location.x", sign.location().getBlockX());
        plugin.getConfig().set(path + ".location.y", sign.location().getBlockY());
        plugin.getConfig().set(path + ".location.z", sign.location().getBlockZ());
        plugin.saveConfig();
    }

    public void removeSign(String id) {
        plugin.getConfig().set("signs." + id, null);
        plugin.saveConfig();
    }
}
