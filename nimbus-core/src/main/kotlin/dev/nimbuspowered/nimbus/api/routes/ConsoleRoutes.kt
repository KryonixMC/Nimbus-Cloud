package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.api.*
import io.ktor.http.*
import dev.nimbuspowered.nimbus.console.CommandDispatcher
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.module.api.CommandOutput
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
private val logger = LoggerFactory.getLogger("ConsoleRoutes")
private val sessionCounter = AtomicInteger(0)

fun Route.consoleRoutes(
    dispatcher: CommandDispatcher,
    eventBus: EventBus,
    registry: ServiceRegistry,
    serviceManager: ServiceManager,
    token: String,
    serviceToken: String = "",
    scope: CoroutineScope? = null,
    geoLookupEnabled: Boolean = false
) {
    route("/api/console") {

        // POST /api/console/complete — Tab completion (C1 fix: requires auth)
        post("complete") {
            if (token.isNotBlank()) {
                val authHeader = call.request.header("Authorization")
                val clientToken = when {
                    authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true) ->
                        authHeader.removePrefix("Bearer ").removePrefix("bearer ")
                    else -> call.request.queryParameters["token"]
                }
                if (clientToken == null || !NimbusApi.timingSafeEquals(clientToken, token)) {
                    call.respond(HttpStatusCode.Unauthorized, apiError("Authentication required", ApiError.UNAUTHORIZED))
                    return@post
                }
            }
            val request = call.receive<CompleteRequest>()
            val buffer = if (request.cursor >= 0 && request.cursor < request.buffer.length) {
                request.buffer.substring(0, request.cursor)
            } else {
                request.buffer
            }
            val candidates = dispatcher.complete(buffer)
            call.respond(CompleteResponse(candidates))
        }
    }

    // WS /api/console/stream — Multiplexed console stream
    webSocket("/api/console/stream") {
        if (!authenticateConsoleWebSocket(token)) return@webSocket

        val sessionId = sessionCounter.incrementAndGet()
        val connectedAt = Instant.now()
        val remoteIp = call.request.local.remoteAddress
        var clientUsername = ""
        var clientHostname = ""
        var clientOs = ""
        var clientLocation = ""
        var commandCount = 0

        logger.info("Remote CLI session #{} connected from {}", sessionId, remoteIp)

        // Subscribe to event bus for live events
        val eventSubscription = eventBus.subscribe()
        val eventJob = launch {
            try {
                eventSubscription.collect { event ->
                    // Suppress high-frequency events
                    if (event is NimbusEvent.StressTestUpdated) return@collect
                    if (event is NimbusEvent.NodeHeartbeat) return@collect

                    val eventMsg = event.toConsoleEventMessage()
                    val msg = ConsoleMessageOut(
                        type = "event",
                        event = eventMsg
                    )
                    send(Frame.Text(json.encodeToString(msg)))
                }
            } catch (_: ClosedReceiveChannelException) {
                // Client disconnected
            } catch (_: Exception) {
                // Connection closed
            }
        }

        // Track active screen session
        var screenJob: kotlinx.coroutines.Job? = null

        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()

                val msg = try {
                    json.decodeFromString<ConsoleMessageIn>(text)
                } catch (e: Exception) {
                    logger.debug("Invalid console message: {}", e.message)
                    continue
                }

                when (msg.type) {
                    "hello" -> {
                        // Parse client info from hello message
                        try {
                            val info = json.decodeFromString<ClientHello>(msg.text)
                            clientUsername = info.username
                            clientHostname = info.hostname
                            clientOs = info.os
                        } catch (_: Exception) {}

                        // GeoIP lookup (async, non-blocking). Off by default — see
                        // ConsoleConfig.geoLookupEnabled.
                        scope?.launch {
                            clientLocation = if (geoLookupEnabled) lookupGeoIp(remoteIp) else ""
                            eventBus.emit(NimbusEvent.CliSessionConnected(
                                sessionId, remoteIp, clientUsername, clientHostname, clientOs, clientLocation
                            ))
                        }
                    }

                    "execute" -> {
                        commandCount++
                        val output = WebSocketCommandOutput(this, msg.id, json)
                        // Launch drain loop to send output without blocking Ktor threads (C4 fix)
                        val drainJob = launch { output.drainLoop() }
                        dispatcher.dispatch(msg.input, output)
                        output.close()
                        drainJob.join()
                        // Signal command completion
                        send(Frame.Text(json.encodeToString(
                            ConsoleMessageOut(type = "output_end", id = msg.id)
                        )))
                    }

                    "complete" -> {
                        val candidates = dispatcher.complete(msg.input)
                        send(Frame.Text(json.encodeToString(
                            ConsoleMessageOut(type = "completions", id = msg.id, candidates = candidates)
                        )))
                    }

                    "screen_attach" -> {
                        val serviceName = msg.service
                        val service = registry.get(serviceName)
                        if (service == null) {
                            send(Frame.Text(json.encodeToString(
                                ConsoleMessageOut(type = "screen_error", text = "Service '$serviceName' not found")
                            )))
                            continue
                        }
                        val processHandle = serviceManager.getProcessHandle(serviceName)
                        if (processHandle == null) {
                            send(Frame.Text(json.encodeToString(
                                ConsoleMessageOut(type = "screen_error", text = "No process for '$serviceName'")
                            )))
                            continue
                        }

                        // Cancel any existing screen session
                        screenJob?.cancel()

                        // Forward process stdout to client
                        screenJob = launch {
                            try {
                                processHandle.stdoutLines.collect { line ->
                                    send(Frame.Text(json.encodeToString(
                                        ConsoleMessageOut(type = "screen_line", text = line)
                                    )))
                                }
                            } catch (_: Exception) {
                                // Session ended
                            }
                        }

                        send(Frame.Text(json.encodeToString(
                            ConsoleMessageOut(type = "screen_attached", text = serviceName)
                        )))
                    }

                    "screen_input" -> {
                        val serviceName = msg.service
                        val processHandle = serviceManager.getProcessHandle(serviceName)
                        if (processHandle != null && msg.text.isNotEmpty()) {
                            processHandle.sendCommand(msg.text)
                        }
                    }

                    "screen_detach" -> {
                        screenJob?.cancel()
                        screenJob = null
                        send(Frame.Text(json.encodeToString(
                            ConsoleMessageOut(type = "screen_detached")
                        )))
                    }
                }
            }
        } catch (_: ClosedReceiveChannelException) {
            // Client disconnected
        } finally {
            eventJob.cancel()
            screenJob?.cancel()
            val durationSeconds = java.time.Duration.between(connectedAt, Instant.now()).seconds
            logger.info("Remote CLI session #{} disconnected ({}s, {} cmds)", sessionId, durationSeconds, commandCount)

            // Emit CLI disconnected event
            scope?.launch {
                eventBus.emit(NimbusEvent.CliSessionDisconnected(sessionId, remoteIp, clientUsername, durationSeconds, commandCount))
            }
        }
    }
}

