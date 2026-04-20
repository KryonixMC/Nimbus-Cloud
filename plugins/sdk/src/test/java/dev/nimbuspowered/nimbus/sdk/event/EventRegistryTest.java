package dev.nimbuspowered.nimbus.sdk.event;

import dev.nimbuspowered.nimbus.sdk.NimbusEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EventRegistryTest {

    @Test
    void knownTypeRoundTrips() {
        assertEquals("SERVICE_READY", EventRegistry.getType(ServiceReadyEvent.class));
        assertEquals("SERVICE_CUSTOM_STATE_CHANGED", EventRegistry.getType(CustomStateChangedEvent.class));
        assertEquals("SCALE_UP", EventRegistry.getType(ScaleUpEvent.class));
        assertEquals("SCALE_DOWN", EventRegistry.getType(ScaleDownEvent.class));
    }

    @Test
    void createProducesTypedInstanceFromRawEvent() {
        NimbusEvent raw = new NimbusEvent("SERVICE_READY", "t",
                Map.of("service", "Lobby-1", "group", "Lobby"));
        ServiceReadyEvent ev = EventRegistry.create("SERVICE_READY", raw);
        assertNotNull(ev);
        assertEquals("Lobby-1", ev.getServiceName());
        assertEquals("Lobby", ev.getGroupName());
    }

    @Test
    void customStateChangedExposesOldAndNewState() {
        NimbusEvent raw = new NimbusEvent("SERVICE_CUSTOM_STATE_CHANGED", "t",
                Map.of("service", "BedWars-1", "group", "BedWars",
                       "oldState", "WAITING", "newState", "INGAME"));
        CustomStateChangedEvent ev = EventRegistry.create("SERVICE_CUSTOM_STATE_CHANGED", raw);
        assertNotNull(ev);
        assertEquals("BedWars-1", ev.getServiceName());
        assertEquals("WAITING", ev.getOldState());
        assertEquals("INGAME", ev.getNewState());
    }

    @Test
    void unknownTypeReturnsNull() {
        NimbusEvent raw = new NimbusEvent("NOPE", "t", Map.of());
        assertNull(EventRegistry.create("NOPE", raw));
        assertNull(EventRegistry.getType(TypedEvent.class));
    }
}
