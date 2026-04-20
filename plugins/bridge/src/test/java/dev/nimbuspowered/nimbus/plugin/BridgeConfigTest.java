package dev.nimbuspowered.nimbus.plugin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers the system-property + env-var path of BridgeConfig.load(). The bridge.json/gson
 * branch is left to integration — gson is excluded from the test classpath.
 */
class BridgeConfigTest {

    private String savedUrl;
    private String savedToken;

    @BeforeEach
    void captureProps() {
        savedUrl = System.getProperty("nimbus.api.url");
        savedToken = System.getProperty("nimbus.api.token");
    }

    @AfterEach
    void restoreProps() {
        if (savedUrl == null) System.clearProperty("nimbus.api.url"); else System.setProperty("nimbus.api.url", savedUrl);
        if (savedToken == null) System.clearProperty("nimbus.api.token"); else System.setProperty("nimbus.api.token", savedToken);
    }

    @Test
    void loadReturnsNullWhenNoSystemPropAndNoFile(@TempDir Path dir) throws IOException {
        System.clearProperty("nimbus.api.url");
        BridgeConfig cfg = BridgeConfig.load(dir);
        assertNull(cfg);
    }

    @Test
    void loadFromSystemPropertyWithTokenFallback(@TempDir Path dir) throws IOException {
        System.setProperty("nimbus.api.url", "http://ctrl:9090");
        System.setProperty("nimbus.api.token", "prop-token");
        BridgeConfig cfg = BridgeConfig.load(dir);
        assertEquals("http://ctrl:9090", cfg.getApiUrl());
        // env var takes priority if set; on most test environments NIMBUS_API_TOKEN is unset → prop wins
        String envTok = System.getenv("NIMBUS_API_TOKEN");
        String expected = (envTok != null && !envTok.isEmpty()) ? envTok : "prop-token";
        assertEquals(expected, cfg.getToken());
    }

    @Test
    void loadFromSystemPropertyWithEmptyTokenDefaultsEmpty(@TempDir Path dir) throws IOException {
        System.setProperty("nimbus.api.url", "http://ctrl:9090");
        System.clearProperty("nimbus.api.token");
        BridgeConfig cfg = BridgeConfig.load(dir);
        assertEquals("http://ctrl:9090", cfg.getApiUrl());
        String envTok = System.getenv("NIMBUS_API_TOKEN");
        String expected = (envTok != null && !envTok.isEmpty()) ? envTok : "";
        assertEquals(expected, cfg.getToken());
    }
}
