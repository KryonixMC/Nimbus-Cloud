package dev.nimbuspowered.nimbus.cli

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jline.reader.LineReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebSocket client for the console stream endpoint.
 * Handles command execution, live events, and screen sessions.
 */
class StreamClient(
    private val httpClient: HttpClient,
    private val host: String,
    private val port: Int,
    private val token: String,
    private val onEvent: (String) -> Unit,
    private val onScreenLine: (String) -> Unit
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val idCounter = AtomicInteger(0)
    private val pendingCommands = ConcurrentHashMap<String, Channel<OutputLine>>()
    private val pendingCompletions = ConcurrentHashMap<String, CompletableDeferred<List<String>>>()

    @Volatile
    private var session: DefaultClientWebSocketSession? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var inScreenSession: Boolean = false
        private set

    @Serializable
    data class OutputLine(val type: String, val text: String)

    @Serializable
    private data class MessageOut(
        val type: String,
        val id: String = "",
        val input: String = "",
        val service: String = "",
        val text: String = ""
    )

    @Serializable
    private data class MessageIn(
        val type: String = "",
        val id: String = "",
        val line: OutputLine? = null,
        val event: EventData? = null,
        val text: String = "",
        val candidates: List<String> = emptyList()
    )

    @Serializable
    data class EventData(
        val type: String = "",
        val timestamp: String = "",
        val data: Map<String, String> = emptyMap()
    )

    /**
     * Connects to the controller's WebSocket console stream.
     * Runs the receive loop in the provided scope.
     */
    suspend fun connect(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                try {
                    httpClient.webSocket(
                        method = HttpMethod.Get,
                        host = this@StreamClient.host,
                        port = this@StreamClient.port,
                        path = "/api/console/stream",
                        request = {
                            if (token.isNotBlank()) {
                                headers.append(HttpHeaders.Authorization, "Bearer $token")
                            }
                        }
                    ) {
                        session = this
                        isConnected = true

                        try {
                            for (frame in incoming) {
                                if (frame !is Frame.Text) continue
                                handleMessage(frame.readText())
                            }
                        } catch (_: ClosedReceiveChannelException) {
                            // Server closed connection
                        } catch (_: CancellationException) {
                            throw CancellationException()
                        } catch (e: Exception) {
                            // Connection error
                        }
                    }
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    // Connection failed
                }

                isConnected = false
                session = null

                // Fail all pending operations
                pendingCommands.values.forEach { it.close() }
                pendingCommands.clear()
                pendingCompletions.values.forEach { it.completeExceptionally(Exception("Disconnected")) }
                pendingCompletions.clear()

                // Reconnect after delay
                delay(3000)
            }
        }
    }

    private fun handleMessage(text: String) {
        val msg = try {
            json.decodeFromString<MessageIn>(text)
        } catch (e: Exception) {
            return
        }

        when (msg.type) {
            "output" -> {
                val channel = pendingCommands[msg.id] ?: return
                val line = msg.line ?: return
                channel.trySend(line)
            }

            "output_end" -> {
                val channel = pendingCommands.remove(msg.id) ?: return
                channel.close()
            }

            "completions" -> {
                val deferred = pendingCompletions.remove(msg.id) ?: return
                deferred.complete(msg.candidates)
            }

            "event" -> {
                val event = msg.event ?: return
                val rendered = OutputRenderer.renderEvent(event.type, event.data, event.timestamp)
                onEvent(rendered)
            }

            "screen_line" -> {
                onScreenLine(msg.text)
            }

            "screen_attached" -> {
                inScreenSession = true
            }

            "screen_detached" -> {
                inScreenSession = false
            }

            "screen_error" -> {
                onEvent("\u001B[31m✗\u001B[0m ${msg.text}")
            }
        }
    }

    /**
     * Executes a command on the controller and streams output lines.
     * Each line is rendered and printed as it arrives.
     */
    suspend fun executeCommand(input: String, render: (OutputLine) -> Unit) {
        val id = idCounter.incrementAndGet().toString()
        val channel = Channel<OutputLine>(Channel.UNLIMITED)
        pendingCommands[id] = channel

        val msg = MessageOut(type = "execute", id = id, input = input)
        session?.send(Frame.Text(json.encodeToString(msg)))
            ?: throw IllegalStateException("Not connected")

        try {
            for (line in channel) {
                render(line)
            }
        } catch (_: Exception) {
            // Channel closed
        } finally {
            pendingCommands.remove(id)
        }
    }

    /**
     * Requests tab-completion via WebSocket (fallback if REST fails).
     */
    suspend fun complete(buffer: String): List<String> {
        val id = idCounter.incrementAndGet().toString()
        val deferred = CompletableDeferred<List<String>>()
        pendingCompletions[id] = deferred

        val msg = MessageOut(type = "complete", id = id, input = buffer)
        session?.send(Frame.Text(json.encodeToString(msg)))
            ?: return emptyList()

        return try {
            withTimeout(5000) { deferred.await() }
        } catch (_: Exception) {
            pendingCompletions.remove(id)
            emptyList()
        }
    }

    /**
     * Attaches to a service's console for interactive screen session.
     */
    suspend fun screenAttach(serviceName: String) {
        val msg = MessageOut(type = "screen_attach", service = serviceName)
        session?.send(Frame.Text(json.encodeToString(msg)))
    }

    /**
     * Sends input to the current screen session.
     */
    suspend fun screenInput(serviceName: String, text: String) {
        val msg = MessageOut(type = "screen_input", service = serviceName, text = text)
        session?.send(Frame.Text(json.encodeToString(msg)))
    }

    /**
     * Detaches from the current screen session.
     */
    suspend fun screenDetach() {
        val msg = MessageOut(type = "screen_detach")
        session?.send(Frame.Text(json.encodeToString(msg)))
    }

    fun disconnect() {
        isConnected = false
        session = null
    }
}
