package dev.nimbuspowered.nimbus.sdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NimbusServiceTest {

    private NimbusService svc(String state, String customState, boolean healthy, int players) {
        return new NimbusService("Lobby-1", "Lobby", 30000, state, customState,
                12345L, players, "2026-01-01T00:00:00Z", 0, "0s",
                20.0, 100, 512, healthy);
    }

    @Test
    void routableRequiresReadyNoCustomStateAndHealthy() {
        assertTrue(svc("READY", null, true, 0).isRoutable());
        assertFalse(svc("READY", "WAITING", true, 0).isRoutable());
        assertFalse(svc("READY", null, false, 0).isRoutable());
        assertFalse(svc("STARTING", null, true, 0).isRoutable());
    }

    @Test
    void isReadyIgnoresCustomStateAndHealth() {
        assertTrue(svc("READY", "INGAME", false, 5).isReady());
        assertFalse(svc("STARTING", null, true, 0).isReady());
    }

    @Test
    void gettersExposeAllFields() {
        NimbusService s = svc("READY", "WAITING", true, 7);
        assertEquals("Lobby-1", s.getName());
        assertEquals("Lobby", s.getGroupName());
        assertEquals(30000, s.getPort());
        assertEquals("READY", s.getState());
        assertEquals("WAITING", s.getCustomState());
        assertEquals(12345L, s.getPid());
        assertEquals(7, s.getPlayerCount());
        assertEquals(0, s.getRestartCount());
        assertEquals(20.0, s.getTps());
        assertEquals(100, s.getMemoryUsedMb());
        assertEquals(512, s.getMemoryMaxMb());
        assertTrue(s.isHealthy());
        assertNotNull(s.getStartedAt());
        assertNotNull(s.getUptime());
    }

    @Test
    void legacyConstructorDefaultsTpsAndMemory() {
        NimbusService s = new NimbusService("X", "G", 25565, "READY", null,
                1L, 0, "t", 0, "0s");
        assertEquals(20.0, s.getTps());
        assertEquals(0, s.getMemoryUsedMb());
        assertEquals(0, s.getMemoryMaxMb());
        assertTrue(s.isHealthy());
    }

    @Test
    void toStringIncludesKeyFields() {
        String out = svc("READY", null, true, 3).toString();
        assertTrue(out.contains("Lobby-1"));
        assertTrue(out.contains("Lobby"));
        assertTrue(out.contains("READY"));
        assertTrue(out.contains("players=3"));
    }
}
