package dev.kryonix.nimbus.template

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize

/**
 * Searches Hangar and Modrinth for Minecraft plugins, downloads JARs,
 * and resolves required dependencies.
 */
class PluginSearchClient(private val client: HttpClient) {

    private val logger = LoggerFactory.getLogger(PluginSearchClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    // ── Unified Models ─────────────────────────────────────

    enum class PluginSource { HANGAR, MODRINTH }

    data class PluginSearchResult(
        val source: PluginSource,
        val name: String,
        val author: String,
        val slug: String,
        val projectId: String,
        val description: String,
        val downloads: Long
    )

    data class PluginVersionInfo(
        val source: PluginSource,
        val versionName: String,
        val downloadUrl: String,
        val fileName: String,
        val fileSize: Long,
        val dependencies: List<PluginDependencyInfo>
    )

    data class PluginDependencyInfo(
        val name: String,
        val slug: String?,
        val projectId: String?,
        val required: Boolean,
        val source: PluginSource
    )

    // ── Search ─────────────────────────────────────────────

    /**
     * Searches both Hangar and Modrinth in parallel, merges and deduplicates results.
     */
    suspend fun search(query: String, mcVersion: String, platform: String = "PAPER"): List<PluginSearchResult> {
        if (query.isBlank()) return emptyList()

        val (hangar, modrinth) = coroutineScope {
            val h = async { searchHangar(query, mcVersion, platform) }
            val m = async { searchModrinth(query, mcVersion, platform.lowercase()) }
            h.await() to m.await()
        }

        // Deduplicate by lowercase slug, prefer source with more downloads
        val merged = linkedMapOf<String, PluginSearchResult>()
        for (r in hangar) merged[r.slug.lowercase()] = r
        for (r in modrinth) {
            val key = r.slug.lowercase()
            val existing = merged[key]
            if (existing == null || r.downloads > existing.downloads) {
                merged[key] = r
            }
        }

        return merged.values.sortedByDescending { it.downloads }.take(10)
    }

    private suspend fun searchHangar(query: String, mcVersion: String, platform: String): List<PluginSearchResult> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val versionParam = if (mcVersion.isNotEmpty()) "&version=$mcVersion" else ""
            val url = "https://hangar.papermc.io/api/v1/projects?q=$encoded&platform=$platform$versionParam&limit=10&sort=-downloads"
            val response = client.get(url)
            if (response.status != HttpStatusCode.OK) return emptyList()

            val data = json.decodeFromString<HangarSearchResponse>(response.bodyAsText())
            data.result.map { project ->
                PluginSearchResult(
                    source = PluginSource.HANGAR,
                    name = project.name,
                    author = project.namespace.owner,
                    slug = project.namespace.slug,
                    projectId = "${project.namespace.owner}/${project.namespace.slug}",
                    description = project.description.take(100),
                    downloads = project.stats.downloads
                )
            }
        } catch (e: Exception) {
            logger.debug("Hangar search failed: {}", e.message)
            emptyList()
        }
    }

    private suspend fun searchModrinth(query: String, mcVersion: String, loader: String): List<PluginSearchResult> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val versionFacet = if (mcVersion.isNotEmpty()) ""","versions:$mcVersion"""" else ""
            val facets = """[["categories:$loader"$versionFacet]]"""
            val facetsEncoded = URLEncoder.encode(facets, "UTF-8")
            val url = "https://api.modrinth.com/v2/search?query=$encoded&facets=$facetsEncoded&limit=10&index=downloads"
            val response = client.get(url)
            if (response.status != HttpStatusCode.OK) return emptyList()

            val data = json.decodeFromString<ModrinthSearchResponse>(response.bodyAsText())
            data.hits
                .filter { hit -> hit.categories.any { it in listOf(loader, "bukkit", "spigot", "folia") } }
                .map { hit ->
                    PluginSearchResult(
                        source = PluginSource.MODRINTH,
                        name = hit.title,
                        author = hit.author,
                        slug = hit.slug,
                        projectId = hit.projectId,
                        description = hit.description.take(100),
                        downloads = hit.downloads
                    )
                }
        } catch (e: Exception) {
            logger.debug("Modrinth search failed: {}", e.message)
            emptyList()
        }
    }

    // ── Version Fetch ──────────────────────────────────────

    /**
     * Fetches the latest compatible version for a plugin, including dependency info.
     */
    suspend fun fetchVersion(result: PluginSearchResult, mcVersion: String, platform: String = "PAPER"): PluginVersionInfo? {
        return when (result.source) {
            PluginSource.HANGAR -> fetchHangarVersion(result, mcVersion, platform)
            PluginSource.MODRINTH -> fetchModrinthVersion(result, mcVersion, platform.lowercase())
        }
    }

    private suspend fun fetchHangarVersion(result: PluginSearchResult, mcVersion: String, platform: String): PluginVersionInfo? {
        return try {
            val url = "https://hangar.papermc.io/api/v1/projects/${result.projectId}/versions?limit=1&platform=$platform"
            val response = client.get(url)
            if (response.status != HttpStatusCode.OK) return null

            val data = json.decodeFromString<HangarVersionsResponse>(response.bodyAsText())
            val version = data.result.firstOrNull() ?: return null
            val download = version.downloads[platform] ?: version.downloads.values.firstOrNull() ?: return null

            val deps = version.pluginDependencies[platform]?.map { dep ->
                PluginDependencyInfo(
                    name = dep.name,
                    slug = dep.namespace?.slug,
                    projectId = dep.namespace?.let { "${it.owner}/${it.slug}" },
                    required = dep.required,
                    source = PluginSource.HANGAR
                )
            } ?: emptyList()

            PluginVersionInfo(
                source = PluginSource.HANGAR,
                versionName = version.name,
                downloadUrl = download.downloadUrl,
                fileName = download.fileInfo?.name ?: "${result.slug}-${version.name}.jar",
                fileSize = download.fileInfo?.sizeBytes ?: 0,
                dependencies = deps
            )
        } catch (e: Exception) {
            logger.debug("Hangar version fetch failed for {}: {}", result.name, e.message)
            null
        }
    }

    private suspend fun fetchModrinthVersion(result: PluginSearchResult, mcVersion: String, loader: String): PluginVersionInfo? {
        return try {
            val versionFilter = if (mcVersion.isNotEmpty()) """&game_versions=["$mcVersion"]""" else ""
            val url = "https://api.modrinth.com/v2/project/${result.slug}/version?loaders=[\"$loader\"]$versionFilter"
            val response = client.get(url)
            if (response.status != HttpStatusCode.OK) return null

            val versions = json.decodeFromString<List<ModrinthVersionDetail>>(response.bodyAsText())
            val version = versions.firstOrNull() ?: return null
            val file = version.files.firstOrNull { it.primary } ?: version.files.firstOrNull() ?: return null

            val deps = version.dependencies
                .filter { it.dependencyType == "required" && it.projectId != null }
                .map { dep ->
                    val depName = resolveModrinthProjectName(dep.projectId!!)
                    PluginDependencyInfo(
                        name = depName ?: dep.projectId,
                        slug = depName?.lowercase(),
                        projectId = dep.projectId,
                        required = true,
                        source = PluginSource.MODRINTH
                    )
                }

            PluginVersionInfo(
                source = PluginSource.MODRINTH,
                versionName = version.versionNumber,
                downloadUrl = file.url,
                fileName = file.filename,
                fileSize = file.size,
                dependencies = deps
            )
        } catch (e: Exception) {
            logger.debug("Modrinth version fetch failed for {}: {}", result.name, e.message)
            null
        }
    }

    private suspend fun resolveModrinthProjectName(projectId: String): String? {
        return try {
            val response = client.get("https://api.modrinth.com/v2/project/$projectId")
            if (response.status != HttpStatusCode.OK) return null
            val info = json.decodeFromString<ModrinthProjectInfo>(response.bodyAsText())
            info.title
        } catch (_: Exception) {
            null
        }
    }

    // ── Download ───────────────────────────────────────────

    /**
     * Downloads a plugin JAR to the given directory.
     * Returns the file path on success, null on failure.
     */
    suspend fun download(version: PluginVersionInfo, pluginsDir: Path): Path? {
        return try {
            if (!pluginsDir.exists()) Files.createDirectories(pluginsDir)

            val targetFile = pluginsDir.resolve(version.fileName)
            val response = client.get(version.downloadUrl)
            if (response.status != HttpStatusCode.OK) {
                logger.error("Download failed for {}: HTTP {}", version.fileName, response.status)
                return null
            }

            Files.write(targetFile, response.readRawBytes())
            logger.info("Downloaded {} ({} MB)", version.fileName, formatSize(targetFile.fileSize()))
            targetFile
        } catch (e: Exception) {
            logger.error("Download failed for {}: {}", version.fileName, e.message)
            null
        }
    }

    /**
     * Resolves and downloads a required dependency.
     * Skips if a JAR with a matching slug prefix already exists in the target directory.
     */
    suspend fun resolveAndDownloadDependency(
        dep: PluginDependencyInfo,
        mcVersion: String,
        pluginsDir: Path,
        platform: String = "PAPER"
    ): Path? {
        // Check if already installed
        val slugLower = (dep.slug ?: dep.name).lowercase()
        if (pluginsDir.exists()) {
            val existing = Files.list(pluginsDir).use { stream ->
                stream.anyMatch { it.fileName.toString().lowercase().startsWith(slugLower) && it.fileName.toString().endsWith(".jar") }
            }
            if (existing) {
                logger.debug("Dependency {} already installed, skipping", dep.name)
                return pluginsDir // already exists
            }
        }

        // Create a search result from the dependency info and fetch its version
        val searchResult = if (dep.projectId != null) {
            PluginSearchResult(
                source = dep.source,
                name = dep.name,
                author = "",
                slug = dep.slug ?: dep.name,
                projectId = dep.projectId,
                description = "",
                downloads = 0
            )
        } else {
            // Try searching by name
            val results = search(dep.name, mcVersion, platform)
            results.firstOrNull() ?: return null
        }

        val version = fetchVersion(searchResult, mcVersion, platform) ?: return null
        return download(version, pluginsDir)
    }

    companion object {
        fun formatSize(bytes: Long): String = String.format("%.1f", bytes / 1024.0 / 1024.0)
    }

    // ── Hangar API Models ──────────────────────────────────

    @Serializable
    private data class HangarSearchResponse(
        val pagination: HangarPagination = HangarPagination(),
        val result: List<HangarProject> = emptyList()
    )

    @Serializable
    private data class HangarPagination(val limit: Int = 0, val offset: Int = 0, val count: Int = 0)

    @Serializable
    private data class HangarProject(
        val name: String,
        val namespace: HangarNamespace,
        val description: String = "",
        val stats: HangarStats = HangarStats(),
        val category: String = ""
    )

    @Serializable
    private data class HangarNamespace(val owner: String = "", val slug: String = "")

    @Serializable
    private data class HangarStats(val downloads: Long = 0, val stars: Long = 0)

    @Serializable
    private data class HangarVersionsResponse(val result: List<HangarVersionDetail> = emptyList())

    @Serializable
    private data class HangarVersionDetail(
        val name: String,
        val downloads: Map<String, HangarPlatformDownload> = emptyMap(),
        val pluginDependencies: Map<String, List<HangarPluginDep>> = emptyMap()
    )

    @Serializable
    private data class HangarPlatformDownload(
        val downloadUrl: String = "",
        val fileInfo: HangarFileInfo? = null
    )

    @Serializable
    private data class HangarFileInfo(
        val name: String = "",
        val sizeBytes: Long = 0,
        val sha256Hash: String = ""
    )

    @Serializable
    private data class HangarPluginDep(
        val name: String = "",
        val required: Boolean = false,
        val namespace: HangarNamespace? = null
    )

    // ── Modrinth API Models ────────────────────────────────

    @Serializable
    private data class ModrinthSearchResponse(
        val hits: List<ModrinthHit> = emptyList(),
        @SerialName("total_hits") val totalHits: Int = 0
    )

    @Serializable
    private data class ModrinthHit(
        @SerialName("project_id") val projectId: String = "",
        val slug: String = "",
        val title: String = "",
        val description: String = "",
        val author: String = "",
        val downloads: Long = 0,
        val follows: Long = 0,
        val categories: List<String> = emptyList()
    )

    @Serializable
    private data class ModrinthVersionDetail(
        val id: String = "",
        @SerialName("version_number") val versionNumber: String = "",
        val files: List<ModrinthFileDetail> = emptyList(),
        val dependencies: List<ModrinthDep> = emptyList()
    )

    @Serializable
    private data class ModrinthFileDetail(
        val url: String = "",
        val filename: String = "",
        val primary: Boolean = false,
        val size: Long = 0
    )

    @Serializable
    private data class ModrinthDep(
        @SerialName("project_id") val projectId: String? = null,
        @SerialName("dependency_type") val dependencyType: String = ""
    )

    @Serializable
    private data class ModrinthProjectInfo(
        val title: String = "",
        val slug: String = ""
    )
}
