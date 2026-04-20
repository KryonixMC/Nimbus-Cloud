package dev.nimbuspowered.nimbus.perms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke: main plugin class + provider SPI load. Deeper behaviour (injector, chat handler,
 * packet name tags) requires a live Bukkit context and belongs in integration.
 */
class SmokeTest {

    @Test
    void pluginClassLoadsWithoutError() throws ClassNotFoundException {
        Class<?> cls = Class.forName("dev.nimbuspowered.nimbus.perms.NimbusPermsPlugin");
        assertNotNull(cls);
        assertTrue(org.bukkit.plugin.java.JavaPlugin.class.isAssignableFrom(cls));
    }

    @Test
    void permissionProviderInterfaceLoads() throws ClassNotFoundException {
        Class<?> cls = Class.forName("dev.nimbuspowered.nimbus.perms.provider.PermissionProvider");
        assertTrue(cls.isInterface());
    }

    @Test
    void builtinProviderIsConcrete() throws ClassNotFoundException {
        Class<?> cls = Class.forName("dev.nimbuspowered.nimbus.perms.provider.BuiltinProvider");
        assertTrue(!cls.isInterface());
    }
}
