package dev.nimbus.api.routes

import dev.nimbus.api.ApiMessage
import dev.nimbus.api.EventMessage
import dev.nimbus.api.NimbusApi
import dev.nimbus.event.EventBus
import dev.nimbus.event.NimbusEvent
import dev.nimbus.service.ServiceManager
import dev.nimbus.service.ServiceRegistry
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = true }

fun Route.eventRoutes(
    eventBus: EventBus,
    registry: ServiceRegistry,
    serviceManager: ServiceManager,
    token: String
) {
    // WS /api/events — Live event stream
    webSocket("/api/events") {
        if (!authenticateWebSocket(token)) return@webSocket

        val subscription = eventBus.subscribe()

        try {
            subscription.collect { event ->
                val message = event.toEventMessage()
                send(Frame.Text(json.encodeToString(message)))
            }
        } catch (_: ClosedReceiveChannelException) {
            // Client disconnected
        }
    }

    // WS /api/services/{name}/console — Bidirectional console access
    webSocket("/api/services/{name}/console") {
        if (!authenticateWebSocket(token)) return@webSocket

        val serviceName = call.parameters["name"]!!
        val service = registry.get(serviceName)

        if (service == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Service '$serviceName' not found"))
            return@webSocket
        }

        val processHandle = serviceManager.getProcessHandle(serviceName)
        if (processHandle == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "No process handle for '$serviceName'"))
            return@webSocket
        }

        // Forward process stdout to WebSocket
        val outputJob = launch {
            try {
                processHandle.stdoutLines.collect { line ->
                    send(Frame.Text(line))
                }
            } catch (_: ClosedReceiveChannelException) {
                // Client disconnected
            } catch (_: Exception) {
                // Connection closed
            }
        }

        // Forward WebSocket input to process stdin
        try {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val command = frame.readText().trim()
                    if (command.isNotEmpty()) {
                        processHandle.sendCommand(command)
                    }
                }
            }
        } catch (_: ClosedReceiveChannelException) {
            // Client disconnected
        } finally {
            outputJob.cancel()
        }
    }
}

/**
 * Authenticates a WebSocket connection via ?token= query parameter.
 * Returns true if authenticated, false if the connection was rejected.
 */
private suspend fun DefaultWebSocketServerSession.authenticateWebSocket(expectedToken: String): Boolean {
    if (expectedToken.isBlank()) return true

    val clientToken = call.request.queryParameters["token"]
    if (clientToken == null || !NimbusApi.timingSafeEquals(clientToken, expectedToken)) {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required — provide ?token= query parameter"))
        return false
    }
    return true
}

private fun NimbusEvent.toEventMessage(): EventMessage {
    return when (this) {
        is NimbusEvent.ServiceStarting -> EventMessage(
            type = "SERVICE_STARTING",
            timestamp = timestamp.toString(),
            data = mapOf("service" to serviceName, "group" to groupName, "port" to port.toString())
        )
        is NimbusEvent.ServiceReady -> EventMessage(
            type = "SERVICE_READY",
            timestamp = timestamp.toString(),
            data = mapOf("service" to serviceName, "group" to groupName)
        )
        is NimbusEvent.ServiceStopping -> EventMessage(
            type = "SERVICE_STOPPING",
            timestamp = timestamp.toString(),
            data = mapOf("service" to serviceName)
        )
        is NimbusEvent.ServiceStopped -> EventMessage(
            type = "SERVICE_STOPPED",
            timestamp = timestamp.toString(),
            data = mapOf("service" to serviceName)
        )
        is NimbusEvent.ServiceCrashed -> EventMessage(
            type = "SERVICE_CRASHED",
            timestamp = timestamp.toString(),
            data = mapOf("service" to serviceName, "exitCode" to exitCode.toString(), "restartAttempt" to restartAttempt.toString())
        )
        is NimbusEvent.ScaleUp -> EventMessage(
            type = "SCALE_UP",
            timestamp = timestamp.toString(),
            data = mapOf("group" to groupName, "from" to currentInstances.toString(), "to" to targetInstances.toString(), "reason" to reason)
        )
        is NimbusEvent.ScaleDown -> EventMessage(
            type = "SCALE_DOWN",
            timestamp = timestamp.toString(),
            data = mapOf("group" to groupName, "service" to serviceName, "reason" to reason)
        )
        is NimbusEvent.PlayerConnected -> EventMessage(
            type = "PLAYER_CONNECTED",
            timestamp = timestamp.toString(),
            data = mapOf("player" to playerName, "service" to serviceName)
        )
        is NimbusEvent.PlayerDisconnected -> EventMessage(
            type = "PLAYER_DISCONNECTED",
            timestamp = timestamp.toString(),
            data = mapOf("player" to playerName, "service" to serviceName)
        )
        is NimbusEvent.GroupCreated -> EventMessage(
            type = "GROUP_CREATED",
            timestamp = timestamp.toString(),
            data = mapOf("group" to groupName)
        )
        is NimbusEvent.GroupUpdated -> EventMessage(
            type = "GROUP_UPDATED",
            timestamp = timestamp.toString(),
            data = mapOf("group" to groupName)
        )
        is NimbusEvent.GroupDeleted -> EventMessage(
            type = "GROUP_DELETED",
            timestamp = timestamp.toString(),
            data = mapOf("group" to groupName)
        )
        is NimbusEvent.ConfigReloaded -> EventMessage(
            type = "CONFIG_RELOADED",
            timestamp = timestamp.toString(),
            data = mapOf("groupsLoaded" to groupsLoaded.toString())
        )
        is NimbusEvent.ApiStarted -> EventMessage(
            type = "API_STARTED",
            timestamp = timestamp.toString(),
            data = mapOf("bind" to bind, "port" to port.toString())
        )
        is NimbusEvent.ApiStopped -> EventMessage(
            type = "API_STOPPED",
            timestamp = timestamp.toString(),
            data = mapOf("reason" to reason)
        )
        is NimbusEvent.ApiWarning -> EventMessage(
            type = "API_WARNING",
            timestamp = timestamp.toString(),
            data = mapOf("message" to message)
        )
        is NimbusEvent.ApiError -> EventMessage(
            type = "API_ERROR",
            timestamp = timestamp.toString(),
            data = mapOf("error" to error)
        )
    }
}
