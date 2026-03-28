package dev.nimbus.api.routes

import dev.nimbus.api.*
import dev.nimbus.config.NimbusConfig
import dev.nimbus.config.ServerSoftware
import dev.nimbus.group.GroupManager
import dev.nimbus.service.ServerListPing
import dev.nimbus.service.ServiceManager
import dev.nimbus.service.ServiceRegistry
import dev.nimbus.service.ServiceState
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Duration
import java.time.Instant

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

        call.respond(StatusResponse(
            networkName = config.network.name,
            online = allServices.any { it.state == ServiceState.READY },
            uptimeSeconds = uptime,
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
        val request = call.receive<SendPlayerRequest>()

        // Find the Velocity proxy to execute the send command
        val proxyService = registry.getAll().firstOrNull { service ->
            val group = groupManager.getGroup(service.groupName)
            group?.config?.group?.software == ServerSoftware.VELOCITY && service.state == ServiceState.READY
        }

        if (proxyService == null) {
            return@post call.respond(HttpStatusCode.ServiceUnavailable, ApiMessage(false, "No Velocity proxy available"))
        }

        // Send player via Velocity's /send command
        val success = serviceManager.executeCommand(proxyService.name, "send $playerName ${request.targetService}")
        if (success) {
            call.respond(ApiMessage(true, "Player '$playerName' sent to '${request.targetService}'"))
        } else {
            call.respond(HttpStatusCode.InternalServerError, ApiMessage(false, "Failed to send player transfer command"))
        }
    }
}
