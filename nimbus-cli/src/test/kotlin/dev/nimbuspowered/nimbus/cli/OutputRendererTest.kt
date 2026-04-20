package dev.nimbuspowered.nimbus.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutputRendererTest {

    private fun stripAnsi(s: String) = s.replace(Regex("\u001B\\[[0-9;]*m"), "")

    @Test
    fun `info wraps in cyan and resets`() {
        val out = OutputRenderer.render("info", "hello")
        assertTrue(out.startsWith("\u001B[36m"))
        assertTrue(out.endsWith("\u001B[0m"))
        assertEquals("hello", stripAnsi(out))
    }

    @Test
    fun `success renders green`() {
        assertTrue(OutputRenderer.render("success", "ok").startsWith("\u001B[32m"))
    }

    @Test
    fun `error renders red`() {
        assertTrue(OutputRenderer.render("error", "bad").startsWith("\u001B[31m"))
    }

    @Test
    fun `item is indented two spaces`() {
        assertEquals("  line", OutputRenderer.render("item", "line"))
    }

    @Test
    fun `text passes through unchanged`() {
        assertEquals("raw", OutputRenderer.render("text", "raw"))
    }

    @Test
    fun `unknown type falls through`() {
        assertEquals("stuff", OutputRenderer.render("weird", "stuff"))
    }

    @Test
    fun `header contains title and padding`() {
        val rendered = stripAnsi(OutputRenderer.render("header", "Services"))
        assertTrue(rendered.contains("Services"))
        assertTrue(rendered.startsWith("── "))
    }

    @Test
    fun `renderEvent extracts HHmmss from ISO timestamp`() {
        val rendered = stripAnsi(
            OutputRenderer.renderEvent(
                "SERVICE_READY",
                mapOf("service" to "Lobby-1"),
                "2026-04-19T14:05:33Z"
            )
        )
        assertTrue(rendered.startsWith("[14:05:33]"), "rendered=$rendered")
        assertTrue(rendered.contains("READY"))
        assertTrue(rendered.contains("Lobby-1"))
    }

    @Test
    fun `renderEvent SERVICE_STARTING includes port`() {
        val rendered = stripAnsi(
            OutputRenderer.renderEvent(
                "SERVICE_STARTING",
                mapOf("service" to "Lobby-1", "port" to "30000"),
                "2026-01-01T00:00:00Z"
            )
        )
        assertTrue(rendered.contains("STARTING"))
        assertTrue(rendered.contains("port=30000"))
    }

    @Test
    fun `renderEvent SERVICE_CRASHED includes exit and attempt`() {
        val rendered = stripAnsi(
            OutputRenderer.renderEvent(
                "SERVICE_CRASHED",
                mapOf("service" to "Lobby-1", "exitCode" to "137", "restartAttempt" to "2"),
                "2026-01-01T00:00:00Z"
            )
        )
        assertTrue(rendered.contains("CRASHED"))
        assertTrue(rendered.contains("exit=137"))
        assertTrue(rendered.contains("attempt=2"))
    }

    @Test
    fun `renderEvent falls back to generic details for unknown type`() {
        val rendered = stripAnsi(
            OutputRenderer.renderEvent(
                "WEIRD_EVENT",
                mapOf("a" to "1", "b" to "2"),
                "2026-01-01T00:00:00Z"
            )
        )
        assertTrue(rendered.contains("WEIRD_EVENT"))
        assertTrue(rendered.contains("a=1"))
        assertTrue(rendered.contains("b=2"))
    }

    @Test
    fun `renderEvent with missing data uses question-mark placeholders`() {
        val rendered = stripAnsi(
            OutputRenderer.renderEvent("SERVICE_READY", emptyMap(), "2026-01-01T00:00:00Z")
        )
        assertTrue(rendered.contains("READY"))
        assertTrue(rendered.contains("?"))
    }

    @Test
    fun `renderEvent timestamp without T falls back gracefully`() {
        // substringAfter with no match returns the whole string; verify no crash.
        val rendered = OutputRenderer.renderEvent(
            "SERVICE_READY",
            mapOf("service" to "X"),
            "no-t-in-here"
        )
        assertFalse(rendered.isEmpty())
    }
}