/**
 * [CommandOutput] implementation that sends each output line over WebSocket
 * as a [ConsoleMessageOut] with the given command ID.
 *
 * Uses a coroutine channel to bridge non-suspend [CommandOutput] methods to
 * the suspend WebSocket send, avoiding runBlocking on Ktor's CIO thread pool (C4 fix).
 */
private class WebSocketCommandOutput(
    private val session: DefaultWebSocketServerSession,
    private val commandId: String,
    private val json: Json
) : CommandOutput {

    private val channel = kotlinx.coroutines.channels.Channel<Pair<String, String>>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    /** Drain loop — launch this in the WebSocket coroutine scope before dispatching commands. */
    suspend fun drainLoop() {
        for ((type, text) in channel) {
            val msg = ConsoleMessageOut(
                type = "output",
                id = commandId,
                line = OutputLineResponse(type, text)
            )
            session.send(Frame.Text(json.encodeToString(msg)))
        }
    }

    /** Signal that no more output will be sent. */
    fun close() { channel.close() }

    private fun enqueue(type: String, text: String) {
        channel.trySend(type to text)
    }

    override fun header(text: String) = enqueue("header", text)
    override fun info(text: String) = enqueue("info", text)
    override fun success(text: String) = enqueue("success", text)
    override fun error(text: String) = enqueue("error", text)
    override fun item(text: String) = enqueue("item", text)
    override fun text(text: String) = enqueue("text", text)
}

