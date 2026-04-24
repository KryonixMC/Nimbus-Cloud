package dev.nimbuspowered.nimbus.template

import dev.nimbuspowered.nimbus.config.ServerSoftware
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Thrown when a requested server version is not offered by the upstream API
 * (e.g. user typed "1.99.9" for Paper). Carries the known-versions list so
 * callers can surface a helpful message to the user.
 */
class UnknownServerVersionException(
    val software: ServerSoftware,
    val requestedVersion: String,
    val knownVersions: List<String>
) : RuntimeException(buildMessage(software, requestedVersion, knownVersions)) {
    companion object {
        private fun buildMessage(software: ServerSoftware, requested: String, known: List<String>): String {
            val preview = known.take(10).joinToString(", ")
            val more = if (known.size > 10) " (+${known.size - 10} more)" else ""
            val hint = if (known.isEmpty()) "upstream API returned no versions" else "known: $preview$more"
            return "Unknown $software version '$requested' — $hint"
        }
    }
}

/** Compares Minecraft version strings (e.g. "1.20.2" >= "1.20.2"). */
internal fun isVersionAtLeast(version: String, minimum: String): Boolean {
    val v = version.split(".").map { it.toIntOrNull() ?: 0 }
    val m = minimum.split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(v.size, m.size)) {
        val a = v.getOrElse(i) { 0 }
        val b = m.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return true // equal
}

class SoftwareResolver {

    private val logger = LoggerFactory.getLogger(SoftwareResolver::class.java)

