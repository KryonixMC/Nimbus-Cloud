package dev.nimbuspowered.nimbus.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AgentConfigLoaderTest {

    @Test
    fun `load parses valid toml`(@TempDir dir: Path) {
        val path = dir.resolve("agent.toml")
        Files.writeString(path, """
            [agent]
            controller = "wss://ctrl.test:9443/cluster"
            token = "secret"
            node_name = "worker-42"
            max_memory = "16G"
            max_services = 20
            trusted_fingerprint = "AA:BB"
            tls_verify = false
            truststore_path = "/etc/ca.jks"
            truststore_password = "pw"
            public_host = "10.0.0.5"

            [java]
            java_16 = "/opt/j16"
            java_17 = "/opt/j17"
            java_21 = "/opt/j21"
        """.trimIndent())

        val cfg = AgentConfigLoader.load(path)

        assertEquals("wss://ctrl.test:9443/cluster", cfg.agent.controller)
        assertEquals("secret", cfg.agent.token)
        assertEquals("worker-42", cfg.agent.nodeName)
        assertEquals("16G", cfg.agent.maxMemory)
        assertEquals(20, cfg.agent.maxServices)
        assertEquals("AA:BB", cfg.agent.trustedFingerprint)
        assertFalse(cfg.agent.tlsVerify)
        assertEquals("/etc/ca.jks", cfg.agent.truststorePath)
        assertEquals("pw", cfg.agent.truststorePassword)
        assertEquals("10.0.0.5", cfg.agent.publicHost)
        assertEquals("/opt/j17", cfg.java.java17)
    }

    @Test
    fun `javaDefinition toMap filters blanks`() {
        val java = JavaDefinition(java17 = "/opt/j17", java21 = "")
        val map = java.toMap()
        assertTrue(map.containsKey(17))
        assertFalse(map.containsKey(21))
        assertFalse(map.containsKey(16))
    }

    @Test
    fun `applyEnvironmentOverrides returns same instance when no env set`() {
        // Preserve original env; best-effort: if env vars are set externally, skip.
        val controller = System.getenv("NIMBUS_AGENT_CONTROLLER")
        val token = System.getenv("NIMBUS_AGENT_TOKEN")
        if (!controller.isNullOrBlank() || !token.isNullOrBlank()) return

        val cfg = AgentConfig()
        val out = AgentConfigLoader.applyEnvironmentOverrides(cfg)
        assertSame(cfg, out)
    }

    @Test
    fun `save then load roundtrips`(@TempDir dir: Path) {
        val path = dir.resolve("agent.toml")
        val cfg = AgentConfig(
            agent = AgentDefinition(
                controller = "wss://x.y.z:443/cluster",
                token = "tok",
                nodeName = "n1",
                maxMemory = "4G",
                maxServices = 3,
                trustedFingerprint = "DE:AD:BE:EF",
                tlsVerify = true,
                truststorePath = "",
                truststorePassword = "",
                publicHost = "1.2.3.4"
            ),
            java = JavaDefinition(java21 = "/java21/bin/java")
        )
        AgentConfigLoader.save(path, cfg)
        val reloaded = AgentConfigLoader.load(path)
        assertEquals(cfg.agent.controller, reloaded.agent.controller)
        assertEquals(cfg.agent.token, reloaded.agent.token)
        assertEquals(cfg.agent.nodeName, reloaded.agent.nodeName)
        assertEquals(cfg.agent.maxMemory, reloaded.agent.maxMemory)
        assertEquals(cfg.agent.maxServices, reloaded.agent.maxServices)
        assertEquals(cfg.agent.trustedFingerprint, reloaded.agent.trustedFingerprint)
        assertEquals(cfg.agent.tlsVerify, reloaded.agent.tlsVerify)
        assertEquals(cfg.agent.publicHost, reloaded.agent.publicHost)
        assertEquals(cfg.java.java21, reloaded.java.java21)
    }

    @Test
    fun `load ignores unknown keys`(@TempDir dir: Path) {
        val path = dir.resolve("agent.toml")
        Files.writeString(path, """
            [agent]
            controller = "wss://x"
            token = ""
            node_name = "n1"
            max_memory = "1G"
            max_services = 1
            trusted_fingerprint = ""
            tls_verify = true
            truststore_path = ""
            truststore_password = ""
            public_host = ""
            future_field = "ignored"

            [java]
            java_16 = ""
            java_17 = ""
            java_21 = ""
        """.trimIndent())
        val cfg = AgentConfigLoader.load(path)
        assertEquals("n1", cfg.agent.nodeName)
        // Verify we didn't change identity after no-op env override
        val out = AgentConfigLoader.applyEnvironmentOverrides(cfg)
        // identity comparison only valid when env is unset
        val controller = System.getenv("NIMBUS_AGENT_CONTROLLER")
        val token = System.getenv("NIMBUS_AGENT_TOKEN")
        if (controller.isNullOrBlank() && token.isNullOrBlank()) {
            assertSame(cfg, out)
        } else {
            assertNotSame(cfg, out)
        }
    }
}
