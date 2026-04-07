package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.api.*
import dev.nimbuspowered.nimbus.api.ApiErrors
import dev.nimbuspowered.nimbus.api.apiError
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceState
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
                        apiError("Invalid state: '$stateParam'. Valid: ${ServiceState.entries.joinToString()}", ApiErrors.INVALID_INPUT)
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

        // GET /api/services/health — Aggregated health summary
        get("health") {
            val allServices = registry.getAll()
            val readyServices = allServices.filter { it.state == ServiceState.READY }
            val healthyCount = readyServices.count { it.healthy }
            val unhealthyCount = readyServices.size - healthyCount

            val avgTps = if (readyServices.isNotEmpty()) {
                readyServices.sumOf { it.tps } / readyServices.size
            } else 0.0

            val totalUsedMb = allServices.sumOf { it.memoryUsedMb }
            val totalMaxMb = allServices.sumOf { it.memoryMaxMb }
            val memPct = if (totalMaxMb > 0) totalUsedMb.toDouble() / totalMaxMb * 100 else 0.0

            val entries = allServices.sortedBy { it.name }.map { svc ->
                val uptimeSec = svc.startedAt?.let { Duration.between(it, Instant.now()).seconds }
                ServiceHealthEntry(
                    name = svc.name,
                    groupName = svc.groupName,
                    state = svc.state.name,
                    tps = svc.tps,
                    memoryUsedMb = svc.memoryUsedMb,
                    memoryMaxMb = svc.memoryMaxMb,
                    healthy = svc.healthy,
                    restartCount = svc.restartCount,
                    uptimeSeconds = uptimeSec
                )
            }

            call.respond(ServiceHealthSummaryResponse(
                totalServices = allServices.size,
                readyServices = readyServices.size,
                healthyServices = healthyCount,
                unhealthyServices = unhealthyCount,
                averageTps = Math.round(avgTps * 100.0) / 100.0,
                totalMemoryUsedMb = totalUsedMb,
                totalMemoryMaxMb = totalMaxMb,
                memoryUsagePercent = Math.round(memPct * 100.0) / 100.0,
                services = entries
            ))
        }

        // GET /api/services/{name} — Get service details
        get("{name}") {
            val name = call.parameters["name"]!!
            val service = registry.get(name)
                ?: return@get call.respond(HttpStatusCode.NotFound, apiError("Service '$name' not found", ApiErrors.SERVICE_NOT_FOUND))
            call.respond(service.toResponse())
        }

        // POST /api/services/{name}/start — Start a new instance of a group
        post("{name}/start") {
            val groupName = call.parameters["name"]!!

            if (groupManager.getGroup(groupName) == null) {
                return@post call.respond(HttpStatusCode.NotFound, apiError("Group '$groupName' not found", ApiErrors.GROUP_NOT_FOUND))
            }

            val service = serviceManager.startService(groupName)
            if (service != null) {
                call.respond(HttpStatusCode.Created, ApiMessage(true, "Service '${service.name}' starting on port ${service.port}"))
            } else {
                call.respond(HttpStatusCode.Conflict, apiError("Failed to start service for group '$groupName' — max instances reached or JAR unavailable", ApiErrors.SERVICE_START_FAILED))
            }
        }

        // POST /api/services/{name}/stop — Stop a service
        post("{name}/stop") {
            val name = call.parameters["name"]!!
            registry.get(name)
                ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Service '$name' not found", ApiErrors.SERVICE_NOT_FOUND))

            val stopped = serviceManager.stopService(name)
            if (stopped) {
                call.respond(ApiMessage(true, "Service '$name' stopped"))
            } else {
                call.respond(HttpStatusCode.InternalServerError, apiError("Failed to stop service '$name'", ApiErrors.SERVICE_STOP_FAILED))
            }
        }

        // POST /api/services/{name}/restart — Restart a service
        post("{name}/restart") {
            val name = call.parameters["name"]!!
            registry.get(name)
                ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Service '$name' not found", ApiErrors.SERVICE_NOT_FOUND))

            val newService = serviceManager.restartService(name)
            if (newService != null) {
                call.respond(ApiMessage(true, "Service restarted as '${newService.name}' on port ${newService.port}"))
            } else {
                call.respond(HttpStatusCode.InternalServerError, apiError("Failed to restart service '$name'", ApiErrors.SERVICE_RESTART_FAILED))
            }
        }

        // POST /api/services/{name}/exec — Execute command on service
        post("{name}/exec") {
            val name = call.parameters["name"]!!
            registry.get(name)
                ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Service '$name' not found", ApiErrors.SERVICE_NOT_FOUND))

            val request = call.receive<ExecRequest>()
            val success = serviceManager.executeCommand(name, request.command)
            call.respond(ExecResponse(success, name, request.command))
        }

        // PUT /api/services/{name}/state — Set custom state (used by plugins via SDK)
        put("{name}/state") {
            val name = call.parameters["name"]!!
            val service = registry.get(name)
                ?: return@put call.respond(HttpStatusCode.NotFound, apiError("Service '$name' not found", ApiErrors.SERVICE_NOT_FOUND))

            if (service.state != ServiceState.READY) {
                return@put call.respond(HttpStatusCode.Conflict, apiError("Service '$name' is not READY (current: ${service.state})", ApiErrors.SERVICE_NOT_READY))
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
                ?: return@get call.respond(HttpStatusCode.NotFound, apiError("Service '$name' not found", ApiErrors.SERVICE_NOT_FOUND))

            call.respond(CustomStateResponse(name, service.customState))
        }

        // PUT /api/services/{name}/health — Report TPS + memory (used by SDK on backend servers)
        put("{name}/health") {
            val name = call.parameters["name"]!!
            val service = registry.get(name)
                ?: return@put call.respond(HttpStatusCode.NotFound, apiError("Service '$name' not found", ApiErrors.SERVICE_NOT_FOUND))

            val request = call.receive<ReportHealthRequest>()
            service.updateHealth(request.tps, request.memoryUsedMb, request.memoryMaxMb)

            call.respond(HealthReportResponse(name, service.healthy))
        }

        // PUT /api/services/{name}/players — Report player count (used by SDK on backend servers)
        put("{name}/players") {
            val name = call.parameters["name"]!!
            val service = registry.get(name)
                ?: return@put call.respond(HttpStatusCode.NotFound, apiError("Service '$name' not found", ApiErrors.SERVICE_NOT_FOUND))

            val request = call.receive<ReportPlayerCountRequest>()
            service.playerCount = request.playerCount
            service.lastPlayerCountUpdate = java.time.Instant.now()

            call.respond(PlayerCountResponse(name, service.playerCount))
        }

        // GET /api/services/{name}/logs — Get recent log lines (tail-read, not full file)
        get("{name}/logs") {
            val name = call.parameters["name"]!!
            val service = registry.get(name)
                ?: return@get call.respond(HttpStatusCode.NotFound, apiError("Service '$name' not found", ApiErrors.SERVICE_NOT_FOUND))

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

            // "controller" is a virtual target — messages are emitted directly to the EventBus
            // without requiring a registered service entry (the controller itself isn't a service)
            if (targetName != "controller") {
                registry.get(targetName)
                    ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Service '$targetName' not found", ApiErrors.SERVICE_NOT_FOUND))
            }

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

private fun dev.nimbuspowered.nimbus.service.Service.toResponse(): ServiceResponse {
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
        bedrockPort = bedrockPort,
        tps = tps,
        memoryUsedMb = memoryUsedMb,
        memoryMaxMb = memoryMaxMb,
        healthy = healthy
    )
}
