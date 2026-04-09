package dev.nimbuspowered.nimbus.template

import dev.nimbuspowered.nimbus.config.ServerSoftware
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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

// Forge API models
@Serializable
private data class ForgePromotions(val promos: Map<String, String> = emptyMap())

// Fabric API models
@Serializable
private data class FabricLoaderVersion(val version: String, val stable: Boolean = true)

@Serializable
private data class FabricGameVersion(val version: String, val stable: Boolean = true)

@Serializable
private data class FabricInstallerVersion(val version: String, val stable: Boolean = true)

// NeoForge API models
@Serializable
private data class NeoForgeVersionsResponse(val versions: List<String> = emptyList())

// Modrinth API models (for proxy forwarding mods)
@Serializable
private data class ModrinthVersionsResponse(val id: String = "", val version_number: String = "", val files: List<ModrinthFile> = emptyList())

@Serializable
private data class ModrinthFile(val url: String, val filename: String, val primary: Boolean = false)

// GeyserMC API models (for Geyser + Floodgate)
@Serializable
private data class GeyserProjectResponse(val versions: List<String> = emptyList())

@Serializable
private data class GeyserBuildsResponse(val builds: List<GeyserBuild> = emptyList())

@Serializable
private data class GeyserBuild(val build: Int, val downloads: Map<String, GeyserDownload> = emptyMap())

@Serializable
private data class GeyserDownload(val name: String, val sha256: String = "")

// Pufferfish CI API models
@Serializable
private data class PufferfishCIResponse(val jobs: List<PufferfishJob> = emptyList())

@Serializable
private data class PufferfishJob(val name: String, val url: String = "")

@Serializable
private data class PufferfishBuildResponse(val url: String = "", val artifacts: List<PufferfishArtifact> = emptyList())

