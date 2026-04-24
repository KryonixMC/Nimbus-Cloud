package dev.nimbuspowered.nimbus.template

import dev.nimbuspowered.nimbus.config.ServerSoftware
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize

internal class ProxyModManager(
    private val client: HttpClient,
    private val json: Json
) {
    private val logger = LoggerFactory.getLogger(ProxyModManager::class.java)

    // ── Via plugin downloads ────────────────────────────────────

    /**
     * Downloads a Via plugin JAR into the template's plugins/ directory.
     * Uses the Hangar API (PaperMC's plugin repository).
     */
    suspend fun downloadViaPlugin(plugin: SoftwareResolver.ViaPlugin, templateDir: Path, platform: String = "PAPER"): Boolean {
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
            jarResponse.bodyAsChannel().toInputStream().use { input ->
                Files.newOutputStream(targetFile).use { out -> input.copyTo(out, 65536) }
            }

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
     * - NeoForge 1.20.2+: NeoForwarding (dedicated NeoForge support, includes CrossStitch)
     * - Forge (all versions) / older NeoForge: proxy-compatible-forge
     *
     * If a forwarding mod from the "wrong" family is present (e.g. PCF when the version
     * now calls for NeoForwarding), it is removed before installing the correct one.
     */
    suspend fun ensureForwardingMod(software: ServerSoftware, mcVersion: String, templateDir: Path) {
        val modsDir = templateDir.resolve("mods")
        if (!modsDir.exists()) modsDir.createDirectories()

        val useNeoForwarding = software == ServerSoftware.NEOFORGE && isVersionAtLeast(mcVersion, "1.20.2")
        val expectedNeedle = if (useNeoForwarding) "neoforwarding" else "proxy-compatible"

        // Short-circuit if the correct mod is already installed
        val hasExpectedMod = modsDir.toFile().listFiles()?.any {
            val name = it.name.lowercase()
            name.contains(expectedNeedle) && name.endsWith(".jar")
        } ?: false
        if (hasExpectedMod) return

        // Remove any stale forwarding mods from the wrong family (e.g. PCF when we now need NeoForwarding)
        modsDir.toFile().listFiles()?.filter {
            val name = it.name.lowercase()
            if (!name.endsWith(".jar")) return@filter false
            val isForwarding = name.contains("proxy-compatible") || name.contains("bungeeforge")
                || name.contains("neovelocity") || name.contains("neoforwarding")
            isForwarding && !name.contains(expectedNeedle)
        }?.forEach {
            if (it.delete()) logger.info("Removed outdated forwarding mod: {}", it.name)
        }

        if (useNeoForwarding) {
            // NeoForge 1.20.2+: use NeoForwarding (better compatibility than PCF)
            downloadModrinthMod("neoforwarding", "neoforge", modsDir, "NeoForwarding", mcVersion)
        } else {
            val loader = when (software) {
                ServerSoftware.FORGE -> "forge"
                ServerSoftware.NEOFORGE -> "neoforge"
                else -> return
            }
            downloadModrinthMod("proxy-compatible-forge", loader, modsDir, "Proxy Compatible Forge", mcVersion)
        }
    }

    /**
     * Removes Forge/NeoForge proxy forwarding mods from a directory.
     * Safe to call when the mods directory or the mod doesn't exist.
     */
    fun removeForwardingMod(templateDir: Path) {
        val modsDir = templateDir.resolve("mods")
        if (!modsDir.exists()) return
        val removed = modsDir.toFile().listFiles()?.filter {
            val name = it.name.lowercase()
            (name.contains("proxy-compatible") || name.contains("bungeeforge")
                || name.contains("neovelocity") || name.contains("neoforwarding"))
                && name.endsWith(".jar")
        } ?: emptyList()
        for (file in removed) {
            if (file.delete()) logger.info("Removed proxy forwarding mod: {}", file.name)
        }
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
     * Removes FabricProxy-Lite from a directory. Fabric API is left in place
     * because it's a general dependency used by many non-proxy mods.
     */
    fun removeFabricProxyMod(templateDir: Path) {
        val modsDir = templateDir.resolve("mods")
        if (!modsDir.exists()) return
        val removed = modsDir.toFile().listFiles()?.filter {
            val name = it.name.lowercase()
            (name.contains("fabricproxy") || name.contains("proxy-lite"))
                && name.endsWith(".jar")
        } ?: emptyList()
        for (file in removed) {
            if (file.delete()) logger.info("Removed proxy forwarding mod: {}", file.name)
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
            val iCommonOk = downloadModrinthMod("icommon", "fabric", modsDir, "iCommon API", mcVersion)
            if (!iCommonOk) {
                logger.error("Failed to download iCommon dependency for Cardboard — Cardboard may not work correctly")
            }
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
            jarResponse.bodyAsChannel().toInputStream().use { input ->
                Files.newOutputStream(targetFile).use { out -> input.copyTo(out, 65536) }
            }
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
                        jarResponse.bodyAsChannel().toInputStream().use { input ->
                            Files.newOutputStream(targetFile).use { out -> input.copyTo(out, 65536) }
                        }
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
}
