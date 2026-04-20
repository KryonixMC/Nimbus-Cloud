package dev.nimbuspowered.nimbus.module.docker

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Minimal HTTP/1.1 server for unit-testing DockerClient. Runs on an ephemeral
 * port; queue responses via [enqueue] — each accepted request pops one.
 */
class FakeDockerDaemon : AutoCloseable {

    val server: ServerSocket = ServerSocket(0)
    private val exec = Executors.newCachedThreadPool()
    private val queue = ConcurrentLinkedQueue<Response>()
    val requests = ConcurrentLinkedQueue<RecordedRequest>()

    data class Response(val status: Int, val reason: String, val body: String, val contentType: String = "application/json")
    data class RecordedRequest(val method: String, val path: String, val body: String)

    val endpoint: String get() = "tcp://127.0.0.1:${server.localPort}"

    init {
        exec.submit {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: Exception) { return@submit }
                exec.submit { handle(s) }
            }
        }
    }

    fun enqueue(status: Int, reason: String, body: String, contentType: String = "application/json") {
        queue.add(Response(status, reason, body, contentType))
    }

    fun enqueue204() = enqueue(204, "No Content", "", "")
    fun enqueue201(body: String) = enqueue(201, "Created", body)
    fun enqueue200(body: String) = enqueue(200, "OK", body)
    fun enqueue404() = enqueue(404, "Not Found", "{\"message\":\"missing\"}")

    private fun handle(s: Socket) {
        s.use {
            val input = s.getInputStream()
            val buf = StringBuilder()
            var last4 = 0
            while (true) {
                val b = input.read()
                if (b < 0) return
                buf.append(b.toChar())
                last4 = ((last4 shl 8) or (b and 0xFF)) and 0x7FFFFFFF
                if (last4 == 0x0D0A0D0A) break
            }
            val headers = buf.toString()
            val requestLine = headers.lineSequence().first()
            val parts = requestLine.split(' ')
            val method = parts.getOrElse(0) { "" }
            val path = parts.getOrElse(1) { "" }
            val clMatch = Regex("(?i)content-length:\\s*(\\d+)").find(headers)
            val bodyStr = if (clMatch != null) {
                val len = clMatch.groupValues[1].toInt()
                val body = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = input.read(body, read, len - read)
                    if (n < 0) break
                    read += n
                }
                String(body, Charsets.UTF_8)
            } else ""
            requests.add(RecordedRequest(method, path, bodyStr))

            val resp = queue.poll() ?: Response(500, "No Mock", "{\"message\":\"no mock queued\"}")
            val out = s.getOutputStream()
            val bodyBytes = resp.body.toByteArray(Charsets.UTF_8)
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(resp.status).append(' ').append(resp.reason).append("\r\n")
            if (resp.contentType.isNotEmpty()) sb.append("Content-Type: ").append(resp.contentType).append("\r\n")
            sb.append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.US_ASCII))
            out.write(bodyBytes)
            out.flush()
        }
    }

    override fun close() {
        runCatching { server.close() }
        exec.shutdownNow()
        exec.awaitTermination(1, TimeUnit.SECONDS)
    }
}
