package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.api.ApiMessage
import dev.nimbuspowered.nimbus.api.EventMessage
import dev.nimbuspowered.nimbus.api.NimbusApi
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
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
    token: String,
    serviceToken: String = ""
) {
    // WS /api/events — Live event stream
    webSocket("/api/events") {
        if (!authenticateWebSocket(token, serviceToken)) return@webSocket

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

    // WS /api/services/{name}/console — Bidirectional console access (admin only)
    webSocket("/api/services/{name}/console") {
        if (!authenticateWebSocket(token, "")) return@webSocket

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
private suspend fun DefaultWebSocketServerSession.authenticateWebSocket(
    masterToken: String,
    serviceToken: String
): Boolean {
    if (masterToken.isBlank()) return true

    // Prefer Authorization header (hidden from logs), fall back to query param for backwards compat
    val authHeader = call.request.headers["Authorization"]
    val clientToken = when {
        authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true) ->
            authHeader.removePrefix("Bearer ").removePrefix("bearer ")
        else -> call.request.queryParameters["token"]
    }

    if (clientToken == null ||
        (!NimbusApi.timingSafeEquals(clientToken, masterToken) &&
         (serviceToken.isBlank() || !NimbusApi.timingSafeEquals(clientToken, serviceToken)))
    ) {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required — provide Authorization header or ?token= query parameter"))
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
        is NimbusEvent.ServiceDraining -> EventMessage(
            type = "SERVICE_DRAINING",
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
        is NimbusEvent.ServiceRecovered -> EventMessage(
            type = "SERVICE_RECOVERED",
            timestamp = timestamp.toString(),
            data = mapOf("service" to serviceName, "group" to groupName, "pid" to pid.toString(), "port" to port.toString())
        )
        is NimbusEvent.ServiceCustomStateChanged -> EventMessage(
            type = "SERVICE_CUSTOM_STATE_CHANGED",
            timestamp = timestamp.toString(),
            data = buildMap {
                put("service", serviceName)
                put("group", groupName)
                if (oldState != null) put("oldState", oldState)
                if (newState != null) put("newState", newState)
            }
        )
        is NimbusEvent.ServiceDeployed -> EventMessage(
            type = "SERVICE_DEPLOYED",
            timestamp = timestamp.toString(),
            data = mapOf("service" to serviceName, "group" to groupName, "filesChanged" to filesChanged.toString())
        )
        is NimbusEvent.ServicePrepared -> EventMessage(
            type = "SERVICE_PREPARED",
            timestamp = timestamp.toString(),
            data = mapOf("service" to serviceName, "group" to groupName)
        )
        is NimbusEvent.WarmPoolReplenished -> EventMessage(
            type = "WARM_POOL_REPLENISHED",
            timestamp = timestamp.toString(),
            data = mapOf("group" to groupName, "poolSize" to poolSize.toString())
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
        is NimbusEvent.PlacementBlocked -> EventMessage(
            type = "PLACEMENT_BLOCKED",
            timestamp = timestamp.toString(),
            data = mapOf("group" to groupName, "reason" to reason)
        )
        is NimbusEvent.SyncCompleted -> EventMessage(
            type = "SYNC_COMPLETED",
            timestamp = timestamp.toString(),
            data = mapOf(
                "service" to serviceName,
                "filesInManifest" to filesInManifest.toString(),
                "filesReceived" to filesReceived.toString(),
                "bytesReceived" to bytesReceived.toString(),
                "durationMs" to durationMs.toString()
            )
        )
        is NimbusEvent.SyncFailed -> EventMessage(
            type = "SYNC_FAILED",
            timestamp = timestamp.toString(),
            data = mapOf("service" to serviceName, "reason" to reason)
        )
        is NimbusEvent.PlayerConnected -> EventMessage(
            type = "PLAYER_CONNECTED",
            timestamp = timestamp.toString(),
            data = mapOf("player" to playerName, "uuid" to uuid, "service" to serviceName)
        )
        is NimbusEvent.PlayerServerSwitch -> EventMessage(
            type = "PLAYER_SERVER_SWITCH",
            timestamp = timestamp.toString(),
            data = mapOf("player" to playerName, "uuid" to uuid, "from" to fromService, "to" to toService)
        )
        is NimbusEvent.PlayerDisconnected -> EventMessage(
            type = "PLAYER_DISCONNECTED",
            timestamp = timestamp.toString(),
            data = mapOf("player" to playerName, "uuid" to uuid, "service" to serviceName)
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
        is NimbusEvent.ServiceMessage -> EventMessage(
            type = "SERVICE_MESSAGE",
            timestamp = timestamp.toString(),
            data = buildMap {
                put("from", fromService)
                put("to", toService)
                put("channel", channel)
                putAll(data)
            }
        )
        is NimbusEvent.ModuleEvent -> EventMessage(
            type = type,
            timestamp = timestamp.toString(),
            data = data
        )
        is NimbusEvent.TabListUpdated -> EventMessage(
            type = "TABLIST_UPDATED",
            timestamp = timestamp.toString(),
            data = mapOf("header" to header, "footer" to footer, "playerFormat" to playerFormat, "updateInterval" to updateInterval.toString())
        )
        is NimbusEvent.MotdUpdated -> EventMessage(
            type = "MOTD_UPDATED",
            timestamp = timestamp.toString(),
            data = mapOf("line1" to line1, "line2" to line2, "maxPlayers" to maxPlayers.toString(), "playerCountOffset" to playerCountOffset.toString())
        )
        is NimbusEvent.PlayerTabUpdated -> EventMessage(
            type = "PLAYER_TAB_UPDATED",
            timestamp = timestamp.toString(),
            data = buildMap {
                put("uuid", uuid)
                if (format != null) put("format", format)
            }
        )
        is NimbusEvent.ChatFormatUpdated -> EventMessage(
            type = "CHAT_FORMAT_UPDATED",
            timestamp = timestamp.toString(),
            data = mapOf("format" to format, "enabled" to enabled.toString())
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
        is NimbusEvent.ProxyUpdateAvailable -> EventMessage(
            type = "PROXY_UPDATE_AVAILABLE",
            timestamp = timestamp.toString(),
            data = mapOf("currentVersion" to currentVersion, "newVersion" to newVersion)
        )
        is NimbusEvent.ProxyUpdateApplied -> EventMessage(
            type = "PROXY_UPDATE_APPLIED",
            timestamp = timestamp.toString(),
            data = mapOf("oldVersion" to oldVersion, "newVersion" to newVersion)
        )
        is NimbusEvent.NimbusUpdateAvailable -> EventMessage(
            type = "NIMBUS_UPDATE_AVAILABLE",
            timestamp = timestamp.toString(),
            data = mapOf("currentVersion" to currentVersion, "newVersion" to newVersion, "updateType" to updateType)
        )
        is NimbusEvent.NimbusUpdateApplied -> EventMessage(
            type = "NIMBUS_UPDATE_APPLIED",
            timestamp = timestamp.toString(),
            data = mapOf("oldVersion" to oldVersion, "newVersion" to newVersion)
        )
        is NimbusEvent.ClusterStarted -> EventMessage(
            type = "CLUSTER_STARTED",
            timestamp = timestamp.toString(),
            data = mapOf("bind" to bind, "port" to port.toString(), "strategy" to strategy)
        )
        is NimbusEvent.NodeConnected -> EventMessage(
            type = "NODE_CONNECTED",
            timestamp = timestamp.toString(),
            data = mapOf("nodeId" to nodeId, "host" to host)
        )
        is NimbusEvent.NodeDisconnected -> EventMessage(
            type = "NODE_DISCONNECTED",
            timestamp = timestamp.toString(),
            data = mapOf("nodeId" to nodeId)
        )
        is NimbusEvent.NodeHeartbeat -> EventMessage(
            type = "NODE_HEARTBEAT",
            timestamp = timestamp.toString(),
            data = mapOf("nodeId" to nodeId, "cpuUsage" to cpuUsage.toString(), "services" to services.toString())
        )
        is NimbusEvent.LoadBalancerStarted -> EventMessage(
            type = "LOAD_BALANCER_STARTED",
            timestamp = timestamp.toString(),
            data = mapOf("bind" to bind, "port" to port.toString(), "strategy" to strategy)
        )
        is NimbusEvent.LoadBalancerStopped -> EventMessage(
            type = "LOAD_BALANCER_STOPPED",
            timestamp = timestamp.toString(),
            data = mapOf("reason" to reason)
        )
        is NimbusEvent.LoadBalancerBackendHealthChanged -> EventMessage(
            type = "LOAD_BALANCER_BACKEND_HEALTH_CHANGED",
            timestamp = timestamp.toString(),
            data = mapOf("host" to host, "port" to port.toString(), "oldStatus" to oldStatus, "newStatus" to newStatus)
        )
        is NimbusEvent.StressTestUpdated -> EventMessage(
            type = "STRESS_TEST_UPDATED",
            timestamp = timestamp.toString(),
            data = buildMap {
                put("simulatedPlayers", simulatedPlayers.toString())
                put("targetPlayers", targetPlayers.toString())
                if (targetGroup != null) put("targetGroup", targetGroup)
                if (perService.isNotEmpty()) put("perService", perService.entries.joinToString(",") { "${it.key}=${it.value}" })
            }
        )
        is NimbusEvent.MaintenanceEnabled -> EventMessage(
            type = "MAINTENANCE_ENABLED",
            timestamp = timestamp.toString(),
            data = buildMap {
                put("scope", scope)
                if (reason.isNotEmpty()) put("reason", reason)
            }
        )
        is NimbusEvent.MaintenanceDisabled -> EventMessage(
            type = "MAINTENANCE_DISABLED",
            timestamp = timestamp.toString(),
            data = mapOf("scope" to scope)
        )
        is NimbusEvent.ModuleLoaded -> EventMessage(
            type = "MODULE_LOADED",
            timestamp = timestamp.toString(),
            data = mapOf("moduleId" to moduleId, "moduleName" to moduleName, "moduleVersion" to moduleVersion)
        )
        is NimbusEvent.ModuleEnabled -> EventMessage(
            type = "MODULE_ENABLED",
            timestamp = timestamp.toString(),
            data = mapOf("moduleId" to moduleId, "moduleName" to moduleName)
        )
        is NimbusEvent.ModuleDisabled -> EventMessage(
            type = "MODULE_DISABLED",
            timestamp = timestamp.toString(),
            data = mapOf("moduleId" to moduleId, "moduleName" to moduleName)
        )
        is NimbusEvent.CliSessionConnected -> EventMessage(
            type = "CLI_SESSION_CONNECTED",
            timestamp = timestamp.toString(),
            data = mapOf(
                "sessionId" to sessionId.toString(), "remoteIp" to remoteIp,
                "clientUsername" to clientUsername, "clientHostname" to clientHostname,
                "clientOs" to clientOs, "location" to location
            )
        )
        is NimbusEvent.CliSessionDisconnected -> EventMessage(
            type = "CLI_SESSION_DISCONNECTED",
            timestamp = timestamp.toString(),
            data = mapOf(
                "sessionId" to sessionId.toString(), "remoteIp" to remoteIp,
                "clientUsername" to clientUsername, "durationSeconds" to durationSeconds.toString(),
                "commandCount" to commandCount.toString()
            )
        )
        is NimbusEvent.DedicatedCreated -> EventMessage(
            type = "DEDICATED_CREATED",
            timestamp = timestamp.toString(),
            data = mapOf("name" to name)
        )
        is NimbusEvent.DedicatedDeleted -> EventMessage(
            type = "DEDICATED_DELETED",
            timestamp = timestamp.toString(),
            data = mapOf("name" to name)
        )
    }
}
