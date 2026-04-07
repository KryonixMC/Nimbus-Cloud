package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.api.*
import dev.nimbuspowered.nimbus.console.CommandDispatcher
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.module.CommandOutput
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
import org.slf4j.LoggerFactory
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
    serviceToken: String = ""
) {
    route("/api/console") {

        // POST /api/console/complete — Tab completion
        post("complete") {
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
        logger.info("Remote CLI session #{} connected", sessionId)

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
                    "execute" -> {
                        val output = WebSocketCommandOutput(this, msg.id, json)
                        dispatcher.dispatch(msg.input, output)
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
            logger.info("Remote CLI session #{} disconnected", sessionId)
        }
    }
}

/**
 * [CommandOutput] implementation that sends each output line over WebSocket
 * as a [ConsoleMessageOut] with the given command ID.
 */
private class WebSocketCommandOutput(
    private val session: DefaultWebSocketServerSession,
    private val commandId: String,
    private val json: Json
) : CommandOutput {

    private suspend fun send(type: String, text: String) {
        val msg = ConsoleMessageOut(
            type = "output",
            id = commandId,
            line = OutputLineResponse(type, text)
        )
        session.send(Frame.Text(json.encodeToString(msg)))
    }

    override fun header(text: String) {
        kotlinx.coroutines.runBlocking { send("header", text) }
    }

    override fun info(text: String) {
        kotlinx.coroutines.runBlocking { send("info", text) }
    }

    override fun success(text: String) {
        kotlinx.coroutines.runBlocking { send("success", text) }
    }

    override fun error(text: String) {
        kotlinx.coroutines.runBlocking { send("error", text) }
    }

    override fun item(text: String) {
        kotlinx.coroutines.runBlocking { send("item", text) }
    }

    override fun text(text: String) {
        kotlinx.coroutines.runBlocking { send("text", text) }
    }
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
            else -> {} // Other events handled by type name only
        }
    }

    return EventMessage(
        type = type,
        timestamp = this.timestamp.toString(),
        data = data
    )
}
