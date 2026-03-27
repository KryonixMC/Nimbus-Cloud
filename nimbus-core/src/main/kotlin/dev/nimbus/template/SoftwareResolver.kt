package dev.nimbus.template

import dev.nimbus.config.ServerSoftware
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize

// PaperMC API models
@Serializable
private data class PaperProjectResponse(val versions: List<String>)

@Serializable
private data class PaperBuildsResponse(val builds: List<PaperBuildEntry>)

@Serializable
private data class PaperBuildEntry(val build: Int, val downloads: Map<String, PaperDownloadEntry>)

@Serializable
private data class PaperDownloadEntry(val name: String, val sha256: String)

// Purpur API models
@Serializable
private data class PurpurProjectResponse(val versions: List<String>)

@Serializable
private data class PurpurVersionResponse(val builds: PurpurBuilds)

@Serializable
private data class PurpurBuilds(val latest: String, val all: List<String>)

// Hangar API models (for Via plugins)
@Serializable
private data class HangarVersionsResponse(val result: List<HangarVersion>)

@Serializable
private data class HangarVersion(val name: String, val downloads: Map<String, HangarDownload>)

@Serializable
private data class HangarDownload(val downloadUrl: String)

class SoftwareResolver {

    private val logger = LoggerFactory.getLogger(SoftwareResolver::class.java)

