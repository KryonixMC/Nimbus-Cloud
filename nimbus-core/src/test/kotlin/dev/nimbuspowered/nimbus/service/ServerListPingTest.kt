package dev.nimbuspowered.nimbus.service

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Spins up a loopback TCP server that speaks just enough of the Minecraft
 * Server List Ping (Handshake + Status Request → Status Response) to cover
 * the ServerListPing parser. Keeps everything on localhost — no real MC server.
 */
class ServerListPingTest {

    private var server: ServerSocket? = null
    private var serverThread: Thread? = null

    @AfterEach
    fun tearDown() {
        try { server?.close() } catch (_: Exception) {}
        serverThread?.interrupt()
    }

    private fun startMockServer(statusJson: String): Int {
        val sock = ServerSocket(0)
        server = sock
        serverThread = thread(isDaemon = true, name = "slp-mock") {
            try {
                sock.accept().use { client ->
                    val input = DataInputStream(client.getInputStream())
                    val output = DataOutputStream(client.getOutputStream())

                    // Read handshake packet: length-prefixed
                    readVarInt(input) // handshake length
                    readVarInt(input) // packet id (0x00)
                    readVarInt(input) // protocol version
                    readString(input) // host
                    input.readUnsignedByte(); input.readUnsignedByte() // port (2 bytes)
                    readVarInt(input) // next state

                    // Read status request
                    readVarInt(input) // length
                    readVarInt(input) // packet id (0x00)

                    // Build status response
                    val payload = ByteArrayOutputStream()
                    writeVarInt(payload, 0x00) // packet id
                    val jsonBytes = statusJson.toByteArray(Charsets.UTF_8)
                    writeVarInt(payload, jsonBytes.size)
                    payload.write(jsonBytes)
                    val packet = payload.toByteArray()

                    val framed = ByteArrayOutputStream()
                    writeVarInt(framed, packet.size)
                    framed.write(packet)
                    output.write(framed.toByteArray())
                    output.flush()

                    // Give client time to read before close
                    Thread.sleep(50)
                }
            } catch (_: Exception) {
                // Connection closed / server stopped — expected on teardown.
            }
        }
        return sock.localPort
    }

    private fun readVarInt(input: DataInputStream): Int {
        var value = 0
        var position = 0
        while (true) {
            val byte = input.readByte().toInt()
            value = value or ((byte and 0x7F) shl position)
            if (byte and 0x80 == 0) return value
            position += 7
            if (position >= 32) throw RuntimeException("VarInt too large")
        }
    }

    private fun readString(input: DataInputStream): String {
        val length = readVarInt(input)
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun writeVarInt(output: ByteArrayOutputStream, value: Int) {
        var remaining = value
        while (true) {
            if (remaining and 0x7F.inv() == 0) {
                output.write(remaining)
                return
            }
            output.write((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
    }

    @Test
    fun `parses valid status response with MOTD and players`() {
        val json = """
            {
              "version": {"name": "Paper 1.21.4", "protocol": 767},
              "players": {"max": 100, "online": 7, "sample": [{"name":"alice","id":"x"},{"name":"bob","id":"y"}]},
              "description": {"text": "Hello MOTD"}
            }
        """.trimIndent()
        val port = startMockServer(json)
        ServerListPing.resetFailures("127.0.0.1", port)

        val result = ServerListPing.ping("127.0.0.1", port, timeout = 3000)
        assertNotNull(result)
        assertEquals(7, result!!.onlinePlayers)
        assertEquals(100, result.maxPlayers)
        assertEquals(listOf("alice", "bob"), result.playerNames)
        assertEquals("Hello MOTD", result.motd)
        assertEquals("Paper 1.21.4", result.version)
    }

    @Test
    fun `handles missing description gracefully`() {
        val json = """{"version":{"name":"v","protocol":0},"players":{"max":50,"online":0}}"""
        val port = startMockServer(json)
        ServerListPing.resetFailures("127.0.0.1", port)

        val result = ServerListPing.ping("127.0.0.1", port, timeout = 3000)
        assertNotNull(result)
        assertEquals("", result!!.motd)
        assertEquals(0, result.onlinePlayers)
        assertEquals(emptyList<String>(), result.playerNames)
    }

    @Test
    fun `returns null when host is unreachable`() {
        // Port 1 is reserved and should always refuse. Use a random free port we close immediately.
        val sock = ServerSocket(0)
        val port = sock.localPort
        sock.close()
        ServerListPing.resetFailures("127.0.0.1", port)

        val result = ServerListPing.ping("127.0.0.1", port, timeout = 500)
        assertNull(result)
    }

    @Test
    fun `backoff skips ping after repeated failures`() {
        val sock = ServerSocket(0)
        val port = sock.localPort
        sock.close()
        ServerListPing.resetFailures("127.0.0.1", port)

        // Three failed pings should trigger backoff
        repeat(3) { ServerListPing.ping("127.0.0.1", port, timeout = 300) }
        // Fourth call should short-circuit without blocking/connecting
        val start = System.currentTimeMillis()
        val result = ServerListPing.ping("127.0.0.1", port, timeout = 5000)
        val elapsed = System.currentTimeMillis() - start
        assertNull(result)
        assertTrue(elapsed < 200, "expected fast-fail via backoff, took ${elapsed}ms")

        // reset clears the counter
        ServerListPing.resetFailures("127.0.0.1", port)
    }

    @Test
    fun `malformed json response returns null`() {
        val port = startMockServer("not json at all")
        ServerListPing.resetFailures("127.0.0.1", port)

        val result = ServerListPing.ping("127.0.0.1", port, timeout = 3000)
        assertNull(result)
    }
}
