package dev.nimbus.api.routes

import dev.nimbus.api.*
import dev.nimbus.event.EventBus
import dev.nimbus.group.GroupManager
import dev.nimbus.service.ServiceRegistry
import dev.nimbus.service.ServiceManager
import dev.nimbus.service.ServiceState
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Duration
import java.time.Instant

fun Route.serviceRoutes(
    registry: ServiceRegistry,
    serviceManager: ServiceManager,
    groupManager: GroupManager,
    eventBus: EventBus
) {
    route("/api/services") {

        // GET /api/services — List all services
        get {
            val group = call.queryParameters["group"]
            val state = call.queryParameters["state"]

            var services = if (group != null) {
                registry.getByGroup(group)
            } else {
                registry.getAll()
            }

            if (state != null) {
                val stateFilter = ServiceState.valueOf(state.uppercase())
                services = services.filter { it.state == stateFilter }
            }

            val responses = services.map { it.toResponse() }
            call.respond(ServiceListResponse(responses, responses.size))
        }

        // GET /api/services/{name} — Get service details
        get("{name}") {
            val name = call.parameters["name"]!!
            val service = registry.get(name)
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Service '$name' not found"))
            call.respond(service.toResponse())
        }

        // POST /api/services/{name}/start — Start a new instance of a group
        post("{name}/start") {
            val groupName = call.parameters["name"]!!

            if (groupManager.getGroup(groupName) == null) {
                return@post call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Group '$groupName' not found"))
            }

            val service = serviceManager.startService(groupName)
            if (service != null) {
                call.respond(HttpStatusCode.Created, ApiMessage(true, "Service '${service.name}' starting on port ${service.port}"))
            } else {
                call.respond(HttpStatusCode.Conflict, ApiMessage(false, "Failed to start service for group '$groupName' — max instances reached or JAR unavailable"))
            }
        }

        // POST /api/services/{name}/stop — Stop a service
        post("{name}/stop") {
            val name = call.parameters["name"]!!
            val service = registry.get(name)
                ?: return@post call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Service '$name' not found"))

            val stopped = serviceManager.stopService(name)
            if (stopped) {
                call.respond(ApiMessage(true, "Service '$name' stopped"))
            } else {
                call.respond(HttpStatusCode.InternalServerError, ApiMessage(false, "Failed to stop service '$name'"))
            }
        }

        // POST /api/services/{name}/restart — Restart a service
        post("{name}/restart") {
            val name = call.parameters["name"]!!
            val service = registry.get(name)
                ?: return@post call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Service '$name' not found"))

            val newService = serviceManager.restartService(name)
            if (newService != null) {
                call.respond(ApiMessage(true, "Service restarted as '${newService.name}' on port ${newService.port}"))
            } else {
                call.respond(HttpStatusCode.InternalServerError, ApiMessage(false, "Failed to restart service '$name'"))
            }
        }

        // POST /api/services/{name}/exec — Execute command on service
        post("{name}/exec") {
            val name = call.parameters["name"]!!
            registry.get(name)
                ?: return@post call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Service '$name' not found"))

            val request = call.receive<ExecRequest>()
            val success = serviceManager.executeCommand(name, request.command)
            call.respond(ExecResponse(success, name, request.command))
        }

        // GET /api/services/{name}/logs — Get recent log lines
        get("{name}/logs") {
            val name = call.parameters["name"]!!
            val service = registry.get(name)
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Service '$name' not found"))

            val logFile = service.workingDirectory.resolve("logs/latest.log")
            if (!logFile.toFile().exists()) {
                return@get call.respond(ApiMessage(true, "No log file found for '$name'"))
            }

            val lines = call.queryParameters["lines"]?.toIntOrNull() ?: 100
            val logLines = logFile.toFile().readLines().takeLast(lines)
            call.respond(mapOf("service" to name, "lines" to logLines, "total" to logLines.size))
        }
    }
}

private fun dev.nimbus.service.Service.toResponse(): ServiceResponse {
    val uptime = if (startedAt != null) {
        val duration = Duration.between(startedAt, Instant.now())
        val hours = duration.toHours()
        val minutes = duration.toMinutesPart()
        val seconds = duration.toSecondsPart()
        "${hours}h ${minutes}m ${seconds}s"
    } else null

    return ServiceResponse(
        name = name,
        groupName = groupName,
        port = port,
        state = state.name,
        pid = pid,
        playerCount = playerCount,
        startedAt = startedAt?.toString(),
        restartCount = restartCount,
        uptime = uptime
    )
}
