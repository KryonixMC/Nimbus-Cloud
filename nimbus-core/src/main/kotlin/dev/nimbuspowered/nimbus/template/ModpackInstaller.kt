package dev.nimbuspowered.nimbus.template

import dev.nimbuspowered.nimbus.config.ServerSoftware
import io.ktor.client.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.exists

// ── Modrinth .mrpack index models ──────────────────────────

@Serializable
data class MrpackIndex(
    val formatVersion: Int = 1,
    val game: String = "minecraft",
    val versionId: String = "",
    val name: String = "",
    val summary: String = "",
    val files: List<MrpackFile> = emptyList(),
    val dependencies: Map<String, String> = emptyMap()
)

@Serializable
data class MrpackFile(
    val path: String,
    val hashes: Map<String, String> = emptyMap(),
    val env: MrpackEnv? = null,
    val downloads: List<String> = emptyList(),
    val fileSize: Long = 0
)

@Serializable
data class MrpackEnv(
    val client: String = "required",
    val server: String = "required"
)

// ── Install result ─────────────────────────────────────────

/** Source type for resolved modpacks. */
enum class ModpackSource {
    MODRINTH,       // .mrpack from Modrinth
    CURSEFORGE_API, // Downloaded via CurseForge API
    SERVER_PACK     // Pre-built server pack ZIP (e.g. CurseForge server files)
}

data class ModpackInfo(
    val name: String,
    val version: String,
    val mcVersion: String,
    val modloader: ServerSoftware,
    val modloaderVersion: String,
    val totalFiles: Int,
    val serverFiles: Int,
    val source: ModpackSource = ModpackSource.MODRINTH
)

data class InstallResult(
    val success: Boolean,
    val filesDownloaded: Int,
    val filesFailed: Int,
    val hashMismatches: List<String> = emptyList()
)

// ── ModpackInstaller ───────────────────────────────────────

class ModpackInstaller(private val client: HttpClient, private val curseForgeApiKey: String = "") {

    private val json = Json { ignoreUnknownKeys = true }

    private val mrpack = MrpackInstaller(client, json)
    private val curseForge = CurseForgeInstaller(client, curseForgeApiKey, json)
    private val serverPack = ServerPackInstaller()

    val hasCurseForgeKey: Boolean get() = curseForgeApiKey.isNotBlank()

    /**
     * Resolves a modpack source (URL, slug, or local path) to a local file.
     * Supports: .mrpack, .zip (server pack), Modrinth URLs/slugs, CurseForge URLs/slugs.
     */
    suspend fun resolve(input: String, downloadDir: Path): Path? {
        if (input.endsWith(".mrpack") && Path.of(input).exists()) {
            return Path.of(input)
        }

        if (input.endsWith(".zip") && Path.of(input).exists()) {
            return Path.of(input)
        }

        val cfSlug = curseForge.extractSlug(input)
        if (cfSlug != null) {
            return curseForge.download(cfSlug, downloadDir)
        }

        if (input.startsWith("curseforge:")) {
            val slug = input.removePrefix("curseforge:").trim()
            return curseForge.download(slug, downloadDir)
        }

        val mrSlug = mrpack.extractSlug(input)
        if (mrSlug != null) {
            return mrpack.download(mrSlug, downloadDir)
        }

        val modrinth = mrpack.download(input.trim(), downloadDir)
        if (modrinth != null) return modrinth

        if (hasCurseForgeKey) {
            val cf = curseForge.download(input.trim(), downloadDir)
            if (cf != null) return cf
        }

        return null
    }

    /**
     * Checks if a ZIP file is a pre-built server pack (contains mods/ and startup scripts/installers).
     */
    fun isServerPack(zipPath: Path): Boolean = serverPack.isServerPack(zipPath)

    /**
     * Detects modloader, MC version, and mod count from a server pack ZIP.
     */
    fun getServerPackInfo(zipPath: Path): ModpackInfo? = serverPack.getInfo(zipPath)

    /**
     * Extracts all files from a server pack ZIP to the template directory.
     * Skips installer JARs and startup scripts (Nimbus manages its own startup).
     */
    fun extractServerPack(zipPath: Path, templateDir: Path) = serverPack.extract(zipPath, templateDir)

    /**
     * Parses the modrinth.index.json from a .mrpack ZIP file.
     */
    fun parseIndex(mrpackPath: Path): MrpackIndex? = mrpack.parseIndex(mrpackPath)

    /**
     * Extracts modpack info (name, MC version, modloader) from the parsed index.
     */
    fun getInfo(index: MrpackIndex): ModpackInfo = mrpack.getInfo(index)

    /**
     * Downloads all server-side mods and files from the modpack.
     */
    suspend fun installFiles(
        index: MrpackIndex,
        templateDir: Path,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit
    ): InstallResult = mrpack.installFiles(index, templateDir, onProgress)

    /**
     * Extracts overrides/ and server-overrides/ from the .mrpack to the template directory.
     */
    fun extractOverrides(mrpackPath: Path, templateDir: Path) = mrpack.extractOverrides(mrpackPath, templateDir)

    /**
     * Resolves modloader type and version from mrpack dependencies.
     */
    fun resolveModloader(dependencies: Map<String, String>): Pair<ServerSoftware, String> =
        mrpack.resolveModloader(dependencies)

    fun close() {
        client.close()
    }
}
