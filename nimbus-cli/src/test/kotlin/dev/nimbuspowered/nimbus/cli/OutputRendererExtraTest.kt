package dev.nimbuspowered.nimbus.cli

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Extra event-type coverage for OutputRenderer.renderEvent — targets the
 * branches that OutputRendererTest didn't already exercise so overall line
 * coverage of this file climbs closer to 100%.
 */
class OutputRendererExtraTest {

    private fun stripAnsi(s: String) = s.replace(Regex("\u001B\\[[0-9;]*m"), "")

    private fun render(type: String, data: Map<String, String> = emptyMap()): String =
        stripAnsi(OutputRenderer.renderEvent(type, data, "2026-01-01T12:34:56Z"))

    @Test
    fun `SERVICE_STOPPED mentions service name`() {
        val r = render("SERVICE_STOPPED", mapOf("service" to "Lobby-1"))
        assertTrue(r.contains("STOPPED"))
        assertTrue(r.contains("Lobby-1"))
    }

    @Test
    fun `SERVICE_RECOVERED mentions service`() {
        val r = render("SERVICE_RECOVERED", mapOf("service" to "L-2"))
        assertTrue(r.contains("RECOVERED"))
        assertTrue(r.contains("L-2"))
    }

    @Test
    fun `SCALE_UP includes group from to and reason`() {
        val r = render("SCALE_UP", mapOf("group" to "Lobby", "from" to "1", "to" to "3", "reason" to "players"))
        assertTrue(r.contains("SCALE UP"))
        assertTrue(r.contains("Lobby"))
        assertTrue(r.contains("1"))
        assertTrue(r.contains("3"))
        assertTrue(r.contains("players"))
    }

    @Test
    fun `SCALE_DOWN renders`() {
        val r = render("SCALE_DOWN", mapOf("service" to "Lobby-3", "reason" to "idle"))
        assertTrue(r.contains("SCALE DOWN"))
        assertTrue(r.contains("Lobby-3"))
        assertTrue(r.contains("idle"))
    }

    @Test
    fun `PLAYER_CONNECTED shows player and service`() {
        val r = render("PLAYER_CONNECTED", mapOf("player" to "Steve", "service" to "Lobby-1"))
        assertTrue(r.contains("Steve"))
        assertTrue(r.contains("Lobby-1"))
    }

    @Test
    fun `PLAYER_DISCONNECTED shows player`() {
        val r = render("PLAYER_DISCONNECTED", mapOf("player" to "Alex"))
        assertTrue(r.contains("Alex"))
    }

    @Test
    fun `NODE_CONNECTED and NODE_DISCONNECTED`() {
        assertTrue(render("NODE_CONNECTED", mapOf("nodeId" to "worker-1")).contains("worker-1"))
        assertTrue(render("NODE_DISCONNECTED", mapOf("nodeId" to "worker-2")).contains("worker-2"))
    }

    @Test
    fun `MAINTENANCE_ENABLED and MAINTENANCE_DISABLED`() {
        val on = render("MAINTENANCE_ENABLED", mapOf("scope" to "Lobby"))
        assertTrue(on.contains("ON"))
        assertTrue(on.contains("Lobby"))
        val off = render("MAINTENANCE_DISABLED", emptyMap())
        assertTrue(off.contains("OFF"))
        assertTrue(off.contains("global"))  // default scope
    }

    @Test
    fun `GROUP_CREATED and GROUP_DELETED`() {
        assertTrue(render("GROUP_CREATED", mapOf("group" to "BedWars")).contains("BedWars"))
        assertTrue(render("GROUP_DELETED", mapOf("group" to "OldGroup")).contains("OldGroup"))
    }

    @Test
    fun `MODULE_LOADED includes module name`() {
        val r = render("MODULE_LOADED", mapOf("moduleName" to "scaling"))
        assertTrue(r.contains("scaling"))
    }

    @Test
    fun `CLI_SESSION_CONNECTED includes user and ip`() {
        val r = render("CLI_SESSION_CONNECTED", mapOf("user" to "admin", "remoteIp" to "10.0.0.1", "sessionId" to "42"))
        assertTrue(r.contains("admin"))
        assertTrue(r.contains("10.0.0.1"))
        assertTrue(r.contains("42"))
    }

    @Test
    fun `CLI_SESSION_DISCONNECTED formats duration under a minute`() {
        val r = render("CLI_SESSION_DISCONNECTED", mapOf(
            "user" to "admin",
            "remoteIp" to "10.0.0.1",
            "durationSeconds" to "42",
            "commandCount" to "3"
        ))
        assertTrue(r.contains("admin"))
        assertTrue(r.contains("42s"))
        assertTrue(r.contains("3 cmds"))
    }

    @Test
    fun `CLI_SESSION_DISCONNECTED formats duration in minutes`() {
        val r = render("CLI_SESSION_DISCONNECTED", mapOf(
            "user" to "u", "remoteIp" to "1.2.3.4",
            "durationSeconds" to "125", "commandCount" to "0"
        ))
        assertTrue(r.contains("2m 5s"))
    }

    @Test
    fun `CLI_SESSION_DISCONNECTED formats duration in hours`() {
        val r = render("CLI_SESSION_DISCONNECTED", mapOf(
            "user" to "u", "remoteIp" to "1.2.3.4",
            "durationSeconds" to "3661", "commandCount" to "0"
        ))
        assertTrue(r.contains("1h 1m"))
    }

    @Test
    fun `CLI_SESSION_DISCONNECTED formats duration in days`() {
        val r = render("CLI_SESSION_DISCONNECTED", mapOf(
            "user" to "u", "remoteIp" to "1.2.3.4",
            "durationSeconds" to "90061", "commandCount" to "0"
        ))
        assertTrue(r.contains("1d 1h"))
    }

    @Test
    fun `CLI_SESSION_DISCONNECTED handles unparseable duration`() {
        val r = render("CLI_SESSION_DISCONNECTED", mapOf(
            "user" to "u", "remoteIp" to "x",
            "durationSeconds" to "NaN", "commandCount" to "0"
        ))
        // fallback is "?"
        assertTrue(r.contains("?"))
    }
}
