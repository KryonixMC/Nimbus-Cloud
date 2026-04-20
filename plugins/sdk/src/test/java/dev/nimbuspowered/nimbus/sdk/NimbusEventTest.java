package dev.nimbuspowered.nimbus.sdk;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NimbusEventTest {

    @Test
    void accessorsReturnFieldsAndDataIsImmutable() {
        Map<String, String> data = Map.of("service", "Lobby-1", "group", "Lobby");
        NimbusEvent ev = new NimbusEvent("SERVICE_READY", "2026-04-19T10:00:00Z", data);
        assertEquals("SERVICE_READY", ev.getType());
        assertEquals("2026-04-19T10:00:00Z", ev.getTimestamp());
        assertEquals("Lobby-1", ev.getServiceName());
        assertEquals("Lobby", ev.getGroupName());
        assertEquals("Lobby-1", ev.get("service"));
        assertNull(ev.get("missing"));
        assertThrows(UnsupportedOperationException.class, () -> ev.getData().put("x", "y"));
    }

    @Test
    void nullDataBecomesEmptyMap() {
        NimbusEvent ev = new NimbusEvent("T", "", null);
        assertNotNull(ev.getData());
        assertTrue(ev.getData().isEmpty());
        assertNull(ev.get("x"));
    }

    @Test
    void toStringContainsTypeAndData() {
        NimbusEvent ev = new NimbusEvent("SCALE_UP", "t", Map.of("group", "Lobby"));
        String s = ev.toString();
        assertTrue(s.contains("SCALE_UP"));
        assertTrue(s.contains("Lobby"));
    }
}
