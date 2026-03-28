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
import java.io.RandomAccessFile
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
            val stateParam = call.queryParameters["state"]

            var services = if (group != null) {
                registry.getByGroup(group)
            } else {
                registry.getAll()
            }

            if (stateParam != null) {
                val stateFilter = try {
                    ServiceState.valueOf(stateParam.uppercase())
                } catch (_: IllegalArgumentException) {
                    return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ApiMessage(false, "Invalid state: '$stateParam'. Valid: ${ServiceState.entries.joinToString()}")
                    )
                }
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
            registry.get(name)
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
            registry.get(name)
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

        // GET /api/services/{name}/logs — Get recent log lines (tail-read, not full file)
        get("{name}/logs") {
            val name = call.parameters["name"]!!
            val service = registry.get(name)
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Service '$name' not found"))

            val logFile = service.workingDirectory.resolve("logs/latest.log").toFile()
            if (!logFile.exists()) {
                return@get call.respond(LogsResponse(name, emptyList(), 0))
            }

            val requestedLines = (call.queryParameters["lines"]?.toIntOrNull() ?: 100).coerceIn(1, 1000)
            val logLines = tailFile(logFile, requestedLines)
            call.respond(LogsResponse(name, logLines, logLines.size))
        }
    }
}

/**
 * Efficiently reads the last N lines from a file using reverse seeking.
 */
private fun tailFile(file: java.io.File, lines: Int): List<String> {
    if (file.length() == 0L) return emptyList()

    RandomAccessFile(file, "r").use { raf ->
        val fileLength = raf.length()
        var pos = fileLength - 1
        var lineCount = 0

        // Scan backwards to find enough newlines
        while (pos > 0 && lineCount <= lines) {
            raf.seek(pos)
            if (raf.readByte().toInt().toChar() == '\n') {
                lineCount++
            }
            pos--
        }

        // Position to start reading
        if (pos == 0L) {
            raf.seek(0)
        } else {
            raf.seek(pos + 2) // Skip past the newline we stopped on
        }

        val result = mutableListOf<String>()
        var line = raf.readLine()
        while (line != null) {
            result.add(line)
            line = raf.readLine()
        }
        return result.takeLast(lines)
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