@Serializable
private data class PufferfishArtifact(val fileName: String, val relativePath: String)

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
     * Fetches available Folia versions from PaperMC API.
     * Folia only supports 1.19.4+.
     */
    suspend fun fetchFoliaVersions(): VersionList {
        return try {
            val response = client.get("https://api.papermc.io/v2/projects/folia")
            if (response.status != HttpStatusCode.OK) return VersionList.EMPTY
            val data = json.decodeFromString<PaperProjectResponse>(response.bodyAsText())
            categorizeVersions(data.versions)
        } catch (e: Exception) {
            logger.error("Failed to fetch Folia versions: {}", e.message)
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
     * Fetches available Pufferfish versions from the CI server.
     * Pufferfish uses Jenkins CI with jobs named Pufferfish-{majorVersion}.
     */
    suspend fun fetchPufferfishVersions(): VersionList {
        return try {
            val response = client.get("https://ci.pufferfish.host/api/json?tree=jobs[name]")
            if (response.status != HttpStatusCode.OK) return VersionList.EMPTY
            val data = json.decodeFromString<PufferfishCIResponse>(response.bodyAsText())
            // Extract MC major versions from job names like "Pufferfish-1.21"
            val versions = data.jobs
                .map { it.name }
                .filter { it.startsWith("Pufferfish-") && !it.contains("Purpur") }
                .map { it.removePrefix("Pufferfish-") }
                .sortedDescending()
            VersionList(stable = versions, snapshots = emptyList())
        } catch (e: Exception) {
            logger.error("Failed to fetch Pufferfish versions: {}", e.message)
            VersionList.EMPTY
        }
    }

    /**
     * Fetches available Leaf versions from the Leaf API.
     * Leaf uses a PaperMC-compatible API at api.leafmc.one.
     */
    suspend fun fetchLeafVersions(): VersionList {
        return try {
            val response = client.get("https://api.leafmc.one/v2/projects/leaf")
            if (response.status != HttpStatusCode.OK) return VersionList.EMPTY
            val data = json.decodeFromString<PaperProjectResponse>(response.bodyAsText())
            categorizeVersions(data.versions)
        } catch (e: Exception) {
            logger.error("Failed to fetch Leaf versions: {}", e.message)
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
            // Velocity uses SNAPSHOT versions as primary distribution —
            // treat all versions as stable to avoid false update suggestions
            VersionList(stable = data.versions.reversed(), snapshots = emptyList())
        } catch (e: Exception) {
            logger.error("Failed to fetch Velocity versions: {}", e.message)
            VersionList.EMPTY
        }
    }

    // ── Forge version fetching ─────────────────────────────────────

    suspend fun fetchForgeVersions(mcVersion: String): VersionList {
        return try {
            val url = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"
            val response = client.get(url)
            if (response.status != HttpStatusCode.OK) return VersionList.EMPTY
            val data = json.decodeFromString<ForgePromotions>(response.bodyAsText())
            val versions = data.promos.entries
                .filter { it.key.startsWith("$mcVersion-") }
                .map { it.value }
                .distinct()
            VersionList(stable = versions, snapshots = emptyList())
        } catch (e: Exception) {
            logger.error("Failed to fetch Forge versions: {}", e.message)
            VersionList.EMPTY
        }
    }

    suspend fun fetchForgeGameVersions(): VersionList {
        return try {
            val url = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"
            val response = client.get(url)
            if (response.status != HttpStatusCode.OK) return VersionList.EMPTY
            val data = json.decodeFromString<ForgePromotions>(response.bodyAsText())
            val versions = data.promos.keys.map { it.substringBefore("-") }.distinct().sortedDescending()
            VersionList(stable = versions, snapshots = emptyList())
        } catch (e: Exception) {
            logger.error("Failed to fetch Forge game versions: {}", e.message)
            VersionList.EMPTY
        }
    }

    // ── NeoForge version fetching ───────────────────────────────

    suspend fun fetchNeoForgeVersions(mcVersion: String): VersionList {
        return try {
            val url = "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge"
            val response = client.get(url)
            if (response.status != HttpStatusCode.OK) return VersionList.EMPTY
            val data = json.decodeFromString<NeoForgeVersionsResponse>(response.bodyAsText())
            val parts = mcVersion.split(".")
            val minor = parts.getOrNull(1) ?: return VersionList.EMPTY
            val patch = parts.getOrNull(2) ?: "0"
            val prefix = "$minor.$patch."
            val matching = data.versions.filter { it.startsWith(prefix) }.reversed()
            val stable = matching.filter { !it.contains("beta") && !it.contains("rc") }
            val snapshots = matching.filter { it.contains("beta") || it.contains("rc") }
            VersionList(stable = stable.ifEmpty { matching }, snapshots = snapshots)
        } catch (e: Exception) {
            logger.error("Failed to fetch NeoForge versions: {}", e.message)
            VersionList.EMPTY
        }
    }

    suspend fun fetchNeoForgeGameVersions(): VersionList {
        return try {
            val url = "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge"
            val response = client.get(url)
            if (response.status != HttpStatusCode.OK) return VersionList.EMPTY
            val data = json.decodeFromString<NeoForgeVersionsResponse>(response.bodyAsText())
            val mcVersions = data.versions.mapNotNull { ver ->
                val vParts = ver.split(".")
                if (vParts.size >= 2) "1.${vParts[0]}.${vParts[1]}" else null
            }.distinct().reversed()
            VersionList(stable = mcVersions, snapshots = emptyList())
        } catch (e: Exception) {
            logger.error("Failed to fetch NeoForge game versions: {}", e.message)
            VersionList.EMPTY
        }
    }

    // ── Fabric version fetching ─────────────────────────────────

    suspend fun fetchFabricLoaderVersions(): VersionList {
        return try {
            val url = "https://meta.fabricmc.net/v2/versions/loader"
            val response = client.get(url)
            if (response.status != HttpStatusCode.OK) return VersionList.EMPTY
            val data = json.decodeFromString<List<FabricLoaderVersion>>(response.bodyAsText())
            val stable = data.filter { it.stable }.map { it.version }
            val snapshots = data.filter { !it.stable }.map { it.version }
            VersionList(stable = stable, snapshots = snapshots)
        } catch (e: Exception) {
            logger.error("Failed to fetch Fabric loader versions: {}", e.message)
            VersionList.EMPTY
        }
    }

    suspend fun fetchFabricGameVersions(): VersionList {
        return try {
            val url = "https://meta.fabricmc.net/v2/versions/game"
            val response = client.get(url)
            if (response.status != HttpStatusCode.OK) return VersionList.EMPTY
            val data = json.decodeFromString<List<FabricGameVersion>>(response.bodyAsText())
            val stable = data.filter { it.stable }.map { it.version }
            val snapshots = data.filter { !it.stable }.map { it.version }
            VersionList(stable = stable, snapshots = snapshots)
        } catch (e: Exception) {
            logger.error("Failed to fetch Fabric game versions: {}", e.message)
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

    // ── Proxy forwarding mod downloads ─────────────────────────

    /**
     * Auto-downloads the correct proxy forwarding mod for Forge/NeoForge servers.
     */
    suspend fun ensureForwardingMod(software: ServerSoftware, mcVersion: String, templateDir: Path) {
        val modsDir = templateDir.resolve("mods")
        if (!modsDir.exists()) modsDir.createDirectories()

        val hasForwardingMod = modsDir.toFile().listFiles()?.any {
            val name = it.name.lowercase()
            name.contains("proxy-compatible") || name.contains("bungeeforge") || name.contains("neovelocity")
        } ?: false
        if (hasForwardingMod) return

        val loader = when (software) {
            ServerSoftware.FORGE -> "forge"
            ServerSoftware.NEOFORGE -> "neoforge"
            else -> return
        }

        // proxy-compatible-forge supports both Forge and NeoForge
        downloadModrinthMod("proxy-compatible-forge", loader, modsDir, "Proxy Compatible Forge", mcVersion)
    }

    /**
     * Auto-downloads FabricProxy-Lite and its dependency Fabric API for Fabric servers.
     */
    suspend fun ensureFabricProxyMod(templateDir: Path, mcVersion: String) {
        val modsDir = templateDir.resolve("mods")
        if (!modsDir.exists()) modsDir.createDirectories()

        // Download Fabric API first (required by FabricProxy-Lite and most Fabric mods)
        val hasFabricApi = modsDir.toFile().listFiles()?.any {
            val name = it.name.lowercase()
            name.contains("fabric-api") || name.contains("fabricapi")
        } ?: false
        if (!hasFabricApi) {
            downloadModrinthMod("fabric-api", "fabric", modsDir, "Fabric API", mcVersion)
        }

        // Then download FabricProxy-Lite
        val hasProxyMod = modsDir.toFile().listFiles()?.any {
            val name = it.name.lowercase()
            name.contains("fabricproxy") || name.contains("proxy-lite")
        } ?: false
        if (!hasProxyMod) {
            downloadModrinthMod("fabricproxy-lite", "fabric", modsDir, "FabricProxy-Lite", mcVersion)
        }
    }

    /**
     * Auto-downloads Cardboard mod and its dependency iCommon for Fabric servers.
     * Cardboard enables Bukkit/Paper plugin support on Fabric — BETA software.
     */
    suspend fun ensureCardboardMod(templateDir: Path, mcVersion: String): Boolean {
        val modsDir = templateDir.resolve("mods")
        if (!modsDir.exists()) modsDir.createDirectories()

        // iCommon is required by Cardboard — install first
        val hasICommon = modsDir.toFile().listFiles()?.any {
            val name = it.name.lowercase()
            name.contains("icommon") && name.endsWith(".jar")
        } ?: false
        if (!hasICommon) {
            downloadModrinthMod("icommon", "fabric", modsDir, "iCommon API", mcVersion)
        }

        val hasCardboard = modsDir.toFile().listFiles()?.any {
            val name = it.name.lowercase()
            name.contains("cardboard") && name.endsWith(".jar")
        } ?: false
        if (hasCardboard) {
            logger.debug("Cardboard mod already present")
            return true
        }

        return downloadModrinthMod("cardboard", "fabric", modsDir, "Cardboard", mcVersion)
    }

    // ── Bedrock plugin downloads (Geyser + Floodgate) ───────────

    /**
     * Downloads Geyser (Velocity plugin) to the template's plugins/ directory.
     * Uses the GeyserMC download API (Modrinth only has Fabric/NeoForge builds).
     */
    suspend fun ensureGeyserPlugin(templateDir: Path): Boolean {
        val pluginsDir = templateDir.resolve("plugins")
        if (!pluginsDir.exists()) pluginsDir.createDirectories()
        val hasGeyser = pluginsDir.toFile().listFiles()?.any {
            it.name.lowercase().contains("geyser") && it.name.endsWith(".jar")
        } ?: false
        if (hasGeyser) return true
        return downloadGeyserMCPlugin("geyser", "velocity", pluginsDir, "Geyser")
    }

    /**
     * Downloads Floodgate to the template's plugins/ directory.
     * Uses the GeyserMC download API.
     * @param platform "velocity" for proxy, "spigot" for backend servers (Paper/Purpur/Folia)
     */
    suspend fun ensureFloodgatePlugin(templateDir: Path, platform: String): Boolean {
        val pluginsDir = templateDir.resolve("plugins")
        if (!pluginsDir.exists()) pluginsDir.createDirectories()
        val hasFloodgate = pluginsDir.toFile().listFiles()?.any {
            it.name.lowercase().contains("floodgate") && it.name.endsWith(".jar")
        } ?: false
        if (hasFloodgate) return true
        return downloadGeyserMCPlugin("floodgate", platform, pluginsDir, "Floodgate")
    }

    /**
     * Downloads a plugin from the GeyserMC download API.
     * API: https://download.geysermc.org/v2/projects/{project}/versions/{version}/builds/{build}/downloads/{platform}
     */
    private suspend fun downloadGeyserMCPlugin(project: String, platform: String, pluginsDir: Path, displayName: String): Boolean {
        return try {
            // Get latest version
            val projectUrl = "https://download.geysermc.org/v2/projects/$project"
            val projectResponse = client.get(projectUrl)
            if (projectResponse.status != HttpStatusCode.OK) {
                logger.error("Failed to fetch {} versions: HTTP {}", displayName, projectResponse.status)
                return false
            }
            val projectData = json.decodeFromString<GeyserProjectResponse>(projectResponse.bodyAsText())
            val latestVersion = projectData.versions.lastOrNull() ?: run {
                logger.error("No versions found for {}", displayName)
                return false
            }

            // Get latest build for that version
            val buildsUrl = "$projectUrl/versions/$latestVersion/builds"
            val buildsResponse = client.get(buildsUrl)
            if (buildsResponse.status != HttpStatusCode.OK) {
                logger.error("Failed to fetch {} builds: HTTP {}", displayName, buildsResponse.status)
                return false
            }
            val buildsData = json.decodeFromString<GeyserBuildsResponse>(buildsResponse.bodyAsText())
            val latestBuild = buildsData.builds.lastOrNull() ?: run {
                logger.error("No builds found for {} {}", displayName, latestVersion)
                return false
            }

            val download = latestBuild.downloads[platform] ?: run {
                logger.error("No {} download for {} (available: {})", platform, displayName, latestBuild.downloads.keys)
                return false
            }

            // Download the JAR
            val downloadUrl = "$projectUrl/versions/$latestVersion/builds/${latestBuild.build}/downloads/$platform"
            val jarResponse = client.get(downloadUrl)
            if (jarResponse.status != HttpStatusCode.OK) {
                logger.error("Failed to download {} {}: HTTP {}", displayName, platform, jarResponse.status)
                return false
            }

            val targetFile = pluginsDir.resolve(download.name)
            Files.write(targetFile, jarResponse.readRawBytes())
            val sizeMb = String.format("%.1f", targetFile.fileSize() / 1024.0 / 1024.0)
            logger.info("Downloaded {} {} v{} build {} ({} MB)", displayName, platform, latestVersion, latestBuild.build, sizeMb)
            true
        } catch (e: Exception) {
            logger.error("Failed to download {} {}: {}", displayName, platform, e.message)
            false
        }
    }

    /**
     * Downloads PacketEvents to the plugins directory if not already present.
     * Required by NimbusPerms on Folia for packet-based name tags.
     */
    suspend fun ensurePacketEventsPlugin(pluginsDir: Path, mcVersion: String): Boolean {
        val hasPacketEvents = pluginsDir.toFile().listFiles()?.any {
            it.name.lowercase().contains("packetevents") && it.name.endsWith(".jar")
        } ?: false
        if (hasPacketEvents) return true
        return downloadFromModrinth("packetevents", "paper", pluginsDir, "PacketEvents", mcVersion)
    }

    /**
     * Downloads a mod from Modrinth by project slug, filtered by game version.
     */
    private suspend fun downloadModrinthMod(projectSlug: String, loader: String, modsDir: Path, displayName: String, mcVersion: String = ""): Boolean {
        return downloadFromModrinth(projectSlug, loader, modsDir, displayName, mcVersion)
    }

    private suspend fun downloadFromModrinth(projectSlug: String, loader: String, targetDir: Path, displayName: String, mcVersion: String): Boolean {
        return try {
            val versionFilter = if (mcVersion.isNotEmpty()) "&game_versions=%5B%22$mcVersion%22%5D" else ""
            val searchUrl = "https://api.modrinth.com/v2/project/$projectSlug/version?loaders=%5B%22$loader%22%5D$versionFilter"
            val response = client.get(searchUrl)
            if (response.status == HttpStatusCode.OK) {
                val versions = json.decodeFromString<List<ModrinthVersionsResponse>>(response.bodyAsText())
                val version = versions.firstOrNull()
                val file = version?.files?.firstOrNull { it.primary } ?: version?.files?.firstOrNull()

                if (file != null) {
                    val jarResponse = client.get(file.url)
                    if (jarResponse.status == HttpStatusCode.OK) {
                        val targetFile = targetDir.resolve(file.filename)
                        Files.write(targetFile, jarResponse.readRawBytes())
                        val sizeMb = String.format("%.1f", targetFile.fileSize() / 1024.0 / 1024.0)
                        logger.info("Auto-installed {} ({}, {} MB)", displayName, file.filename, sizeMb)
                        return true
                    }
                }
            }
            logger.warn("Could not auto-download {} — install manually", displayName)
            false
        } catch (e: Exception) {
            logger.warn("Failed to auto-download {}: {}", displayName, e.message)
            false
        }
    }

    // ── Server JAR downloads ────────────────────────────────────

    suspend fun ensureJarAvailable(
        software: ServerSoftware,
        version: String,
        templateDir: Path,
        modloaderVersion: String = "",
        customJarName: String = ""
    ): Boolean {
        when (software) {
            ServerSoftware.CUSTOM -> {
                val jarName = customJarName.ifEmpty { "server.jar" }
                val jarFile = templateDir.resolve(jarName)
                if (jarFile.exists()) return true
                logger.error("Custom JAR '{}' not found in template dir: {}", jarName, templateDir)
                return false
            }
            ServerSoftware.FORGE -> {
                if (hasServerJar(templateDir, software)) return true
                logger.info("Installing Forge {} for MC {}...", modloaderVersion.ifEmpty { "latest" }, version)
                return installForge(version, modloaderVersion, templateDir)
            }
            ServerSoftware.NEOFORGE -> {
                if (hasServerJar(templateDir, software)) return true
                logger.info("Installing NeoForge {} for MC {}...", modloaderVersion.ifEmpty { "latest" }, version)
                return installNeoForge(version, modloaderVersion, templateDir)
            }
            ServerSoftware.FABRIC -> {
                val jarFile = templateDir.resolve(jarFileName(software))
                if (jarFile.exists()) return true
                logger.info("Installing Fabric for MC {}...", version)
                return installFabric(version, modloaderVersion, templateDir)
            }
            else -> {
                val jarFile = templateDir.resolve(jarFileName(software))
                if (jarFile.exists()) return true
                logger.info("Downloading {} {}...", software, version)
                return downloadJar(software, version, templateDir) != null
            }
        }
    }

    private fun hasServerJar(templateDir: Path, software: ServerSoftware): Boolean {
        val serverJar = templateDir.resolve("server.jar")
        if (serverJar.exists()) return true
        // Check for modded JARs (forge-*.jar, neoforge-*.jar)
        val prefix = when (software) {
            ServerSoftware.FORGE -> "forge-"
            ServerSoftware.NEOFORGE -> "neoforge-"
            else -> return false
        }
        val hasJar = templateDir.toFile().listFiles()?.any {
            it.name.startsWith(prefix) && it.name.endsWith(".jar") && !it.name.contains("installer")
        } ?: false
        if (hasJar) return true
        // Modern Forge/NeoForge: installer creates libraries/ with args file instead of a JAR
        return findArgsFile(templateDir.resolve("libraries")) != null
    }

    suspend fun downloadJar(software: ServerSoftware, version: String, targetDir: Path): Path? {
        return when (software) {
            ServerSoftware.PURPUR -> downloadPurpur(version, targetDir)
            ServerSoftware.PUFFERFISH -> downloadPufferfish(version, targetDir)
            ServerSoftware.LEAF -> downloadLeaf(version, targetDir)
            ServerSoftware.PAPER, ServerSoftware.FOLIA, ServerSoftware.VELOCITY -> downloadPaperMC(software, version, targetDir)
            else -> null
        }
    }

    // ── Modded server installers ──────────────────────────────────

    private suspend fun installForge(mcVersion: String, forgeVersion: String, targetDir: Path): Boolean {
        return try {
            val loaderVer = forgeVersion.ifEmpty {
                val versions = fetchForgeVersions(mcVersion)
                versions.latest ?: run {
                    logger.error("No Forge versions found for MC {}", mcVersion)
                    return false
                }
            }

            val installerUrl = "https://maven.minecraftforge.net/net/minecraftforge/forge/$mcVersion-$loaderVer/forge-$mcVersion-$loaderVer-installer.jar"
            val installerFile = targetDir.resolve("forge-installer.jar")

            Files.createDirectories(targetDir)

            val response = client.get(installerUrl)
            if (response.status != HttpStatusCode.OK) {
                logger.error("Failed to download Forge installer: HTTP {}", response.status)
                return false
            }
            Files.write(installerFile, response.readRawBytes())
            logger.info("Downloaded Forge installer ({} MB)", String.format("%.1f", installerFile.fileSize() / 1024.0 / 1024.0))

            val process = withContext(Dispatchers.IO) {
                ProcessBuilder("java", "-jar", "forge-installer.jar", "--installServer")
                    .directory(targetDir.toFile())
                    .redirectErrorStream(true)
                    .start()
            }

            val output = withContext(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
            val exitCode = withContext(Dispatchers.IO) { process.waitFor() }

            Files.deleteIfExists(installerFile)
            Files.deleteIfExists(targetDir.resolve("forge-installer.jar.log"))

            if (exitCode != 0) {
                logger.error("Forge installer failed (exit code {}): {}", exitCode, output.takeLast(500))
                return false
            }

            logger.info("Forge {}-{} installation complete", mcVersion, loaderVer)
            true
        } catch (e: Exception) {
            logger.error("Failed to install Forge: {}", e.message, e)
            false
        }
    }

    private suspend fun installNeoForge(mcVersion: String, neoforgeVersion: String, targetDir: Path): Boolean {
        return try {
            val loaderVer = neoforgeVersion.ifEmpty {
                val versions = fetchNeoForgeVersions(mcVersion)
                versions.latest ?: run {
                    logger.error("No NeoForge versions found for MC {}", mcVersion)
                    return false
                }
            }

            val installerUrl = "https://maven.neoforged.net/releases/net/neoforged/neoforge/$loaderVer/neoforge-$loaderVer-installer.jar"
            val installerFile = targetDir.resolve("neoforge-installer.jar")

            Files.createDirectories(targetDir)

            val response = client.get(installerUrl)
            if (response.status != HttpStatusCode.OK) {
                logger.error("Failed to download NeoForge installer: HTTP {}", response.status)
                return false
            }
            Files.write(installerFile, response.readRawBytes())
            logger.info("Downloaded NeoForge installer ({} MB)", String.format("%.1f", installerFile.fileSize() / 1024.0 / 1024.0))

            val process = withContext(Dispatchers.IO) {
                ProcessBuilder("java", "-jar", "neoforge-installer.jar", "--install-server")
                    .directory(targetDir.toFile())
                    .redirectErrorStream(true)
                    .start()
            }

            val output = withContext(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
            val exitCode = withContext(Dispatchers.IO) { process.waitFor() }

            Files.deleteIfExists(installerFile)
            Files.deleteIfExists(targetDir.resolve("neoforge-installer.jar.log"))

            if (exitCode != 0) {
                logger.error("NeoForge installer failed (exit code {}): {}", exitCode, output.takeLast(500))
                return false
            }

            logger.info("NeoForge {} installation complete", loaderVer)
            true
        } catch (e: Exception) {
            logger.error("Failed to install NeoForge: {}", e.message, e)
            false
        }
    }

    private suspend fun installFabric(mcVersion: String, loaderVersion: String, targetDir: Path): Boolean {
        return try {
            // Always use the latest stable Fabric loader — it's backwards compatible
            // and avoids conflicts with proxy mods that may require newer versions
            val latestLoader = fetchFabricLoaderVersions().latest
            val loaderVer = latestLoader ?: loaderVersion.ifEmpty {
                logger.error("No Fabric loader versions found")
                return false
            }
            if (loaderVersion.isNotEmpty() && latestLoader != null && latestLoader != loaderVersion) {
                logger.info("Upgrading Fabric loader {} -> {} (latest stable, backwards compatible)", loaderVersion, latestLoader)
            }

            val installerVer = try {
                val response = client.get("https://meta.fabricmc.net/v2/versions/installer")
                if (response.status != HttpStatusCode.OK) throw Exception("HTTP ${response.status}")
                val installers = json.decodeFromString<List<FabricInstallerVersion>>(response.bodyAsText())
                installers.firstOrNull { it.stable }?.version ?: installers.first().version
            } catch (e: Exception) {
                logger.warn("Failed to fetch Fabric installer version, using fallback: {}", e.message)
                "1.0.1"
            }

            val launcherUrl = "https://meta.fabricmc.net/v2/versions/loader/$mcVersion/$loaderVer/$installerVer/server/jar"

            Files.createDirectories(targetDir)

            val response = client.get(launcherUrl)
            if (response.status != HttpStatusCode.OK) {
                logger.error("Failed to download Fabric server: HTTP {}", response.status)
                return false
            }

            val targetFile = targetDir.resolve("server.jar")
            Files.write(targetFile, response.readRawBytes())

            val sizeMb = String.format("%.1f", targetFile.fileSize() / 1024.0 / 1024.0)
            logger.info("Downloaded Fabric server launcher ({} MB) — MC {} / Loader {}", sizeMb, mcVersion, loaderVer)
            true
        } catch (e: Exception) {
            logger.error("Failed to install Fabric: {}", e.message, e)
            false
        }
    }

    // ── Modded startup command ─────────────────────────────────

    fun getModdedStartCommand(software: ServerSoftware, templateDir: Path, customJarName: String = ""): List<String> {
        return when (software) {
            ServerSoftware.CUSTOM -> {
                val jarName = customJarName.ifEmpty { "server.jar" }
                listOf("-jar", jarName)
            }
            ServerSoftware.FORGE, ServerSoftware.NEOFORGE -> {
                // Check for modern Forge/NeoForge with @libraries args file
                val libsDir = templateDir.resolve("libraries")
                val argsFile = findArgsFile(libsDir)
                if (argsFile != null) {
                    listOf("@$argsFile")
                } else {
                    val jar = findModdedJar(templateDir, software)
                    listOf("-jar", jar)
                }
            }
            ServerSoftware.FABRIC -> listOf("-jar", "server.jar")
            else -> listOf("-jar", jarFileName(software))
        }
    }

    private fun findArgsFile(libsDir: Path): String? {
        if (!libsDir.exists()) return null
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val target = if (isWindows) "win_args.txt" else "unix_args.txt"
        return try {
            Files.walk(libsDir, Int.MAX_VALUE, FileVisitOption.FOLLOW_LINKS).use { stream ->
                stream.filter { it.fileName.toString() == target }
                    .findFirst()
                    .map { libsDir.parent.relativize(it).toString() }
                    .orElse(null)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findModdedJar(templateDir: Path, software: ServerSoftware): String {
        val prefix = when (software) {
            ServerSoftware.FORGE -> "forge-"
            ServerSoftware.NEOFORGE -> "neoforge-"
            else -> ""
        }
        val jar = templateDir.toFile().listFiles()?.find {
            it.name.startsWith(prefix) && it.name.endsWith(".jar") && !it.name.contains("installer")
        }
        return jar?.name ?: "server.jar"
    }

    // ── Paper/Purpur/Velocity downloads ─────────────────────────

    private suspend fun downloadPaperMC(software: ServerSoftware, version: String, targetDir: Path): Path? {
        return try {
            val project = when (software) {
                ServerSoftware.PAPER -> "paper"
                ServerSoftware.FOLIA -> "folia"
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
            downloadFile(downloadUrl, targetDir, software, version, "build ${latestBuild.build}", expectedSha256 = downloadEntry.sha256)
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

    private suspend fun downloadPufferfish(version: String, targetDir: Path): Path? {
        return try {
            // Pufferfish CI uses major version branches (1.17, 1.18, 1.19, 1.20, 1.21)
            val majorVersion = version.split(".").take(2).joinToString(".")
            val buildUrl = "https://ci.pufferfish.host/job/Pufferfish-$majorVersion/lastSuccessfulBuild/api/json"

            val buildResponse = client.get(buildUrl)
            if (buildResponse.status != HttpStatusCode.OK) {
                logger.error("Failed to fetch Pufferfish build for {}: HTTP {}", majorVersion, buildResponse.status)
                return null
            }

            val buildData = json.decodeFromString<PufferfishBuildResponse>(buildResponse.bodyAsText())
            val artifact = buildData.artifacts.firstOrNull { it.fileName.endsWith(".jar") } ?: run {
                logger.error("No JAR artifact found for Pufferfish {}", majorVersion)
                return null
            }

            val downloadUrl = "${buildData.url}artifact/${artifact.relativePath}"
            val jarResponse = client.get(downloadUrl)
            if (jarResponse.status != HttpStatusCode.OK) {
                logger.error("Failed to download Pufferfish {}: HTTP {}", majorVersion, jarResponse.status)
                return null
            }

            Files.createDirectories(targetDir)
            val targetFile = targetDir.resolve("server.jar")
            Files.write(targetFile, jarResponse.readRawBytes())

            val sizeMb = String.format("%.1f", targetFile.fileSize() / 1024.0 / 1024.0)
            logger.info("Downloaded Pufferfish {} ({} MB)", artifact.fileName, sizeMb)
            targetFile
        } catch (e: Exception) {
            logger.error("Failed to download Pufferfish {}: {}", version, e.message, e)
            null
        }
    }

    private suspend fun downloadLeaf(version: String, targetDir: Path): Path? {
        return try {
            val buildsUrl = "https://api.leafmc.one/v2/projects/leaf/versions/$version/builds"

            val buildsResponse = client.get(buildsUrl)
            if (buildsResponse.status != HttpStatusCode.OK) {
                logger.error("Failed to fetch Leaf builds for {}: HTTP {}", version, buildsResponse.status)
                return null
            }

            val builds = json.decodeFromString<PaperBuildsResponse>(buildsResponse.bodyAsText())
            if (builds.builds.isEmpty()) {
                logger.error("No builds found for Leaf {}", version)
                return null
            }

            val latestBuild = builds.builds.last()
            val downloadEntry = latestBuild.downloads["application"] ?: run {
                logger.error("No application download for Leaf {} build {}", version, latestBuild.build)
                return null
            }

            val downloadUrl = "https://api.leafmc.one/v2/projects/leaf/versions/$version/builds/${latestBuild.build}/downloads/${downloadEntry.name}"
            downloadFile(downloadUrl, targetDir, ServerSoftware.LEAF, version, "build ${latestBuild.build}", expectedSha256 = downloadEntry.sha256)
        } catch (e: Exception) {
            logger.error("Failed to download Leaf {}: {}", version, e.message, e)
            null
        }
    }

    private suspend fun downloadFile(url: String, targetDir: Path, software: ServerSoftware, version: String, buildInfo: String, expectedSha256: String? = null): Path? {
        val jarResponse = client.get(url)
        if (jarResponse.status != HttpStatusCode.OK) {
            logger.error("Failed to download {} {} {}: HTTP {}", software, version, buildInfo, jarResponse.status)
            return null
        }

        Files.createDirectories(targetDir)
        val targetFile = targetDir.resolve(jarFileName(software))
        val bytes = jarResponse.readRawBytes()

        // Verify SHA-256 checksum if provided by the API
        if (!expectedSha256.isNullOrBlank()) {
            val actualSha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                logger.error("SHA-256 mismatch for {} {} {}! Expected: {}, got: {}. Download rejected.",
                    software, version, buildInfo, expectedSha256, actualSha256)
                return null
            }
            logger.debug("SHA-256 verified for {} {} {}", software, version, buildInfo)
        }

        Files.write(targetFile, bytes)

        val sizeMb = String.format("%.1f", targetFile.fileSize() / 1024.0 / 1024.0)
        logger.info("Downloaded {} {} {} ({} MB)", software, version, buildInfo, sizeMb)
        return targetFile
    }

    fun jarFileName(software: ServerSoftware): String = when (software) {
        ServerSoftware.VELOCITY -> "velocity.jar"
        else -> "server.jar"
    }

    fun close() {
        client.close()
    }
}
