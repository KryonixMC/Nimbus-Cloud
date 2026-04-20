package dev.nimbuspowered.nimbus.cli

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests StreamClient without actually opening a WebSocket — the receive
 * loop is socket-heavy and not practical to mock without rewriting the
 * client. Instead we drive `handleMessage` directly via reflection and
 * verify state-machine transitions (pending command channels, screen
 * flags, completion deferreds).
 */
class StreamClientTest {

    private val clients = mutableListOf<HttpClient>()

    @AfterEach
    fun cleanup() {
        clients.forEach { it.close() }
        clients.clear()
    }

    private fun newClient(
        onEvent: (String) -> Unit = {},
        onScreenLine: (String) -> Unit = {}
    ): StreamClient {
        val http = HttpClient(MockEngine { error("not reachable in unit test") }) {
            install(WebSockets)
        }
        clients += http
        return StreamClient(http, "127.0.0.1", 8080, "tok", onEvent, onScreenLine)
    }

    private fun StreamClient.handleMessageRaw(text: String) {
        val m = StreamClient::class.java.getDeclaredMethod("handleMessage", String::class.java)
        m.isAccessible = true
        m.invoke(this, text)
    }

    @Serializable
    private data class Outbound(
        val type: String,
        val id: String = "",
        val line: OutputLine? = null,
        val text: String = "",
        val candidates: List<String> = emptyList(),
        val event: EventPayload? = null
    )

    @Serializable
    private data class OutputLine(val type: String, val text: String)

    @Serializable
    private data class EventPayload(
        val type: String = "",
        val timestamp: String = "",
        val data: Map<String, String> = emptyMap()
    )

    private val json = Json { encodeDefaults = true }

    @Test
    fun `handleMessage ignores malformed json silently`() {
        val c = newClient()
        c.handleMessageRaw("{ not json")
        assertFalse(c.inScreenSession)
        assertFalse(c.isConnected)
    }

    @Test
    fun `handleMessage screen_attached sets inScreenSession`() {
        val c = newClient()
        c.handleMessageRaw(json.encodeToString(Outbound(type = "screen_attached")))
        assertTrue(c.inScreenSession)
    }

    @Test
    fun `handleMessage screen_detached clears inScreenSession`() {
        val c = newClient()
        c.handleMessageRaw(json.encodeToString(Outbound(type = "screen_attached")))
        assertTrue(c.inScreenSession)
        c.handleMessageRaw(json.encodeToString(Outbound(type = "screen_detached")))
        assertFalse(c.inScreenSession)
    }

    @Test
    fun `handleMessage screen_line invokes onScreenLine`() {
        val collected = mutableListOf<String>()
        val c = newClient(onScreenLine = { collected += it })
        c.handleMessageRaw(json.encodeToString(Outbound(type = "screen_line", text = "hello world")))
        assertEquals(listOf("hello world"), collected)
    }

    @Test
    fun `handleMessage screen_error routes through onEvent with prefix`() {
        val events = mutableListOf<String>()
        val c = newClient(onEvent = { events += it })
        c.handleMessageRaw(json.encodeToString(Outbound(type = "screen_error", text = "nope")))
        assertEquals(1, events.size)
        assertTrue(events[0].contains("nope"))
    }

    @Test
    fun `handleMessage event renders via OutputRenderer`() {
        val events = mutableListOf<String>()
        val c = newClient(onEvent = { events += it })
        val body = json.encodeToString(Outbound(
            type = "event",
            event = EventPayload(
                type = "SERVICE_READY",
                timestamp = "2026-04-19T12:00:00Z",
                data = mapOf("service" to "Lobby-1")
            )
        ))
        c.handleMessageRaw(body)
        assertEquals(1, events.size)
        assertTrue(events[0].contains("Lobby-1"))
        assertTrue(events[0].contains("READY"))
    }

    @Test
    fun `handleMessage unknown type is ignored`() {
        val c = newClient()
        c.handleMessageRaw(json.encodeToString(Outbound(type = "galaxy_brain")))
        // no crash, no state change
        assertFalse(c.inScreenSession)
    }

    @Test
    fun `handleMessage output with no matching id is dropped`() {
        val c = newClient()
        // No pending command with id="42", so this is a no-op (doesn't crash).
        c.handleMessageRaw(json.encodeToString(Outbound(
            type = "output",
            id = "42",
            line = OutputLine("info", "hi")
        )))
    }

    @Test
    fun `disconnect sets isConnected false`() {
        val c = newClient()
        // isConnected starts false; calling disconnect should keep it false.
        c.disconnect()
        assertFalse(c.isConnected)
    }

    @Test
    fun `complete returns empty list when not connected`() = runBlocking {
        val c = newClient()
        val result = c.complete("anything")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `screenDetach and screenAttach do not throw when unconnected`() = runBlocking {
        val c = newClient()
        c.screenAttach("Lobby-1")
        c.screenInput("Lobby-1", "say hi")
        c.screenDetach()
        // no exceptions
    }

    @Test
    fun `ClientInfo data class stores fields`() {
        val ci = StreamClient.ClientInfo(username = "u", hostname = "h", os = "Linux x64")
        assertEquals("u", ci.username)
        assertEquals("h", ci.hostname)
        assertEquals("Linux x64", ci.os)
    }

    @Test
    fun `EventData default values`() {
        val e = StreamClient.EventData()
        assertEquals("", e.type)
        assertEquals("", e.timestamp)
        assertTrue(e.data.isEmpty())
    }

    @Test
    fun `OutputLine stores type and text`() {
        val l = StreamClient.OutputLine("info", "hello")
        assertEquals("info", l.type)
        assertEquals("hello", l.text)
    }
}