    val client = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 300_000  // 5 min for server JAR downloads
            socketTimeoutMillis = 30_000
        }
    }
    private val json = Json { ignoreUnknownKeys = true }

    private val paperFamily = PaperFamilyResolver(client, json)
    private val modded = ModdedServerInstaller(client, json)
    private val proxyMods = ProxyModManager(client, json)

    // ── Version fetching ────────────────────────────────────────

    /**
     * Fetches available versions from PaperMC API.
     * Returns versions sorted newest-first, with pre-releases/RCs separated.
     */
    suspend fun fetchPaperVersions(): VersionList = paperFamily.fetchPaperVersions()

    /**
     * Fetches available Folia versions from PaperMC API.
     * Folia only supports 1.19.4+.
     */
    suspend fun fetchFoliaVersions(): VersionList = paperFamily.fetchFoliaVersions()

    /**
     * Fetches available versions from Purpur API.
     */
    suspend fun fetchPurpurVersions(): VersionList = paperFamily.fetchPurpurVersions()

    /**
     * Fetches available Pufferfish versions from the CI server.
     * Pufferfish uses Jenkins CI with jobs named Pufferfish-{majorVersion}.
     */
    suspend fun fetchPufferfishVersions(): VersionList = paperFamily.fetchPufferfishVersions()

    /**
     * Fetches available Leaf versions from the Leaf API.
     * Leaf uses a PaperMC-compatible API at api.leafmc.one.
     */
    suspend fun fetchLeafVersions(): VersionList = paperFamily.fetchLeafVersions()

    /**
     * Fetches available Velocity versions.
     */
    suspend fun fetchVelocityVersions(): VersionList = paperFamily.fetchVelocityVersions()

    // ── Forge version fetching ─────────────────────────────────────

    suspend fun fetchForgeVersions(mcVersion: String): VersionList = modded.fetchForgeVersions(mcVersion)

    suspend fun fetchForgeGameVersions(): VersionList = modded.fetchForgeGameVersions()

    // ── NeoForge version fetching ───────────────────────────────

    suspend fun fetchNeoForgeVersions(mcVersion: String): VersionList = modded.fetchNeoForgeVersions(mcVersion)

    suspend fun fetchNeoForgeGameVersions(): VersionList = modded.fetchNeoForgeGameVersions()

    // ── Fabric version fetching ─────────────────────────────────

    suspend fun fetchFabricLoaderVersions(): VersionList = modded.fetchFabricLoaderVersions()

    suspend fun fetchFabricGameVersions(): VersionList = modded.fetchFabricGameVersions()

    // ── VersionList ─────────────────────────────────────────────

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
    suspend fun downloadViaPlugin(plugin: ViaPlugin, templateDir: Path, platform: String = "PAPER"): Boolean =
        proxyMods.downloadViaPlugin(plugin, templateDir, platform)

    // ── Proxy forwarding mod downloads ─────────────────────────

    /**
     * Auto-downloads the correct proxy forwarding mod for Forge/NeoForge servers.
     */
    suspend fun ensureForwardingMod(software: ServerSoftware, mcVersion: String, templateDir: Path) =
        proxyMods.ensureForwardingMod(software, mcVersion, templateDir)

    /**
     * Removes Forge/NeoForge proxy forwarding mods from a directory.
     */
    fun removeForwardingMod(templateDir: Path) = proxyMods.removeForwardingMod(templateDir)

    /**
     * Auto-downloads FabricProxy-Lite and its dependency Fabric API for Fabric servers.
     */
    suspend fun ensureFabricProxyMod(templateDir: Path, mcVersion: String) =
        proxyMods.ensureFabricProxyMod(templateDir, mcVersion)

    /**
     * Removes FabricProxy-Lite from a directory.
     */
    fun removeFabricProxyMod(templateDir: Path) = proxyMods.removeFabricProxyMod(templateDir)

    /**
     * Auto-downloads Cardboard mod and its dependency iCommon for Fabric servers.
     */
    suspend fun ensureCardboardMod(templateDir: Path, mcVersion: String): Boolean =
        proxyMods.ensureCardboardMod(templateDir, mcVersion)

    // ── Bedrock plugin downloads (Geyser + Floodgate) ───────────

    /**
     * Downloads Geyser (Velocity plugin) to the template's plugins/ directory.
     */
    suspend fun ensureGeyserPlugin(templateDir: Path): Boolean = proxyMods.ensureGeyserPlugin(templateDir)

    /**
     * Downloads Floodgate to the template's plugins/ directory.
     */
    suspend fun ensureFloodgatePlugin(templateDir: Path, platform: String): Boolean =
        proxyMods.ensureFloodgatePlugin(templateDir, platform)

    /**
     * Downloads PacketEvents to the plugins directory if not already present.
     */
    suspend fun ensurePacketEventsPlugin(pluginsDir: Path, mcVersion: String): Boolean =
        proxyMods.ensurePacketEventsPlugin(pluginsDir, mcVersion)

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
                if (jarFile.toFile().exists()) return true
                logger.error("Custom JAR '{}' not found in template dir: {}", jarName, templateDir)
                return false
            }
            ServerSoftware.FORGE -> {
                if (modded.hasServerJar(templateDir, software)) return true
                logger.info("Installing Forge {} for MC {}...", modloaderVersion.ifEmpty { "latest" }, version)
                return modded.installForge(version, modloaderVersion, templateDir)
            }
            ServerSoftware.NEOFORGE -> {
                if (modded.hasServerJar(templateDir, software)) return true
                logger.info("Installing NeoForge {} for MC {}...", modloaderVersion.ifEmpty { "latest" }, version)
                return modded.installNeoForge(version, modloaderVersion, templateDir)
            }
            ServerSoftware.FABRIC -> {
                val jarFile = templateDir.resolve(jarFileName(software))
                if (jarFile.toFile().exists()) return true
                logger.info("Installing Fabric for MC {}...", version)
                return modded.installFabric(version, modloaderVersion, templateDir)
            }
            else -> {
                val jarFile = templateDir.resolve(jarFileName(software))
                if (jarFile.toFile().exists()) return true
                logger.info("Downloading {} {}...", software, version)
                return downloadJar(software, version, templateDir) != null
            }
        }
    }

    suspend fun downloadJar(software: ServerSoftware, version: String, targetDir: Path): Path? =
        paperFamily.downloadJar(software, version, targetDir)

    /**
     * Verifies that [version] is offered by the upstream API for [software].
     * Throws [UnknownServerVersionException] if not. For Pufferfish the check
     * is against the major branch (e.g. "1.21") because that's all the CI exposes.
     * A fetch failure (empty list) is treated as "skip validation" rather than
     * a hard fail — offline / upstream-down shouldn't block a known-good version.
     */
    suspend fun verifyVersionSupported(software: ServerSoftware, version: String) {
        val list = when (software) {
            ServerSoftware.PAPER -> fetchPaperVersions()
            ServerSoftware.FOLIA -> fetchFoliaVersions()
            ServerSoftware.VELOCITY -> fetchVelocityVersions()
            ServerSoftware.PURPUR -> fetchPurpurVersions()
            ServerSoftware.LEAF -> fetchLeafVersions()
            ServerSoftware.PUFFERFISH -> fetchPufferfishVersions()
            else -> return
        }
        if (list.all.isEmpty()) {
            logger.warn("Version list for {} is empty — skipping validation (upstream API unreachable?)", software)
            return
        }
        val needle = if (software == ServerSoftware.PUFFERFISH) {
            version.split(".").take(2).joinToString(".")
        } else version
        if (needle !in list.all) {
            throw UnknownServerVersionException(software, version, list.all)
        }
    }

    // ── Modded startup command ─────────────────────────────────

    fun getModdedStartCommand(software: ServerSoftware, templateDir: Path, customJarName: String = ""): List<String> =
        modded.getModdedStartCommand(software, templateDir, customJarName)

    fun jarFileName(software: ServerSoftware): String = when (software) {
        ServerSoftware.VELOCITY -> "velocity.jar"
        else -> "server.jar"
    }

    fun close() {
        client.close()
    }
}
