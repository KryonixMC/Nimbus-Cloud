package dev.kryonix.nimbus.api.routes

import dev.kryonix.nimbus.api.*
import dev.kryonix.nimbus.api.ApiErrors
import dev.kryonix.nimbus.api.apiError
import dev.kryonix.nimbus.config.GroupConfig
import dev.kryonix.nimbus.config.GroupDefinition
import dev.kryonix.nimbus.config.GroupType
import dev.kryonix.nimbus.config.JvmConfig
import dev.kryonix.nimbus.config.LifecycleConfig
import dev.kryonix.nimbus.config.ResourcesConfig
import dev.kryonix.nimbus.config.ScalingConfig
import dev.kryonix.nimbus.config.ServerSoftware
import dev.kryonix.nimbus.event.EventBus
import dev.kryonix.nimbus.event.NimbusEvent
import dev.kryonix.nimbus.group.GroupManager
import dev.kryonix.nimbus.group.ServerGroup
import dev.kryonix.nimbus.service.ServiceRegistry
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

private val VALID_NAME = Regex("^[a-zA-Z0-9_-]{1,64}$")

fun Route.groupRoutes(
    registry: ServiceRegistry,
    groupManager: GroupManager,
    groupsDir: Path,
    eventBus: EventBus
) {
    route("/api/groups") {

        // GET /api/groups — List all groups
        get {
            val groups = groupManager.getAllGroups().map { it.toResponse(registry) }
            call.respond(GroupListResponse(groups, groups.size))
        }

        // GET /api/groups/{name} — Get group details
        get("{name}") {
            val name = call.parameters["name"]!!
            val group = groupManager.getGroup(name)
                ?: return@get call.respond(HttpStatusCode.NotFound, apiError("Group '$name' not found", ApiErrors.GROUP_NOT_FOUND))
            call.respond(group.toResponse(registry))
        }

        // POST /api/groups — Create a new group
        post {
            val request = call.receive<CreateGroupRequest>()

            val errors = validateGroupRequest(request)
            if (errors.isNotEmpty()) {
                return@post call.respond(HttpStatusCode.BadRequest, apiError(errors.joinToString("; "), ApiErrors.VALIDATION_FAILED))
            }

            if (groupManager.getGroup(request.name) != null) {
                return@post call.respond(HttpStatusCode.Conflict, apiError("Group '${request.name}' already exists", ApiErrors.GROUP_ALREADY_EXISTS))
            }

            val software = ServerSoftware.valueOf(request.software.uppercase())
            val groupType = GroupType.valueOf(request.type.uppercase())

            val toml = buildGroupToml(request, groupType, software)
            val configFile = groupsDir.resolve("${request.name.lowercase()}.toml")
            configFile.writeText(toml)

            val groupConfig = buildGroupConfig(request, groupType, software)
            groupManager.reloadGroups(
                groupManager.getAllGroups().map { it.config } + groupConfig
            )

            eventBus.emit(NimbusEvent.GroupCreated(request.name))
            call.respond(HttpStatusCode.Created, ApiMessage(true, "Group '${request.name}' created"))
        }

        // PUT /api/groups/{name} — Update a group config
        put("{name}") {
            val name = call.parameters["name"]!!
            groupManager.getGroup(name)
                ?: return@put call.respond(HttpStatusCode.NotFound, apiError("Group '$name' not found", ApiErrors.GROUP_NOT_FOUND))

            val request = call.receive<CreateGroupRequest>()

            val errors = validateGroupRequest(request)
            if (errors.isNotEmpty()) {
                return@put call.respond(HttpStatusCode.BadRequest, apiError(errors.joinToString("; "), ApiErrors.VALIDATION_FAILED))
            }

            val software = ServerSoftware.valueOf(request.software.uppercase())
            val groupType = GroupType.valueOf(request.type.uppercase())

            val toml = buildGroupToml(request, groupType, software)
            val configFile = groupsDir.resolve("${name.lowercase()}.toml")
            configFile.writeText(toml)

            val updatedConfig = buildGroupConfig(request, groupType, software)
            val otherGroups = groupManager.getAllGroups()
                .filter { it.name != name }
                .map { it.config }
            groupManager.reloadGroups(otherGroups + updatedConfig)

            eventBus.emit(NimbusEvent.GroupUpdated(name))
            call.respond(ApiMessage(true, "Group '$name' updated"))
        }

        // DELETE /api/groups/{name} — Delete a group
        delete("{name}") {
            val name = call.parameters["name"]!!
            groupManager.getGroup(name)
                ?: return@delete call.respond(HttpStatusCode.NotFound, apiError("Group '$name' not found", ApiErrors.GROUP_NOT_FOUND))

            val running = registry.getByGroup(name)
            if (running.isNotEmpty()) {
                return@delete call.respond(
                    HttpStatusCode.Conflict,
                    apiError("Group '$name' has ${running.size} running instance(s). Stop them first.", ApiErrors.GROUP_HAS_RUNNING_INSTANCES)
                )
            }

            val configFile = groupsDir.resolve("${name.lowercase()}.toml")
            configFile.deleteIfExists()

            val remainingGroups = groupManager.getAllGroups()
                .filter { it.name != name }
                .map { it.config }
            groupManager.reloadGroups(remainingGroups)

            eventBus.emit(NimbusEvent.GroupDeleted(name))
            call.respond(ApiMessage(true, "Group '$name' deleted"))
        }
    }
}

/**
 * Validates a CreateGroupRequest and returns a list of error messages (empty = valid).
 */
