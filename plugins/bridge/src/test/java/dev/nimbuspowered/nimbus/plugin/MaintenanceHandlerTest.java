package dev.nimbuspowered.nimbus.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.helpers.NOPLogger;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MaintenanceHandlerTest {

    private static final Logger SILENT = NOPLogger.NOP_LOGGER;

    private MaintenanceHandler handler;

    @BeforeEach
    void setUp() {
        handler = new MaintenanceHandler(SILENT);
    }

    // ── Global state ────────────────────────────────────────────────

    @Test
    void isGlobalEnabledReturnsFalseByDefault() {
        assertFalse(handler.isGlobalEnabled());
    }

    @Test
    void onMaintenanceEnabledSetsGlobalToTrue() {
        handler.onMaintenanceEnabled(Map.of("scope", "global"));
        assertTrue(handler.isGlobalEnabled());
    }

    @Test
    void onMaintenanceDisabledSetsGlobalBackToFalse() {
        handler.onMaintenanceEnabled(Map.of("scope", "global"));
        assertTrue(handler.isGlobalEnabled());

        handler.onMaintenanceDisabled(Map.of("scope", "global"));
        assertFalse(handler.isGlobalEnabled());
    }

    @Test
    void onMaintenanceEnabledWithNullScopeIsNoOp() {
        // Map.of doesn't allow null values — use a plain HashMap
        java.util.Map<String, String> data = new java.util.HashMap<>();
        data.put("scope", null);
        handler.onMaintenanceEnabled(data);
        assertFalse(handler.isGlobalEnabled(), "null scope must not change global state");
    }

    // ── Group state ─────────────────────────────────────────────────

    @Test
    void isGroupInMaintenanceReturnsFalseByDefault() {
        assertFalse(handler.isGroupInMaintenance("BedWars"));
    }

    @Test
    void groupMaintenanceCanBeEnabledViaEvent() {
        handler.onMaintenanceEnabled(Map.of("scope", "BedWars"));
        assertTrue(handler.isGroupInMaintenance("BedWars"));
    }

    @Test
    void groupMaintenanceCanBeDisabled() {
        handler.onMaintenanceEnabled(Map.of("scope", "SkyWars"));
        assertTrue(handler.isGroupInMaintenance("SkyWars"));

        handler.onMaintenanceDisabled(Map.of("scope", "SkyWars"));
        assertFalse(handler.isGroupInMaintenance("SkyWars"));
    }

    @Test
    void disablingOneGroupDoesNotAffectAnother() {
        handler.onMaintenanceEnabled(Map.of("scope", "BedWars"));
        handler.onMaintenanceEnabled(Map.of("scope", "SkyWars"));

        handler.onMaintenanceDisabled(Map.of("scope", "BedWars"));

        assertFalse(handler.isGroupInMaintenance("BedWars"));
        assertTrue(handler.isGroupInMaintenance("SkyWars"));
    }

    // ── Whitelist ───────────────────────────────────────────────────

    @Test
    void isWhitelistedReturnsTrueForWhitelistedUuid() {
        JsonObject json = new JsonObject();
        json.addProperty("globalEnabled", false);
        JsonArray whitelist = new JsonArray();
        whitelist.add("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        json.add("whitelist", whitelist);

        handler.loadFromProxyConfig(json);

        assertTrue(handler.isWhitelisted("a1b2c3d4-e5f6-7890-abcd-ef1234567890"));
    }

    @Test
    void isWhitelistedReturnsFalseForUnknownUuid() {
        handler.loadFromProxyConfig(new JsonObject());
        assertFalse(handler.isWhitelisted("00000000-0000-0000-0000-000000000000"));
    }

    @Test
    void isWhitelistedIsCaseInsensitive() {
        JsonObject json = new JsonObject();
        JsonArray whitelist = new JsonArray();
        whitelist.add("UPPERCASE-UUID");
        json.add("whitelist", whitelist);

        handler.loadFromProxyConfig(json);

        // isWhitelisted stores in lower-case; query with mixed case must still match
        assertTrue(handler.isWhitelisted("uppercase-uuid"),
                "whitelist lookup must be case-insensitive");
    }

    // ── loadFromProxyConfig ─────────────────────────────────────────

    @Test
    void loadFromProxyConfigParsesGlobalEnabled() {
        JsonObject json = new JsonObject();
        json.addProperty("globalEnabled", true);

        handler.loadFromProxyConfig(json);

        assertTrue(handler.isGlobalEnabled());
    }

    @Test
    void loadFromProxyConfigParsesCustomMotd() {
        JsonObject json = new JsonObject();
        json.addProperty("globalEnabled", false);
        json.addProperty("motdLine1", "Custom line 1");
        json.addProperty("motdLine2", "Custom line 2");

        handler.loadFromProxyConfig(json);

        assertEquals("Custom line 1", handler.getMotdLine1());
        assertEquals("Custom line 2", handler.getMotdLine2());
    }

    @Test
    void loadFromProxyConfigParsesGroupMaintenance() {
        JsonObject groups = new JsonObject();
        groups.addProperty("BedWars", "<red>BedWars maintenance</red>");

        JsonObject json = new JsonObject();
        json.add("groups", groups);

        handler.loadFromProxyConfig(json);

        assertTrue(handler.isGroupInMaintenance("BedWars"));
        assertEquals("<red>BedWars maintenance</red>", handler.getGroupKickMessage("BedWars"));
    }

    @Test
    void loadFromProxyConfigWithNullIsNoOp() {
        // Should not throw
        assertDoesNotThrow(() -> handler.loadFromProxyConfig(null));
        assertFalse(handler.isGlobalEnabled());
    }

    @Test
    void loadFromProxyConfigClearsWhitelistOnReload() {
        // Load with one entry
        JsonObject first = new JsonObject();
        JsonArray wl1 = new JsonArray();
        wl1.add("player-one");
        first.add("whitelist", wl1);
        handler.loadFromProxyConfig(first);
        assertTrue(handler.isWhitelisted("player-one"));

        // Reload with a different entry — player-one must be gone
        JsonObject second = new JsonObject();
        JsonArray wl2 = new JsonArray();
        wl2.add("player-two");
        second.add("whitelist", wl2);
        handler.loadFromProxyConfig(second);

        assertFalse(handler.isWhitelisted("player-one"),
                "old whitelist entry must be cleared on reload");
        assertTrue(handler.isWhitelisted("player-two"));
    }
}
