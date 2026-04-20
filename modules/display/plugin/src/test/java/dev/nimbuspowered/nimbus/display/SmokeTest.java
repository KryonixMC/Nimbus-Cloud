package dev.nimbuspowered.nimbus.display;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke: the plugin main class loads and its declared structure is sane.
 * Deeper behaviour requires a running Paper server; that belongs in integration tests,
 * not a unit harness (MockBukkit for ~14 files of mostly Bukkit glue = poor ROI).
 */
class SmokeTest {

    @Test
    void pluginClassLoadsWithoutError() throws ClassNotFoundException {
        Class<?> cls = Class.forName("dev.nimbuspowered.nimbus.display.NimbusDisplayPlugin");
        assertNotNull(cls);
        assertTrue(org.bukkit.plugin.java.JavaPlugin.class.isAssignableFrom(cls));
    }

    @Test
    void npcActionEnumLoads() throws ClassNotFoundException {
        Class<?> cls = Class.forName("dev.nimbuspowered.nimbus.display.NpcAction");
        assertTrue(cls.isEnum());
        assertTrue(cls.getEnumConstants().length > 0);
    }
}
