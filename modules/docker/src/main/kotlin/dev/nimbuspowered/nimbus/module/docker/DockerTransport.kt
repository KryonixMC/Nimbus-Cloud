package dev.nimbuspowered.nimbus.module.docker

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.Locale

/**
 * Minimal socket abstraction — Docker Engine API is plain HTTP/1.1 over either
 * a Unix Domain Socket (Linux/Mac) or TCP (Docker Desktop Windows, rootless TCP
 * exposure, Podman).
 *
 * Opens a fresh connection per call — Docker's API tolerates it fine and the
 * code stays simple. Long-lived streams (attach, logs follow) take a raw
 * connection via [openStream] so the socket stays open beyond a single request.
 */
internal class DockerTransport(private val endpoint: String) {

    private val kind: EndpointKind
    private val unixPath: String?
    private val tcpHost: String?
    private val tcpPort: Int

    init {
        when {
            endpoint.startsWith("unix://") -> {
                kind = EndpointKind.UNIX
                unixPath = endpoint.removePrefix("unix://")
                tcpHost = null
                tcpPort = 0
            }
            endpoint.startsWith("tcp://") -> {
                kind = EndpointKind.TCP
                val rest = endpoint.removePrefix("tcp://")
                val colon = rest.lastIndexOf(':')
                if (colon < 0) {
                    tcpHost = rest
                    tcpPort = 2375
                } else {
                    tcpHost = rest.substring(0, colon)
                    tcpPort = rest.substring(colon + 1).toIntOrNull() ?: 2375
                }
                unixPath = null
            }
            endpoint.startsWith("npipe://") -> {
                throw UnsupportedOperationException(
                    "Windows named pipes are not supported in Phase 1. Expose Docker over TCP " +
                        "(tcp://localhost:2375) in Docker Desktop settings, or run from WSL2 where " +
                        "the Unix socket is available at /var/run/docker.sock."
                )
            }
            endpoint.startsWith("/") -> {
                kind = EndpointKind.UNIX
                unixPath = endpoint
                tcpHost = null
                tcpPort = 0
            }
            else -> throw IllegalArgumentException(
                "Unrecognised Docker endpoint '$endpoint' — expected unix:///path, tcp://host:port, or an absolute socket path."
            )
        }
    }

    /**
     * Single request/response round-trip — builds the HTTP/1.1 request, reads the
     * full response, closes the connection. Suitable for endpoints that return a
     * bounded JSON body.
     */
    fun request(
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap()
    ): DockerResponse {
        val conn = connect()
        try {
            writeRequest(conn, method, path, body, contentType, headers, keepAlive = false)
            return readResponse(conn.input)
        } finally {
            runCatching { conn.close() }
        }
    }

    /**
     * Opens a long-lived connection, writes the request, and returns the raw
     * streams plus parsed response head. The caller owns the connection and is
     * responsible for calling [DockerStream.close].
     */
    fun openStream(
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap()
    ): DockerStream {
        val conn = connect()
        try {
            writeRequest(conn, method, path, body, contentType, headers, keepAlive = true)
            val status = parseStatusLine(conn.input)
            val respHeaders = parseHeaders(conn.input)
            return DockerStream(status, respHeaders, conn.input, conn.output, conn.close)
        } catch (e: Exception) {
            runCatching { conn.close() }
            throw e
        }
    }