    val client = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }

    // ── Version fetching ────────────────────────────────────────

    /**
     * Fetches available versions from PaperMC API.
     * Returns versions sorted newest-first, with pre-releases/RCs separated.
     */
    suspend fun fetchPaperVersions(): VersionList {
        return try {
            val response = client.get("https://api.papermc.io/v2/projects/paper")
            if (response.status != HttpStatusCode.OK) return VersionList.EMPTY
            val data = json.decodeFromString<PaperProjectResponse>(response.bodyAsText())
            categorizeVersions(data.versions)
        } catch (e: Exception) {
            logger.error("Failed to fetch Paper versions: {}", e.message)
            VersionList.EMPTY
        }
    }

    /**
     * Fetches available versions from Purpur API.
     */
    suspend fun fetchPurpurVersions(): VersionList {
        return try {
            val response = client.get("https://api.purpurmc.org/v2/purpur")
            if (response.status != HttpStatusCode.OK) return VersionList.EMPTY
            val data = json.decodeFromString<PurpurProjectResponse>(response.bodyAsText())
            categorizeVersions(data.versions)
        } catch (e: Exception) {
            logger.error("Failed to fetch Purpur versions: {}", e.message)
            VersionList.EMPTY
        }
    }

    /**
     * Fetches available Velocity versions.
     */
    suspend fun fetchVelocityVersions(): VersionList {
        return try {
            val response = client.get("https://api.papermc.io/v2/projects/velocity")
            if (response.status != HttpStatusCode.OK) return VersionList.EMPTY
            val data = json.decodeFromString<PaperProjectResponse>(response.bodyAsText())
            categorizeVersions(data.versions)
        } catch (e: Exception) {
            logger.error("Failed to fetch Velocity versions: {}", e.message)
            VersionList.EMPTY
        }
    }

    private fun categorizeVersions(versions: List<String>): VersionList {
        val stable = mutableListOf<String>()
        val snapshots = mutableListOf<String>()

        for (v in versions) {
            if (v.contains("pre") || v.contains("rc") || v.contains("SNAPSHOT")) {
                snapshots.add(v)
            } else {
                stable.add(v)
            }
        }

        return VersionList(
            stable = stable.reversed(),     // newest first
            snapshots = snapshots.reversed()
        )
    }

    data class VersionList(
        val stable: List<String>,
        val snapshots: List<String>
    ) {
        val latest: String? get() = stable.firstOrNull()
        val all: List<String> get() = stable + snapshots

        companion object {
            val EMPTY = VersionList(emptyList(), emptyList())
        }
    }

    // ── Via plugin downloads ────────────────────────────────────

    enum class ViaPlugin(val owner: String, val slug: String, val description: String) {
        VIA_VERSION("ViaVersion", "ViaVersion", "Allow newer clients to join older servers"),
        VIA_BACKWARDS("ViaVersion", "ViaBackwards", "Allow older clients to join newer servers"),
        VIA_REWIND("ViaVersion", "ViaRewind", "Extends ViaBackwards support to 1.7-1.8 clients");
    }

    /**
     * Downloads a Via plugin JAR into the template's plugins/ directory.
     * Uses the Hangar API (PaperMC's plugin repository).
     */
    suspend fun downloadViaPlugin(plugin: ViaPlugin, templateDir: Path, platform: String = "PAPER"): Boolean {
        return try {
            val pluginsDir = templateDir.resolve("plugins")
            if (!pluginsDir.exists()) pluginsDir.createDirectories()

            // Check if already downloaded
            val existing = pluginsDir.toFile().listFiles()?.any {
                it.name.startsWith(plugin.slug, ignoreCase = true) && it.name.endsWith(".jar")
            } ?: false
            if (existing) return true

            // Fetch latest version from Hangar
            val url = "https://hangar.papermc.io/api/v1/projects/${plugin.owner}/${plugin.slug}/versions?limit=1&platform=$platform"
            val response = client.get(url)
            if (response.status != HttpStatusCode.OK) {
                logger.error("Failed to fetch {} versions from Hangar: HTTP {}", plugin.slug, response.status)
                return false
            }

            val data = json.decodeFromString<HangarVersionsResponse>(response.bodyAsText())
            val latest = data.result.firstOrNull() ?: run {
                logger.error("No versions found for {} on Hangar", plugin.slug)
                return false
            }

            val download = latest.downloads[platform] ?: latest.downloads.values.firstOrNull() ?: run {
                logger.error("No {} download found for {}", platform, plugin.slug)
                return false
            }

            // Download the JAR
            val jarResponse = client.get(download.downloadUrl)
            if (jarResponse.status != HttpStatusCode.OK) {
                logger.error("Failed to download {}: HTTP {}", plugin.slug, jarResponse.status)
                return false
            }

            val targetFile = pluginsDir.resolve("${plugin.slug}-${latest.name}.jar")
            Files.write(targetFile, jarResponse.readRawBytes())

            val sizeMb = String.format("%.1f", targetFile.fileSize() / 1024.0 / 1024.0)
            logger.info("Downloaded {} {} ({} MB)", plugin.slug, latest.name, sizeMb)
            true
        } catch (e: Exception) {
            logger.error("Failed to download {}: {}", plugin.slug, e.message, e)
            false
        }
    }

    // ── Server JAR downloads ────────────────────────────────────

    suspend fun ensureJarAvailable(software: ServerSoftware, version: String, templateDir: Path): Boolean {
        val jarFile = templateDir.resolve(jarFileName(software))
        if (jarFile.exists()) return true

        logger.info("Downloading {} {}...", software, version)
        return downloadJar(software, version, templateDir) != null
    }

    suspend fun downloadJar(software: ServerSoftware, version: String, targetDir: Path): Path? {
        return when (software) {
            ServerSoftware.PURPUR -> downloadPurpur(version, targetDir)
            else -> downloadPaperMC(software, version, targetDir)
        }
    }

    private suspend fun downloadPaperMC(software: ServerSoftware, version: String, targetDir: Path): Path? {
        return try {
            val project = when (software) {
                ServerSoftware.PAPER -> "paper"
                ServerSoftware.VELOCITY -> "velocity"
                else -> "paper"
            }
            val buildsUrl = "https://api.papermc.io/v2/projects/$project/versions/$version/builds"

            val buildsResponse = client.get(buildsUrl)
            if (buildsResponse.status != HttpStatusCode.OK) {
                logger.error("Failed to fetch builds for {} {}: HTTP {}", software, version, buildsResponse.status)
                return null
            }

            val builds = json.decodeFromString<PaperBuildsResponse>(buildsResponse.bodyAsText())
            if (builds.builds.isEmpty()) {
                logger.error("No builds found for {} {}", software, version)
                return null
            }

            val latestBuild = builds.builds.last()
            val downloadEntry = latestBuild.downloads["application"] ?: run {
                logger.error("No application download for {} {} build {}", software, version, latestBuild.build)
                return null
            }

            val downloadUrl = "https://api.papermc.io/v2/projects/$project/versions/$version/builds/${latestBuild.build}/downloads/${downloadEntry.name}"
            downloadFile(downloadUrl, targetDir, software, version, "build ${latestBuild.build}")
        } catch (e: Exception) {
            logger.error("Failed to download {} {}: {}", software, version, e.message, e)
            null
        }
    }

    private suspend fun downloadPurpur(version: String, targetDir: Path): Path? {
        return try {
            val versionUrl = "https://api.purpurmc.org/v2/purpur/$version"
            val versionResponse = client.get(versionUrl)
            if (versionResponse.status != HttpStatusCode.OK) {
                logger.error("Failed to fetch Purpur builds for {}: HTTP {}", version, versionResponse.status)
                return null
            }

            val versionData = json.decodeFromString<PurpurVersionResponse>(versionResponse.bodyAsText())
            val latestBuild = versionData.builds.latest

            val downloadUrl = "https://api.purpurmc.org/v2/purpur/$version/$latestBuild/download"
            downloadFile(downloadUrl, targetDir, ServerSoftware.PURPUR, version, "build $latestBuild")
        } catch (e: Exception) {
            logger.error("Failed to download Purpur {}: {}", version, e.message, e)
            null
        }
    }

    private suspend fun downloadFile(url: String, targetDir: Path, software: ServerSoftware, version: String, buildInfo: String): Path? {
        val jarResponse = client.get(url)
        if (jarResponse.status != HttpStatusCode.OK) {
            logger.error("Failed to download {} {} {}: HTTP {}", software, version, buildInfo, jarResponse.status)
            return null
        }

        Files.createDirectories(targetDir)
        val targetFile = targetDir.resolve(jarFileName(software))
        val bytes = jarResponse.readRawBytes()
        Files.write(targetFile, bytes)

        val sizeMb = String.format("%.1f", targetFile.fileSize() / 1024.0 / 1024.0)
        logger.info("Downloaded {} {} {} ({} MB)", software, version, buildInfo, sizeMb)
        return targetFile
    }

    fun jarFileName(software: ServerSoftware): String = when (software) {
        ServerSoftware.VELOCITY -> "velocity.jar"
        ServerSoftware.PAPER, ServerSoftware.PURPUR -> "server.jar"
    }

    fun close() {
        client.close()
    }
}
