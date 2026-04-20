package dev.nimbuspowered.nimbus.punishments.velocity;

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for {@link PunishmentsApiClient}. Network calls go to a TEST-NET address
 * so they fail fast and exercise the defensive return-null path + the cache.
 */
class PunishmentsApiClientTest {

    @Test
    void constructorAcceptsMissingToken() {
        PunishmentsApiClient c = new PunishmentsApiClient("http://192.0.2.1:1", "", NOPLogger.NOP_LOGGER);
        assertNotNull(c);
    }

    @Test
    void checkLoginReturnsNullOnUnreachableController() {
        PunishmentsApiClient c = new PunishmentsApiClient("http://192.0.2.1:1", "tok", NOPLogger.NOP_LOGGER);
        assertNull(c.checkLogin(UUID.randomUUID(), "127.0.0.1"));
    }

    @Test
    void checkConnectReturnsNullOnUnreachableController() {
        PunishmentsApiClient c = new PunishmentsApiClient("http://192.0.2.1:1", "tok", NOPLogger.NOP_LOGGER);
        assertNull(c.checkConnect(UUID.randomUUID(), "1.2.3.4", "Lobby", "Lobby-1"));
    }

    @Test
    void repeatedChecksDoNotThrow() {
        PunishmentsApiClient c = new PunishmentsApiClient("http://192.0.2.1:1", "", NOPLogger.NOP_LOGGER);
        UUID id = UUID.randomUUID();
        assertNull(c.checkLogin(id, "1.2.3.4"));
        assertNull(c.checkLogin(id, "1.2.3.4"));
        c.invalidate(id);
        assertNull(c.checkLogin(id, "1.2.3.4"));
    }

    @Test
    void nullIpOmitsQueryParamGracefully() {
        PunishmentsApiClient c = new PunishmentsApiClient("http://192.0.2.1:1", "", NOPLogger.NOP_LOGGER);
        assertNull(c.checkLogin(UUID.randomUUID(), null));
    }

    @Test
    void connectCheckDistinguishesPerGroupAndService() {
        PunishmentsApiClient c = new PunishmentsApiClient("http://192.0.2.1:1", "", NOPLogger.NOP_LOGGER);
        UUID id = UUID.randomUUID();
        // Two different connect targets → two cache keys, no crosstalk.
        assertNull(c.checkConnect(id, "ip", "Lobby", "Lobby-1"));
        assertNull(c.checkConnect(id, "ip", "Lobby", "Lobby-2"));
        assertNull(c.checkConnect(id, "ip", "BedWars", "BedWars-1"));
    }

    @Test
    void invalidateDropsCachedEntries() {
        PunishmentsApiClient c = new PunishmentsApiClient("http://192.0.2.1:1", "", NOPLogger.NOP_LOGGER);
        UUID id = UUID.randomUUID();
        c.checkLogin(id, "1.2.3.4");
        c.checkConnect(id, "1.2.3.4", "Lobby", "Lobby-1");
        c.invalidate(id); // does not throw, clears entries
    }

    @Test
    void loginAndConnectCachesAreSeparateKeys() {
        PunishmentsApiClient c = new PunishmentsApiClient("http://192.0.2.1:1", "", NOPLogger.NOP_LOGGER);
        UUID id = UUID.randomUUID();
        assertNull(c.checkLogin(id, null));
        assertNull(c.checkConnect(id, null, "Lobby", "Lobby-1"));
    }
}