private fun validateGroupRequest(request: CreateGroupRequest): List<String> {
    val errors = mutableListOf<String>()

    if (!VALID_NAME.matches(request.name)) {
        errors += "Invalid name '${request.name}' — only alphanumeric, dash and underscore allowed (max 64 chars)"
    }
    if (!VALID_NAME.matches(request.template)) {
        errors += "Invalid template '${request.template}' — only alphanumeric, dash and underscore allowed"
    }

    // Validate enums
    try { ServerSoftware.valueOf(request.software.uppercase()) }
    catch (_: IllegalArgumentException) { errors += "Invalid software '${request.software}'. Valid: ${ServerSoftware.entries.joinToString()}" }

    try { GroupType.valueOf(request.type.uppercase()) }
    catch (_: IllegalArgumentException) { errors += "Invalid type '${request.type}'. Valid: ${GroupType.entries.joinToString()}" }

    // Validate memory format
    if (!request.memory.matches(Regex("^\\d+[MmGg]$"))) {
        errors += "Invalid memory format '${request.memory}' — expected e.g. '512M' or '2G'"
    }

    // Validate version format
    if (!request.version.matches(Regex("^\\d+\\.\\d+(\\.\\d+)?(-.*)?$"))) {
        errors += "Invalid version '${request.version}' — expected e.g. '1.21.4'"
    }

    // Range checks
    if (request.maxPlayers < 1) errors += "max_players must be >= 1"
    if (request.minInstances < 0) errors += "min_instances must be >= 0"
    if (request.maxInstances < 1) errors += "max_instances must be >= 1"
    if (request.minInstances > request.maxInstances) errors += "min_instances must be <= max_instances"
    if (request.playersPerInstance < 1) errors += "players_per_instance must be >= 1"
    if (request.scaleThreshold < 0.0 || request.scaleThreshold > 1.0) errors += "scale_threshold must be between 0.0 and 1.0"
    if (request.idleTimeout < 0) errors += "idle_timeout must be >= 0"
    if (request.maxRestarts < 0) errors += "max_restarts must be >= 0"

    return errors
}

/**
 * Builds a TOML string with proper escaping to prevent injection.
 */
private fun buildGroupToml(request: CreateGroupRequest, groupType: GroupType, software: ServerSoftware): String {
    return buildString {
        appendLine("[group]")
        appendLine("name = ${tomlString(request.name)}")
        appendLine("type = ${tomlString(groupType.name)}")
        appendLine("template = ${tomlString(request.template)}")
        appendLine("software = ${tomlString(software.name)}")
        appendLine("version = ${tomlString(request.version)}")
        if (request.modloaderVersion.isNotEmpty()) appendLine("modloader_version = ${tomlString(request.modloaderVersion)}")
        if (request.jarName.isNotEmpty()) appendLine("jar_name = ${tomlString(request.jarName)}")
        if (request.readyPattern.isNotEmpty()) appendLine("ready_pattern = ${tomlString(request.readyPattern)}")
        appendLine()
        appendLine("[group.resources]")
        appendLine("memory = ${tomlString(request.memory)}")
        appendLine("max_players = ${request.maxPlayers}")
        appendLine()
        appendLine("[group.scaling]")
        appendLine("min_instances = ${request.minInstances}")
        appendLine("max_instances = ${request.maxInstances}")
        appendLine("players_per_instance = ${request.playersPerInstance}")
        appendLine("scale_threshold = ${request.scaleThreshold}")
        appendLine("idle_timeout = ${request.idleTimeout}")
        appendLine()
        appendLine("[group.lifecycle]")
        appendLine("stop_on_empty = ${request.stopOnEmpty}")
        appendLine("restart_on_crash = ${request.restartOnCrash}")
        appendLine("max_restarts = ${request.maxRestarts}")
        appendLine()
        appendLine("[group.jvm]")
        appendLine("args = [${request.jvmArgs.joinToString(", ") { tomlString(it) }}]")
    }
}

/**
 * Escapes a string for safe TOML embedding — prevents TOML injection.
 */
private fun tomlString(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    return "\"$escaped\""
}

private fun buildGroupConfig(request: CreateGroupRequest, groupType: GroupType, software: ServerSoftware): GroupConfig {
    return GroupConfig(
        group = GroupDefinition(
            name = request.name,
            type = groupType,
            template = request.template,
            software = software,
            version = request.version,
            modloaderVersion = request.modloaderVersion,
            jarName = request.jarName,
            readyPattern = request.readyPattern,
            resources = ResourcesConfig(request.memory, request.maxPlayers),
            scaling = ScalingConfig(request.minInstances, request.maxInstances, request.playersPerInstance, request.scaleThreshold, request.idleTimeout),
            lifecycle = LifecycleConfig(request.stopOnEmpty, request.restartOnCrash, request.maxRestarts),
            jvm = JvmConfig(request.jvmArgs, request.jvmOptimize)
        )
    )
}

private fun ServerGroup.toResponse(registry: ServiceRegistry): GroupResponse {
    val def = config.group
    return GroupResponse(
        name = name,
        type = def.type.name,
        software = def.software.name,
        version = def.version,
        template = def.template,
        resources = GroupResourcesResponse(def.resources.memory, def.resources.maxPlayers),
        scaling = GroupScalingResponse(
            def.scaling.minInstances, def.scaling.maxInstances,
            def.scaling.playersPerInstance, def.scaling.scaleThreshold, def.scaling.idleTimeout
        ),
        lifecycle = GroupLifecycleResponse(def.lifecycle.stopOnEmpty, def.lifecycle.restartOnCrash, def.lifecycle.maxRestarts),
        jvmArgs = def.jvm.args,
        jvmOptimize = def.jvm.optimize,
        activeInstances = registry.countByGroup(name)
    )
}
