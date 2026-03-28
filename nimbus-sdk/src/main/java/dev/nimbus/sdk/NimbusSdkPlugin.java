package dev.nimbus.sdk;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Nimbus SDK Paper plugin.
 * <p>
 * Automatically initializes {@link Nimbus} on startup if running in a
 * Nimbus-managed service. Other plugins just call {@code Nimbus.setState("WAITING")}
 * etc. — no setup required.
 */
public class NimbusSdkPlugin extends JavaPlugin {

    private static NimbusSdkPlugin instance;
    private NimbusPermissionHandler permissionHandler;

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
        if (permissionHandler != null) {
            permissionHandler.shutdown();
        }
        Nimbus.shutdown();
        instance = null;
    }

    public static NimbusSdkPlugin getInstance() {
        return instance;
    }
}
