package dev.nimbuspowered.nimbus.module.docker

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tests DockerTransport against a local TCP socket that plays the role of the
 * Docker daemon. We don't talk to a real daemon — just exercise the HTTP/1.1
 * request serializer + response parser.
 */
class DockerTransportTest {

    private lateinit var server: ServerSocket
    private val exec = Executors.newSingleThreadExecutor()
    private var lastRequest: String = ""

    @BeforeEach
    fun start() {
        server = ServerSocket(0) // ephemeral
    }

    @AfterEach
    fun stop() {
        runCatching { server.close() }
        exec.shutdownNow()
    }

    private fun serve(rawResponse: ByteArray) {
        exec.submit {
            server.accept().use { s ->
                val input = s.getInputStream()
                // Read headers until CRLFCRLF, then (best-effort) the body
                val reqBuf = StringBuilder()
                var last4 = 0
                while (true) {
                    val b = input.read()
                    if (b < 0) break
                    reqBuf.append(b.toChar())
                    last4 = ((last4 shl 8) or (b and 0xFF)) and 0x7FFFFFFF
                    // CRLFCRLF = 0x0D0A0D0A
                    if (last4 == 0x0D0A0D0A) break
                }
                // If Content-Length present, read body
                val reqStr = reqBuf.toString()
                val clMatch = Regex("(?i)content-length:\\s*(\\d+)").find(reqStr)
                if (clMatch != null) {
                    val len = clMatch.groupValues[1].toInt()
                    val body = ByteArray(len)
                    var read = 0
                    while (read < len) {
                        val n = input.read(body, read, len - read)
                        if (n < 0) break
                        read += n
                    }
                }
                lastRequest = reqStr
                s.getOutputStream().write(rawResponse)
                s.getOutputStream().flush()
            }
        }
    }

    private fun endpoint() = "tcp://127.0.0.1:${server.localPort}"

    @Test
    fun `parses fixed content-length response`() {
        val body = """{"msg":"hi"}"""
        val resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        serve(resp.toByteArray(Charsets.US_ASCII))

        val transport = DockerTransport(endpoint())
        val r = transport.request("GET", "/v1.41/_ping")
        assertEquals(200, r.status)
        assertEquals(body, r.bodyText)
        assertEquals("application/json", r.headers["content-type"])
    }

    @Test
    fun `parses chunked transfer-encoding`() {
        val chunk1 = "hello "
        val chunk2 = "world"
        val resp = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Transfer-Encoding: chunked\r\n")
            append("Connection: close\r\n")
            append("\r\n")
            append(Integer.toHexString(chunk1.length)).append("\r\n").append(chunk1).append("\r\n")
            append(Integer.toHexString(chunk2.length)).append("\r\n").append(chunk2).append("\r\n")
            append("0\r\n\r\n")
        }
        serve(resp.toByteArray(Charsets.US_ASCII))

        val transport = DockerTransport(endpoint())
        val r = transport.request("GET", "/anything")
        assertEquals(200, r.status)
        assertEquals("hello world", r.bodyText)
    }

    @Test
    fun `204 no-content has empty body`() {
        val resp = "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"
        serve(resp.toByteArray(Charsets.US_ASCII))

        val transport = DockerTransport(endpoint())
        val r = transport.request("POST", "/v1.41/containers/abc/start")
        assertEquals(204, r.status)
        assertEquals(0, r.body.size)
    }

    @Test
    fun `request includes body with content-type and length`() {
        val resp = "HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        serve(resp.toByteArray(Charsets.US_ASCII))

        val transport = DockerTransport(endpoint())
        val body = """{"Name":"demo"}""".toByteArray(Charsets.UTF_8)
        val r = transport.request("POST", "/v1.41/containers/create", body = body)
        assertEquals(201, r.status)

        // Allow the server thread to finish recording the request
        exec.shutdown()
        exec.awaitTermination(2, TimeUnit.SECONDS)
        assertTrue(lastRequest.contains("POST /v1.41/containers/create HTTP/1.1"))
        assertTrue(lastRequest.contains("Content-Type: application/json"))
        assertTrue(lastRequest.contains("Content-Length: ${body.size}"))
    }

    @Test
    fun `npipe endpoint is rejected with clear message`() {
        val ex = assertThrows<UnsupportedOperationException> { DockerTransport("npipe:////./pipe/docker_engine") }
        assertTrue(ex.message!!.contains("Windows named pipes"))
    }

    @Test
    fun `malformed endpoint throws`() {
        assertThrows<IllegalArgumentException> { DockerTransport("http://not-supported") }
    }
}
