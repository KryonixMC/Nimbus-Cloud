package dev.nimbus.api.routes

import dev.nimbus.api.*
import dev.nimbus.config.GroupConfig
import dev.nimbus.config.GroupDefinition
import dev.nimbus.config.GroupType
import dev.nimbus.config.JvmConfig
import dev.nimbus.config.LifecycleConfig
import dev.nimbus.config.ResourcesConfig
import dev.nimbus.config.ScalingConfig
import dev.nimbus.config.ServerSoftware
import dev.nimbus.group.GroupManager
import dev.nimbus.group.ServerGroup
import dev.nimbus.service.ServiceRegistry
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeText

fun Route.groupRoutes(
    registry: ServiceRegistry,
    groupManager: GroupManager,
    groupsDir: Path
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
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Group '$name' not found"))
            call.respond(group.toResponse(registry))
        }

        // POST /api/groups — Create a new group
        post {
            val request = call.receive<CreateGroupRequest>()

            // Validate
            if (groupManager.getGroup(request.name) != null) {
                return@post call.respond(HttpStatusCode.Conflict, ApiMessage(false, "Group '${request.name}' already exists"))
            }

            val software = try {
                ServerSoftware.valueOf(request.software.uppercase())
            } catch (e: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiMessage(false, "Invalid software: '${request.software}'. Valid: PAPER, PURPUR, VELOCITY"))
            }

            val groupType = try {
                GroupType.valueOf(request.type.uppercase())
            } catch (e: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, ApiMessage(false, "Invalid type: '${request.type}'. Valid: STATIC, DYNAMIC"))
            }

            // Build TOML config
            val toml = buildString {
                appendLine("[group]")
                appendLine("name = \"${request.name}\"")
                appendLine("type = \"${groupType.name}\"")
                appendLine("template = \"${request.template}\"")
                appendLine("software = \"${software.name}\"")
                appendLine("version = \"${request.version}\"")
                appendLine()
                appendLine("[group.resources]")
                appendLine("memory = \"${request.memory}\"")
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
                appendLine("args = [${request.jvmArgs.joinToString(", ") { "\"$it\"" }}]")
            }

            // Write TOML file
            val configFile = groupsDir.resolve("${request.name.lowercase()}.toml")
            configFile.writeText(toml)

            // Register in memory
            val groupConfig = GroupConfig(
                group = GroupDefinition(
                    name = request.name,
                    type = groupType,
                    template = request.template,
                    software = software,
                    version = request.version,
                    resources = ResourcesConfig(request.memory, request.maxPlayers),
                    scaling = ScalingConfig(request.minInstances, request.maxInstances, request.playersPerInstance, request.scaleThreshold, request.idleTimeout),
                    lifecycle = LifecycleConfig(request.stopOnEmpty, request.restartOnCrash, request.maxRestarts),
                    jvm = JvmConfig(request.jvmArgs)
                )
            )
            groupManager.reloadGroups(
                groupManager.getAllGroups().map { it.config } + groupConfig
            )

            call.respond(HttpStatusCode.Created, ApiMessage(true, "Group '${request.name}' created"))
        }

        // PUT /api/groups/{name} — Update a group config
        put("{name}") {
            val name = call.parameters["name"]!!
            val existing = groupManager.getGroup(name)
                ?: return@put call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Group '$name' not found"))

            val request = call.receive<CreateGroupRequest>()

            // Rebuild TOML
            val toml = buildString {
                appendLine("[group]")
                appendLine("name = \"${request.name}\"")
                appendLine("type = \"${request.type.uppercase()}\"")
                appendLine("template = \"${request.template}\"")
                appendLine("software = \"${request.software.uppercase()}\"")
                appendLine("version = \"${request.version}\"")
                appendLine()
                appendLine("[group.resources]")
                appendLine("memory = \"${request.memory}\"")
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
                appendLine("args = [${request.jvmArgs.joinToString(", ") { "\"$it\"" }}]")
            }

            val configFile = groupsDir.resolve("${name.lowercase()}.toml")
            configFile.writeText(toml)

            // Reload into memory
            val software = ServerSoftware.valueOf(request.software.uppercase())
            val groupType = GroupType.valueOf(request.type.uppercase())
            val updatedConfig = GroupConfig(
                group = GroupDefinition(
                    name = request.name,
                    type = groupType,
                    template = request.template,
                    software = software,
                    version = request.version,
                    resources = ResourcesConfig(request.memory, request.maxPlayers),
                    scaling = ScalingConfig(request.minInstances, request.maxInstances, request.playersPerInstance, request.scaleThreshold, request.idleTimeout),
                    lifecycle = LifecycleConfig(request.stopOnEmpty, request.restartOnCrash, request.maxRestarts),
                    jvm = JvmConfig(request.jvmArgs)
                )
            )

            val otherGroups = groupManager.getAllGroups()
                .filter { it.name != name }
                .map { it.config }
            groupManager.reloadGroups(otherGroups + updatedConfig)

            call.respond(ApiMessage(true, "Group '$name' updated"))
        }

        // DELETE /api/groups/{name} — Delete a group
        delete("{name}") {
            val name = call.parameters["name"]!!
            groupManager.getGroup(name)
                ?: return@delete call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Group '$name' not found"))

            // Check for running instances
            val running = registry.getByGroup(name)
            if (running.isNotEmpty()) {
                return@delete call.respond(
                    HttpStatusCode.Conflict,
                    ApiMessage(false, "Group '$name' has ${running.size} running instance(s). Stop them first.")
                )
            }

            // Delete config file
            val configFile = groupsDir.resolve("${name.lowercase()}.toml")
            configFile.deleteIfExists()

            // Reload groups without the deleted one
            val remainingGroups = groupManager.getAllGroups()
                .filter { it.name != name }
                .map { it.config }
            groupManager.reloadGroups(remainingGroups)

            call.respond(ApiMessage(true, "Group '$name' deleted"))
        }
    }
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
        activeInstances = registry.countByGroup(name)
    )
}
