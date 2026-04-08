package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.template.SoftwareResolver
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class SoftwareListResponse(
    val software: List<SoftwareInfo>
)

@Serializable
data class SoftwareInfo(
    val name: String,
    val needsModloaderVersion: Boolean,
    val needsCustomJar: Boolean,
    val isProxy: Boolean
)

@Serializable
data class VersionListResponse(
    val software: String,
    val stable: List<String>,
    val snapshots: List<String>,
    val latest: String?
)

fun Route.softwareRoutes(softwareResolver: SoftwareResolver) {

    // GET /api/software — List all available server software types
    get("/api/software") {
        val list = ServerSoftware.entries.map { sw ->
            SoftwareInfo(
                name = sw.name,
                needsModloaderVersion = sw in listOf(ServerSoftware.FORGE, ServerSoftware.NEOFORGE, ServerSoftware.FABRIC),
                needsCustomJar = sw == ServerSoftware.CUSTOM,
                isProxy = sw == ServerSoftware.VELOCITY
            )
        }
        call.respond(SoftwareListResponse(list))
    }

    // GET /api/software/{type}/versions — Fetch available versions for a software type
    get("/api/software/{type}/versions") {
        val typeName = call.parameters["type"]?.uppercase() ?: return@get call.respond(HttpStatusCode.BadRequest)
        val software = try {
            ServerSoftware.valueOf(typeName)
        } catch (_: IllegalArgumentException) {
            return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown software: $typeName"))
        }

        val versions = when (software) {
            ServerSoftware.PAPER -> softwareResolver.fetchPaperVersions()
            ServerSoftware.VELOCITY -> softwareResolver.fetchVelocityVersions()
            ServerSoftware.PURPUR -> softwareResolver.fetchPurpurVersions()
            ServerSoftware.FOLIA -> softwareResolver.fetchFoliaVersions()
            ServerSoftware.PUFFERFISH -> softwareResolver.fetchPufferfishVersions()
            ServerSoftware.LEAF -> softwareResolver.fetchLeafVersions()
            ServerSoftware.FORGE -> softwareResolver.fetchForgeGameVersions()
            ServerSoftware.NEOFORGE -> softwareResolver.fetchNeoForgeGameVersions()
            ServerSoftware.FABRIC -> softwareResolver.fetchFabricGameVersions()
            ServerSoftware.CUSTOM -> SoftwareResolver.VersionList.EMPTY
        }

        call.respond(VersionListResponse(
            software = software.name,
            stable = versions.stable,
            snapshots = versions.snapshots,
            latest = versions.latest
        ))
    }

    // GET /api/software/{type}/modloader-versions?mcVersion=1.20.1 — Fetch modloader versions
    get("/api/software/{type}/modloader-versions") {
        val typeName = call.parameters["type"]?.uppercase() ?: return@get call.respond(HttpStatusCode.BadRequest)
        val mcVersion = call.request.queryParameters["mcVersion"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "mcVersion query parameter required"))

        val software = try {
            ServerSoftware.valueOf(typeName)
        } catch (_: IllegalArgumentException) {
            return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown software: $typeName"))
        }

        val versions = when (software) {
            ServerSoftware.FORGE -> softwareResolver.fetchForgeVersions(mcVersion)
            ServerSoftware.NEOFORGE -> softwareResolver.fetchNeoForgeVersions(mcVersion)
            ServerSoftware.FABRIC -> softwareResolver.fetchFabricLoaderVersions()
            else -> SoftwareResolver.VersionList.EMPTY
        }

        call.respond(VersionListResponse(
            software = software.name,
            stable = versions.stable,
            snapshots = versions.snapshots,
            latest = versions.latest
        ))
    }
}
