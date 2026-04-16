package dev.nimbuspowered.nimbus.module.docker.routes

import dev.nimbuspowered.nimbus.module.docker.DockerClient
import dev.nimbuspowered.nimbus.module.docker.DockerConfigManager
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Admin-only Docker introspection endpoints. No mutating endpoints beyond
 * prune — container lifecycle stays with [ServiceManager], the module doesn't
 * expose raw create/start/stop here.
 */
fun Route.dockerRoutes(
    client: DockerClient,
    configManager: DockerConfigManager
) {
    route("/api/docker") {

        get("status") {
            val cfg = configManager.config.docker
            if (!cfg.enabled) {
                call.respond(DockerStatus(enabled = false, reachable = false, socket = cfg.socket))
                return@get
            }
            val reachable = client.ping()
            val v = if (reachable) client.version() else null
            val containers = if (reachable) {
                runCatching { client.listContainers(labels = mapOf("nimbus.managed" to "true")) }
                    .getOrElse { emptyList() }
            } else emptyList()
            val running = containers.count { (it["State"]?.jsonPrimitive?.content ?: "") == "running" }

            call.respond(DockerStatus(
                enabled = true,
                reachable = reachable,
                socket = cfg.socket,
                version = v?.version,
                apiVersion = v?.apiVersion,
                os = v?.os,
                arch = v?.arch,
                totalContainers = containers.size,
                runningContainers = running
            ))
        }

        get("containers") {
            val containers = runCatching { client.listContainers(labels = mapOf("nimbus.managed" to "true")) }
                .getOrElse {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Docker unreachable: ${it.message}"))
                    return@get
                }
            val out = containers.map { toSummary(it) }
            call.respond(out)
        }

        get("containers/{name}") {
            val name = call.parameters["name"]
            if (name.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing container name"))
                return@get
            }
            val info = runCatching { client.inspect(name) }
                .getOrElse {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Docker unreachable: ${it.message}"))
                    return@get
                }
            if (info == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("No such container: $name"))
                return@get
            }
            val state = info["State"]?.jsonObject
            val config = info["Config"]?.jsonObject
            val host = info["HostConfig"]?.jsonObject

            val id = info["Id"]?.jsonPrimitive?.content ?: ""
            val stats = if (state?.get("Running")?.jsonPrimitive?.content == "true") client.stats(id) else null

            call.respond(ContainerDetail(
                id = id,
                name = info["Name"]?.jsonPrimitive?.content?.removePrefix("/") ?: name,
                image = config?.get("Image")?.jsonPrimitive?.content ?: "",
                state = state?.get("Status")?.jsonPrimitive?.content ?: "",
                running = state?.get("Running")?.jsonPrimitive?.content?.toBoolean() ?: false,
                pid = state?.get("Pid")?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                exitCode = state?.get("ExitCode")?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                memoryLimitBytes = host?.get("Memory")?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                nanoCpus = host?.get("NanoCpus")?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                memoryUsedBytes = stats?.memoryBytes ?: 0,
                cpuPercent = stats?.cpuPercent ?: 0.0
            ))
        }

        post("prune") {
            val containers = runCatching { client.listContainers(labels = mapOf("nimbus.managed" to "true")) }
                .getOrElse {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Docker unreachable: ${it.message}"))
                    return@post
                }
            val stopped = containers.filter {
                (it["State"]?.jsonPrimitive?.content ?: "") != "running"
            }
            var removed = 0
            val errors = mutableListOf<String>()
            for (c in stopped) {
                val id = c["Id"]?.jsonPrimitive?.content ?: continue
                try {
                    client.removeContainer(id, force = true)
                    removed++
                } catch (e: Exception) {
                    errors += "${id.take(12)}: ${e.message}"
                }
            }
            call.respond(PruneResponse(removed, errors))
        }
    }
}

private fun toSummary(c: JsonObject): ContainerSummary {
    val labels = c["Labels"]?.jsonObject
    val ports = c["Ports"]?.jsonArray?.map {
        val obj = it.jsonObject
        PortMapping(
            publicPort = obj["PublicPort"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            privatePort = obj["PrivatePort"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            type = obj["Type"]?.jsonPrimitive?.content ?: "tcp"
        )
    } ?: emptyList()
    return ContainerSummary(
        id = c["Id"]?.jsonPrimitive?.content ?: "",
        service = labels?.get("nimbus.service")?.jsonPrimitive?.content ?: "",
        group = labels?.get("nimbus.group")?.jsonPrimitive?.content ?: "",
        image = c["Image"]?.jsonPrimitive?.content ?: "",
        state = c["State"]?.jsonPrimitive?.content ?: "",
        status = c["Status"]?.jsonPrimitive?.content ?: "",
        ports = ports
    )
}

@Serializable
data class DockerStatus(
    val enabled: Boolean,
    val reachable: Boolean,
    val socket: String,
    val version: String? = null,
    val apiVersion: String? = null,
    val os: String? = null,
    val arch: String? = null,
    val totalContainers: Int = 0,
    val runningContainers: Int = 0
)

@Serializable
data class ContainerSummary(
    val id: String,
    val service: String,
    val group: String,
    val image: String,
    val state: String,
    val status: String,
    val ports: List<PortMapping>
)

@Serializable
data class PortMapping(
    val publicPort: Int,
    val privatePort: Int,
    val type: String
)

@Serializable
data class ContainerDetail(
    val id: String,
    val name: String,
    val image: String,
    val state: String,
    val running: Boolean,
    val pid: Long,
    val exitCode: Int,
    val memoryLimitBytes: Long,
    val nanoCpus: Long,
    val memoryUsedBytes: Long,
    val cpuPercent: Double
)

@Serializable
data class PruneResponse(
    val removed: Int,
    val errors: List<String>
)

@Serializable
data class ErrorResponse(val error: String)
