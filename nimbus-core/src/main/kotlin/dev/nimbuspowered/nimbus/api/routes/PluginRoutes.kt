package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.api.ApiError
import dev.nimbuspowered.nimbus.api.ApiMessage
import dev.nimbuspowered.nimbus.api.apiError
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.template.PluginSearchClient
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
data class PluginSearchResponse(
    val results: List<PluginSearchResultResponse>,
    val total: Int
)

@Serializable
data class PluginSearchResultResponse(
    val source: String,
    val name: String,
    val author: String,
    val slug: String,
    val projectId: String,
    val description: String,
    val downloads: Long
)

@Serializable
data class PluginInstallRequest(
    val source: String,
    val slug: String,
    val projectId: String,
    val group: String,
    val mcVersion: String = "",
    val platform: String = "PAPER"
)

fun Route.pluginRoutes(
    pluginSearchClient: PluginSearchClient,
    groupManager: GroupManager,
    templatesDir: Path
) {
    route("/api/plugins") {

        // GET /api/plugins/search?q=..&mcVersion=..&platform=..
        get("search") {
            val query = call.request.queryParameters["q"] ?: ""
            val mcVersion = call.request.queryParameters["mcVersion"] ?: ""
            val platform = call.request.queryParameters["platform"] ?: "PAPER"

            if (query.isBlank()) {
                return@get call.respond(PluginSearchResponse(emptyList(), 0))
            }

            val results = pluginSearchClient.search(query, mcVersion, platform)
            val response = results.map {
                PluginSearchResultResponse(
                    source = it.source.name,
                    name = it.name,
                    author = it.author,
                    slug = it.slug,
                    projectId = it.projectId,
                    description = it.description,
                    downloads = it.downloads
                )
            }
            call.respond(PluginSearchResponse(response, response.size))
        }

        // POST /api/plugins/install — Download and install a plugin to a group's template
        post("install") {
            val request = call.receive<PluginInstallRequest>()

            val group = groupManager.getGroup(request.group)
                ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Group '${request.group}' not found", ApiError.GROUP_NOT_FOUND))

            val searchResult = PluginSearchClient.PluginSearchResult(
                source = PluginSearchClient.PluginSource.valueOf(request.source.uppercase()),
                name = "",
                author = "",
                slug = request.slug,
                projectId = request.projectId,
                description = "",
                downloads = 0
            )

            val mcVersion = request.mcVersion.ifEmpty { group.config.group.version }
            val version = pluginSearchClient.fetchVersion(searchResult, mcVersion, request.platform)
                ?: return@post call.respond(HttpStatusCode.NotFound, apiError("No compatible version found", ApiError.PLUGIN_VERSION_NOT_FOUND))

            val templateName = group.config.group.template.ifEmpty { group.name.lowercase() }
            val pluginsDir = templatesDir.resolve(templateName).resolve("plugins")
            val downloaded = pluginSearchClient.download(version, pluginsDir)
                ?: return@post call.respond(HttpStatusCode.InternalServerError, apiError("Download failed", ApiError.INTERNAL_ERROR))

            call.respond(ApiMessage(true, "Plugin '${version.fileName}' installed to group '${request.group}'"))
        }
    }
}
