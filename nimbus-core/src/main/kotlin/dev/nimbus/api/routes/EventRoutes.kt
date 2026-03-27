package dev.nimbus.api.routes

import dev.nimbus.api.EventMessage
import dev.nimbus.event.EventBus
import dev.nimbus.event.NimbusEvent
import dev.nimbus.service.ServiceManager
import dev.nimbus.service.ServiceRegistry
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = true }

fun Route.eventRoutes(
    eventBus: EventBus,
    registry: ServiceRegistry,
    serviceManager: ServiceManager
) {
    // WS /api/events — Live event stream
    webSocket("/api/events") {
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
    }
}