/**
 * Authenticates a WebSocket connection for the console stream.
 * Only master token is accepted (admin-level access).
 */
private suspend fun DefaultWebSocketServerSession.authenticateConsoleWebSocket(
    masterToken: String
): Boolean {
    if (masterToken.isBlank()) return true

    val authHeader = call.request.headers["Authorization"]
    val clientToken = when {
        authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true) ->
            authHeader.removePrefix("Bearer ").removePrefix("bearer ")
        else -> call.request.queryParameters["token"]
    }

    if (clientToken == null || !NimbusApi.timingSafeEquals(clientToken, masterToken)) {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required"))
        return false
    }
    return true
}

/**
 * Reuses the event-to-EventMessage conversion from EventRoutes via extension function.
 */
private fun NimbusEvent.toConsoleEventMessage(): EventMessage {
    // Delegate to the same format as the /api/events endpoint
    val type = this::class.simpleName?.let { name ->
        // Convert CamelCase to SCREAMING_SNAKE_CASE
        name.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
    } ?: "UNKNOWN"

    val data = buildMap {
        when (val event = this@toConsoleEventMessage) {
            is NimbusEvent.ServiceStarting -> {
                put("service", event.serviceName); put("group", event.groupName); put("port", event.port.toString())
            }
            is NimbusEvent.ServiceReady -> {
                put("service", event.serviceName); put("group", event.groupName)
            }
            is NimbusEvent.ServiceDraining -> {
                put("service", event.serviceName); put("group", event.groupName)
            }
            is NimbusEvent.ServiceStopping -> put("service", event.serviceName)
            is NimbusEvent.ServiceStopped -> put("service", event.serviceName)
            is NimbusEvent.ServiceCrashed -> {
                put("service", event.serviceName); put("exitCode", event.exitCode.toString()); put("restartAttempt", event.restartAttempt.toString())
            }
            is NimbusEvent.ServiceRecovered -> {
                put("service", event.serviceName); put("group", event.groupName)
            }
            is NimbusEvent.ServiceCustomStateChanged -> {
                put("service", event.serviceName); put("group", event.groupName)
            }
            is NimbusEvent.ServiceDeployed -> {
                put("service", event.serviceName); put("group", event.groupName); put("filesChanged", event.filesChanged.toString())
            }
            is NimbusEvent.ServicePrepared -> {
                put("service", event.serviceName); put("group", event.groupName)
            }
            is NimbusEvent.WarmPoolReplenished -> {
                put("group", event.groupName); put("poolSize", event.poolSize.toString())
            }
            is NimbusEvent.ScaleUp -> {
                put("group", event.groupName); put("from", event.currentInstances.toString()); put("to", event.targetInstances.toString()); put("reason", event.reason)
            }
            is NimbusEvent.ScaleDown -> {
                put("group", event.groupName); put("service", event.serviceName); put("reason", event.reason)
            }
            is NimbusEvent.PlayerConnected -> {
                put("player", event.playerName); put("service", event.serviceName)
            }
            is NimbusEvent.PlayerDisconnected -> {
                put("player", event.playerName); put("service", event.serviceName)
            }
            is NimbusEvent.PlayerServerSwitch -> {
                put("player", event.playerName); put("from", event.fromService); put("to", event.toService)
            }
            is NimbusEvent.GroupCreated -> put("group", event.groupName)
            is NimbusEvent.GroupUpdated -> put("group", event.groupName)
            is NimbusEvent.GroupDeleted -> put("group", event.groupName)
            is NimbusEvent.MaintenanceEnabled -> {
                put("scope", event.scope); if (event.reason.isNotEmpty()) put("reason", event.reason)
            }
            is NimbusEvent.MaintenanceDisabled -> put("scope", event.scope)
            is NimbusEvent.NodeConnected -> {
                put("nodeId", event.nodeId); put("host", event.host)
            }
            is NimbusEvent.NodeDisconnected -> put("nodeId", event.nodeId)
            is NimbusEvent.ModuleLoaded -> {
                put("moduleId", event.moduleId); put("moduleName", event.moduleName)
            }
            is NimbusEvent.ModuleEnabled -> {
                put("moduleId", event.moduleId); put("moduleName", event.moduleName)
            }
            is NimbusEvent.ModuleDisabled -> {
                put("moduleId", event.moduleId); put("moduleName", event.moduleName)
            }
            is NimbusEvent.CliSessionConnected -> {
                put("sessionId", event.sessionId.toString()); put("remoteIp", event.remoteIp)
                put("clientUsername", event.clientUsername); put("clientHostname", event.clientHostname)
                put("clientOs", event.clientOs); put("location", event.location)
            }
            is NimbusEvent.CliSessionDisconnected -> {
                put("sessionId", event.sessionId.toString()); put("remoteIp", event.remoteIp)
                put("clientUsername", event.clientUsername)
                put("durationSeconds", event.durationSeconds.toString()); put("commandCount", event.commandCount.toString())
            }
            else -> {} // Other events handled by type name only
        }
    }

    return EventMessage(
        type = type,
        timestamp = this.timestamp.toString(),
        data = data
    )
}

