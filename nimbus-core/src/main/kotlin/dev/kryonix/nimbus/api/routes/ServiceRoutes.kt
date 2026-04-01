package dev.kryonix.nimbus.api.routes

import dev.kryonix.nimbus.api.*
import dev.kryonix.nimbus.event.EventBus
import dev.kryonix.nimbus.event.NimbusEvent
import dev.kryonix.nimbus.group.GroupManager
import dev.kryonix.nimbus.service.ServiceRegistry
import dev.kryonix.nimbus.service.ServiceManager
import dev.kryonix.nimbus.service.ServiceState
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

            val customStateParam = call.queryParameters["customState"]
            if (customStateParam != null) {
                services = services.filter { it.customState.equals(customStateParam, ignoreCase = true) }
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

        // PUT /api/services/{name}/state — Set custom state (used by plugins via SDK)
        put("{name}/state") {
            val name = call.parameters["name"]!!
            val service = registry.get(name)
                ?: return@put call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Service '$name' not found"))

            if (service.state != ServiceState.READY) {
                return@put call.respond(HttpStatusCode.Conflict, ApiMessage(false, "Service '$name' is not READY (current: ${service.state})"))
            }

            val request = call.receive<SetCustomStateRequest>()
            val oldState = service.customState
            service.customState = request.customState

            eventBus.emit(
                NimbusEvent.ServiceCustomStateChanged(
                    serviceName = name,
                    groupName = service.groupName,
                    oldState = oldState,
                    newState = request.customState
                )
            )

            call.respond(CustomStateResponse(name, service.customState))
        }

        // GET /api/services/{name}/state — Get custom state
        get("{name}/state") {
            val name = call.parameters["name"]!!
            val service = registry.get(name)
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Service '$name' not found"))

            call.respond(CustomStateResponse(name, service.customState))
        }

        // PUT /api/services/{name}/players — Report player count (used by SDK on backend servers)
        put("{name}/players") {
            val name = call.parameters["name"]!!
            val service = registry.get(name)
                ?: return@put call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Service '$name' not found"))

            val request = call.receive<ReportPlayerCountRequest>()
            service.playerCount = request.playerCount
            service.lastPlayerCountUpdate = java.time.Instant.now()

            call.respond(PlayerCountResponse(name, service.playerCount))
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

        // POST /api/services/{name}/message — Send a message to a service (service-to-service messaging)
        post("{name}/message") {
            val targetName = call.parameters["name"]!!
            registry.get(targetName)
                ?: return@post call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Service '$targetName' not found"))

            val request = call.receive<SendMessageRequest>()

            eventBus.emit(
                NimbusEvent.ServiceMessage(
                    fromService = request.from,
                    toService = targetName,
                    channel = request.channel,
                    data = request.data
                )
            )

            call.respond(ApiMessage(true, "Message sent to '$targetName' on channel '${request.channel}'"))
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

private fun dev.kryonix.nimbus.service.Service.toResponse(): ServiceResponse {
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
        host = host,
        nodeId = nodeId,
        state = state.name,
        customState = customState,
        pid = pid,
        playerCount = playerCount,
        startedAt = startedAt?.toString(),
        restartCount = restartCount,
        uptime = uptime,
        isStatic = isStatic,
        bedrockPort = bedrockPort
    )
}
