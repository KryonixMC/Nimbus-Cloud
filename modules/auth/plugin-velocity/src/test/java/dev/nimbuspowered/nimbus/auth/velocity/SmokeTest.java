package dev.nimbuspowered.nimbus.auth.velocity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Smoke test: the plugin class loads. arch-lead flagged this plugin as integration-only —
 * the plugin is ~all Velocity event-subscription glue for magic-link chat delivery, no pure
 * helpers worth extracting at this stage.
 */
class SmokeTest {

    @Test
    void pluginClassLoads() throws ClassNotFoundException {
        Class<?> cls = Class.forName("dev.nimbuspowered.nimbus.auth.velocity.NimbusAuthVelocityPlugin");
        assertNotNull(cls);
    }
}