@Serializable
private data class ClientHello(
    val username: String = "",
    val hostname: String = "",
    val os: String = ""
)

/**
 * Looks up approximate location for an IP address using ip-api.com.
 * Returns a human-readable location string or empty string on failure.
 * Skips lookup for private/local IPs.
 */
private suspend fun lookupGeoIp(ip: String): String {
    // Skip private/local IPs
    if (ip == "127.0.0.1" || ip == "0:0:0:0:0:0:0:1" || ip == "::1"
        || ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("172.")) {
        return "local"
    }

    return withContext(Dispatchers.IO) {
        try {
            // ip-api.com's free tier is HTTP-only; pro tier supports HTTPS.
            // Use HTTPS — if the operator hits the free tier they get a clear
            // failure logged below rather than a silent cleartext leak.
            val url = java.net.URI("https://ip-api.com/json/$ip?fields=status,city,regionName,country").toURL()
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.requestMethod = "GET"

            if (connection.responseCode == 200) {
                val body = connection.inputStream.bufferedReader().readText()
                // Simple JSON parsing without full deserialization
                val status = Regex(""""status"\s*:\s*"(\w+)"""").find(body)?.groupValues?.get(1)
                if (status == "success") {
                    val city = Regex(""""city"\s*:\s*"([^"]*?)"""").find(body)?.groupValues?.get(1) ?: ""
                    val region = Regex(""""regionName"\s*:\s*"([^"]*?)"""").find(body)?.groupValues?.get(1) ?: ""
                    val country = Regex(""""country"\s*:\s*"([^"]*?)"""").find(body)?.groupValues?.get(1) ?: ""
                    listOf(city, region, country).filter { it.isNotEmpty() }.joinToString(", ")
                } else ""
            } else ""
        } catch (e: Exception) {
            logger.debug("GeoIP lookup failed for {}: {}", ip, e.message)
            ""
        }
    }
}
