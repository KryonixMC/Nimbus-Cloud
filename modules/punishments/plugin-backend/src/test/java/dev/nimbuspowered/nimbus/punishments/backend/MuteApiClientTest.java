package dev.nimbuspowered.nimbus.punishments.backend;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests: constructor, invalidate(), and that checkMute() never throws on
 * a bad URL (returns null instead — defensive for offline controllers).
 */
class MuteApiClientTest {

    private final Logger logger = Logger.getLogger("test");

    @Test
    void constructorAcceptsAllNullableFields() {
        MuteApiClient c = new MuteApiClient("http://127.0.0.1:9999", null, null, null, logger);
        assertNotNull(c);
    }

    @Test
    void invalidateIsNoopForUnknownUuid() {
        MuteApiClient c = new MuteApiClient("http://127.0.0.1:9999", "tok", "Lobby-1", "Lobby", logger);
        c.invalidate(UUID.randomUUID()); // does not throw
    }

    @Test
    void checkMuteReturnsNullOnUnreachableController() {
        // Unroutable TEST-NET address → connect fails fast; client must swallow + return null.
        MuteApiClient c = new MuteApiClient("http://192.0.2.1:1", "", "", "", logger);
        assertNull(c.checkMute(UUID.randomUUID()));
    }

    @Test
    void repeatedChecksDoNotThrow() {
        MuteApiClient c = new MuteApiClient("http://192.0.2.1:1", "", "Lobby-1", "Lobby", logger);
        UUID id = UUID.randomUUID();
        assertNull(c.checkMute(id));
        assertNull(c.checkMute(id));
        c.invalidate(id);
        assertNull(c.checkMute(id));
    }

    @Test
    void groupAndServiceQueryParamsAreOptionalInAnyCombination() {
        // Empty strings, nulls, and populated values all work — no NPE from URL building.
        new MuteApiClient("http://192.0.2.1:1", "", "", "", logger).checkMute(UUID.randomUUID());
        new MuteApiClient("http://192.0.2.1:1", "", null, null, logger).checkMute(UUID.randomUUID());
        new MuteApiClient("http://192.0.2.1:1", "", "svc", "", logger).checkMute(UUID.randomUUID());
        new MuteApiClient("http://192.0.2.1:1", "", "", "grp", logger).checkMute(UUID.randomUUID());
    }
}
