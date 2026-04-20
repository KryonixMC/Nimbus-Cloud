package dev.nimbuspowered.nimbus.punishments.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke: plugin entry + listener classes load. Chat-event handling requires a live Bukkit
 * chat event pipeline; that's integration, not unit.
 */
class SmokeTest {

    @Test
    void pluginClassLoads() throws ClassNotFoundException {
        Class<?> cls = Class.forName("dev.nimbuspowered.nimbus.punishments.backend.NimbusPunishmentsBackendPlugin");
        assertNotNull(cls);
        assertTrue(org.bukkit.plugin.java.JavaPlugin.class.isAssignableFrom(cls));
    }

    @Test
    void chatListenerClassLoads() throws ClassNotFoundException {
        Class<?> cls = Class.forName("dev.nimbuspowered.nimbus.punishments.backend.ChatListener");
        assertNotNull(cls);
    }

    @Test
    void muteApiClientClassLoads() throws ClassNotFoundException {
        Class<?> cls = Class.forName("dev.nimbuspowered.nimbus.punishments.backend.MuteApiClient");
        assertNotNull(cls);
    }
}
