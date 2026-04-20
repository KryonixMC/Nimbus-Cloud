package dev.nimbuspowered.nimbus.cli

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliConfigTest {

    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `default config has a default profile`() {
        val cfg = CliConfig()
        assertEquals("default", cfg.defaultProfile)
        assertTrue(cfg.profiles.containsKey("default"))
        val p = cfg.profiles["default"]!!
        assertEquals("127.0.0.1", p.host)
        assertEquals(8080, p.port)
        assertEquals("", p.token)
    }

    @Test
    fun `config roundtrips via json with multiple profiles`() {
        val cfg = CliConfig(
            defaultProfile = "prod",
            profiles = mapOf(
                "prod" to ConnectionProfile(host = "prod.example", port = 443, token = "abc"),
                "dev" to ConnectionProfile(host = "127.0.0.1", port = 8080, token = "dev-tok")
            )
        )
        val encoded = json.encodeToString(cfg)
        val decoded = json.decodeFromString<CliConfig>(encoded)
        assertEquals(cfg, decoded)
    }

    @Test
    fun `unknown json keys are ignored`() {
        val raw = """{"defaultProfile":"default","profiles":{},"futureField":123}"""
        val decoded = json.decodeFromString<CliConfig>(raw)
        assertEquals("default", decoded.defaultProfile)
        assertTrue(decoded.profiles.isEmpty())
    }
}