    private fun writeRequest(
        conn: Connection,
        method: String,
        path: String,
        body: ByteArray?,
        contentType: String,
        headers: Map<String, String>,
        keepAlive: Boolean
    ) {
        val sb = StringBuilder()
        sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
        sb.append("Host: docker\r\n")
        sb.append("User-Agent: nimbus-docker-module/1.0\r\n")
        sb.append("Accept: application/json\r\n")
        if (!keepAlive) sb.append("Connection: close\r\n")
        if (body != null) {
            sb.append("Content-Type: ").append(contentType).append("\r\n")
            sb.append("Content-Length: ").append(body.size).append("\r\n")
        }
        for ((k, v) in headers) {
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("\r\n")
        conn.output.write(sb.toString().toByteArray(Charsets.US_ASCII))
        body?.let { conn.output.write(it) }
        conn.output.flush()
    }

    private fun connect(): Connection {
        return when (kind) {
            EndpointKind.UNIX -> {
                val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
                // Non-blocking connect + Selector gives us the same 5s timeout
                // behaviour as the TCP path. Without it, a hung daemon (socket
                // file present, daemon unresponsive) would block isAvailable()
                // forever on every service start.
                ch.configureBlocking(false)
                val selector = Selector.open()
                try {
                    ch.register(selector, SelectionKey.OP_CONNECT)
                    ch.connect(UnixDomainSocketAddress.of(unixPath!!))
                    val deadline = System.currentTimeMillis() + 5_000L
                    while (!ch.finishConnect()) {
                        val remaining = deadline - System.currentTimeMillis()
                        if (remaining <= 0 || selector.select(remaining) == 0) {
                            runCatching { ch.close() }
                            throw DockerException("Timed out connecting to Unix socket $unixPath after 5s")
                        }
                        selector.selectedKeys().clear()
                    }
                } finally {
                    runCatching { selector.close() }
                }
                ch.configureBlocking(true)
                Connection(
                    input = Channels.newInputStream(ch),
                    output = Channels.newOutputStream(ch),
                    close = { ch.close() }
                )
            }
            EndpointKind.TCP -> {
                val socket = Socket()
                socket.connect(InetSocketAddress(tcpHost, tcpPort), 5_000)
                Connection(
                    input = socket.getInputStream(),
                    output = socket.getOutputStream(),
                    close = { socket.close() }
                )
            }
        }
    }

    private fun readResponse(input: InputStream): DockerResponse {
        val status = parseStatusLine(input)
        val headers = parseHeaders(input)
        val transferEncoding = headers["transfer-encoding"]?.lowercase(Locale.ROOT)
        val contentLength = headers["content-length"]?.toIntOrNull()

        val body = when {
            status == 204 -> ByteArray(0)
            transferEncoding?.contains("chunked") == true -> readChunkedBody(input)
            contentLength != null -> readFixedBody(input, contentLength)
            else -> input.readAllBytes()
        }
        return DockerResponse(status, headers, body)
    }

    private fun parseStatusLine(input: InputStream): Int {
        val line = readHttpLine(input)
            ?: throw DockerException("Empty response from Docker endpoint")
        val parts = line.split(' ', limit = 3)
        if (parts.size < 2) throw DockerException("Malformed status line: $line")
        return parts[1].toIntOrNull() ?: throw DockerException("Bad status code in: $line")
    }

    private fun parseHeaders(input: InputStream): Map<String, String> {
        val map = mutableMapOf<String, String>()
        while (true) {
            val line = readHttpLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            val value = line.substring(colon + 1).trim()
            map[key] = value
        }
        return map
    }

    private fun readHttpLine(input: InputStream): String? {
        val buf = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.isEmpty()) null else buf.toString()
            if (b == '\r'.code) {
                val next = input.read()
                if (next == '\n'.code) return buf.toString()
                if (next < 0) return buf.toString()
                buf.append(b.toChar()).append(next.toChar())
            } else if (b == '\n'.code) {
                return buf.toString()
            } else {
                buf.append(b.toChar())
            }
        }
    }

    private fun readFixedBody(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(out, read, length - read)
            if (n < 0) break
            read += n
        }
        return if (read == length) out else out.copyOf(read)
    }

    private fun readChunkedBody(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val sizeLine = readHttpLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                while (true) {
                    val t = readHttpLine(input) ?: break
                    if (t.isEmpty()) break
                }
                break
            }
            val chunk = readFixedBody(input, size)
            out.write(chunk)
            readHttpLine(input)
        }
        return out.toByteArray()
    }

    private enum class EndpointKind { UNIX, TCP }

    private data class Connection(
        val input: InputStream,
        val output: OutputStream,
        val close: () -> Unit
    )
}

/** Result of a one-shot Docker API call. */
internal data class DockerResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray
) {
    val bodyText: String get() = String(body, Charsets.UTF_8)
}

/** Long-lived connection handle for streaming endpoints (logs, attach, stats). */
class DockerStream(
    val status: Int,
    val headers: Map<String, String>,
    val input: InputStream,
    val output: OutputStream,
    private val closer: () -> Unit
) : AutoCloseable {
    override fun close() {
        runCatching { closer() }
    }
}

class DockerException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
