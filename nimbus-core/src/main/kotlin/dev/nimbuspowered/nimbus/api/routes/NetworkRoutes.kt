package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.api.*
import dev.nimbuspowered.nimbus.api.ApiError
import dev.nimbuspowered.nimbus.api.apiError
import dev.nimbuspowered.nimbus.config.NimbusConfig
import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.service.ServerListPing
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceState
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Duration
import java.time.Instant

private val MINECRAFT_NAME = Regex("^[a-zA-Z0-9_]{1,16}$")

fun Route.networkRoutes(
    config: NimbusConfig,
    registry: ServiceRegistry,
    groupManager: GroupManager,
    serviceManager: ServiceManager,
    startedAt: Instant
) {
    // GET /api/status — Full cluster overview
    get("/api/status") {
        val allServices = registry.getAll()
        val uptime = Duration.between(startedAt, Instant.now()).seconds

        val groupStatuses = groupManager.getAllGroups().map { group ->
            val services = registry.getByGroup(group.name)
            val readyServices = services.filter { it.state == ServiceState.READY }
            val totalPlayers = readyServices.sumOf { it.playerCount }
            val maxPlayers = readyServices.size * group.config.group.resources.maxPlayers

            GroupStatusResponse(
                name = group.name,
                instances = services.size,
                maxInstances = group.maxInstances,
                players = totalPlayers,
                maxPlayers = maxPlayers,
                software = group.config.group.software.name,
                version = group.config.group.version
            )
        }

        val uptimeDuration = Duration.between(startedAt, Instant.now())
        val uptimeHuman = buildString {
            val h = uptimeDuration.toHours()
            val m = uptimeDuration.toMinutesPart()
            val s = uptimeDuration.toSecondsPart()
            if (h > 0) append("${h}h ")
            if (h > 0 || m > 0) append("${m}m ")
            append("${s}s")
        }

        call.respond(StatusResponse(
            networkName = config.network.name,
            online = allServices.any { it.state == ServiceState.READY },
            uptimeSeconds = uptime,
            uptimeHuman = uptimeHuman,
            totalServices = allServices.size,
            totalPlayers = allServices.sumOf { it.playerCount },
            groups = groupStatuses
        ))
    }

    // GET /api/players — List all connected players (pings in parallel)
    get("/api/players") {
        val readyServices = registry.getAll().filter { it.state == ServiceState.READY }

        val allPlayers = coroutineScope {
            readyServices.map { service ->
                async {
                    val result = ServerListPing.ping("127.0.0.1", service.port, timeout = 3000)
                    if (result != null) {
                        service.playerCount = result.onlinePlayers
                        result.playerNames.map { PlayerInfo(it, service.name) }
                    } else {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        call.respond(PlayersResponse(allPlayers, allPlayers.size))
    }

    // POST /api/players/{name}/send — Transfer player to another service
    post("/api/players/{name}/send") {
        val playerName = call.parameters["name"]!!
        if (!MINECRAFT_NAME.matches(playerName)) {
            return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid player name", ApiError.VALIDATION_FAILED))
        }
        val request = call.receive<SendPlayerRequest>()

        // Find the Velocity proxy to execute the send command
        val proxyService = registry.getAll().firstOrNull { service ->
            val group = groupManager.getGroup(service.groupName)
            group?.config?.group?.software == ServerSoftware.VELOCITY && service.state == ServiceState.READY
        }

        if (proxyService == null) {
            return@post call.respond(HttpStatusCode.ServiceUnavailable, apiError("No Velocity proxy available", ApiError.PROXY_NOT_AVAILABLE))
        }

        // Send player via Velocity's /send command
        val success = serviceManager.executeCommand(proxyService.name, "send $playerName ${request.targetService}")
        if (success) {
            call.respond(ApiMessage(true, "Player '$playerName' sent to '${request.targetService}'"))
        } else {
            call.respond(HttpStatusCode.InternalServerError, apiError("Failed to send player transfer command", ApiError.INTERNAL_ERROR))
        }
    }

    // POST /api/players/{name}/kick — Kick player from the network
    post("/api/players/{name}/kick") {
        val playerName = call.parameters["name"]!!
        if (!MINECRAFT_NAME.matches(playerName)) {
            return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid player name", ApiError.VALIDATION_FAILED))
        }
        val request = call.receive<KickPlayerRequest>()

        val proxyService = registry.getAll().firstOrNull { service ->
            val group = groupManager.getGroup(service.groupName)
            group?.config?.group?.software == ServerSoftware.VELOCITY && service.state == ServiceState.READY
        }

        if (proxyService == null) {
            return@post call.respond(HttpStatusCode.ServiceUnavailable, apiError("No Velocity proxy available", ApiError.PROXY_NOT_AVAILABLE))
        }

        // Velocity's /velocity kick command: velocity kick <player> <reason>
        val success = serviceManager.executeCommand(proxyService.name, "velocity kick $playerName ${request.reason}")
        if (success) {
            call.respond(ApiMessage(true, "Player '$playerName' kicked from the network"))
        } else {
            call.respond(HttpStatusCode.InternalServerError, apiError("Failed to execute kick command", ApiError.INTERNAL_ERROR))
        }
    }

    // POST /api/broadcast — Broadcast a message to all services (or a specific group)
    post("/api/broadcast") {
        val request = call.receive<BroadcastRequest>()

        val targetServices = if (request.group != null) {
            registry.getByGroup(request.group).filter { it.state == ServiceState.READY }
        } else {
            registry.getAll().filter { it.state == ServiceState.READY }
        }

        if (targetServices.isEmpty()) {
            val scope = request.group?.let { "group '${it}'" } ?: "network"
            return@post call.respond(HttpStatusCode.NotFound, apiError("No ready services found in $scope", ApiError.SERVICE_NOT_FOUND))
        }

        var successCount = 0
        for (service in targetServices) {
            // Use Minecraft's /say command for backend servers, alertraw for proxies
            val group = groupManager.getGroup(service.groupName)
            val command = if (group?.config?.group?.software == ServerSoftware.VELOCITY) {
                "velocity broadcast ${request.message}"
            } else {
                "say ${request.message}"
            }
            if (serviceManager.executeCommand(service.name, command)) {
                successCount++
            }
        }

        val scope = request.group?.let { "group '${it}'" } ?: "network"
        call.respond(BroadcastResponse(
            success = successCount > 0,
            message = "Broadcast sent to $successCount/${targetServices.size} services in $scope",
            services = successCount
        ))
    }
}
