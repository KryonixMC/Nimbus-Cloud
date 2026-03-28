package dev.nimbus.sdk;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Nimbus SDK Paper plugin.
 * <p>
 * This plugin does not add any gameplay features — it only provides the
 * {@link NimbusClient}, {@link NimbusSelfService}, and {@link NimbusEventStream}
 * classes to other plugins that depend on it.
 * <p>
 * Other plugins can declare {@code depend: [NimbusSDK]} in their plugin.yml
 * and then use the SDK to communicate with the Nimbus cloud controller.
 */
public class NimbusSdkPlugin extends JavaPlugin {

    private static NimbusSdkPlugin instance;

    @Override
    public void onEnable() {
        instance = this;

        if (NimbusSelfService.isNimbusManaged()) {
            String serviceName = System.getProperty("nimbus.service.name", "unknown");
            String groupName = System.getProperty("nimbus.service.group", "unknown");
            getLogger().info("Nimbus SDK loaded — service: " + serviceName + " (group: " + groupName + ")");
        } else {
            getLogger().info("Nimbus SDK loaded — not running in a Nimbus-managed service");
        }
    }

    @Override
    public void onDisable() {
        instance = null;
    }

    /**
     * Get the plugin instance.
     */
    public static NimbusSdkPlugin getInstance() {
        return instance;
    }
}
