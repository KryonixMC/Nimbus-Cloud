package dev.nimbuspowered.nimbus.resourcepacks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test: the plugin class loads. arch-lead flagged this plugin as integration-only —
 * pack-stack selection + multi-pack reflection both require a live Paper server context.
 */
class SmokeTest {

    @Test
    void pluginClassLoads() throws ClassNotFoundException {
        Class<?> cls = Class.forName("dev.nimbuspowered.nimbus.resourcepacks.NimbusResourcePacksPlugin");
        assertNotNull(cls);
        assertTrue(org.bukkit.plugin.java.JavaPlugin.class.isAssignableFrom(cls));
    }
}
