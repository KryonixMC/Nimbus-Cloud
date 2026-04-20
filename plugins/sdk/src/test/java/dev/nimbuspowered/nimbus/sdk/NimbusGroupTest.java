package dev.nimbuspowered.nimbus.sdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NimbusGroupTest {

    @Test
    void dynamicAndStaticHelpers() {
        NimbusGroup dyn = new NimbusGroup("Lobby", "DYNAMIC", "paper", "1.21.4", "default", 1, 5, 100);
        NimbusGroup stat = new NimbusGroup("Proxy", "STATIC", "velocity", "latest", "vel", 1, 1, 500);
        assertTrue(dyn.isDynamic());
        assertFalse(dyn.isStatic());
        assertTrue(stat.isStatic());
        assertFalse(stat.isDynamic());
    }

    @Test
    void gettersExposeAllFields() {
        NimbusGroup g = new NimbusGroup("BedWars", "DYNAMIC", "purpur", "1.20.4", "bw", 2, 10, 16);
        assertEquals("BedWars", g.getName());
        assertEquals("DYNAMIC", g.getType());
        assertEquals("purpur", g.getSoftware());
        assertEquals("1.20.4", g.getVersion());
        assertEquals("bw", g.getTemplate());
        assertEquals(2, g.getActiveInstances());
        assertEquals(10, g.getMaxInstances());
        assertEquals(16, g.getMaxPlayers());
    }

    @Test
    void toStringIncludesKeyFields() {
        NimbusGroup g = new NimbusGroup("BedWars", "DYNAMIC", "purpur", "1.20.4", "bw", 2, 10, 16);
        String s = g.toString();
        assertTrue(s.contains("BedWars"));
        assertTrue(s.contains("DYNAMIC"));
        assertTrue(s.contains("purpur"));
        assertTrue(s.contains("2/10"));
    }
}
