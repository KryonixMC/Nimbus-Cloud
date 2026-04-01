package dev.kryonix.nimbus.template

import dev.kryonix.nimbus.config.ServerSoftware
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes

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

// ── Modrinth API models ────────────────────────────────────

@Serializable
private data class ModrinthProject(
    val slug: String = "",
    val title: String = "",
    @SerialName("project_type")
    val projectType: String = "",
    val id: String = ""
)

@Serializable
private data class ModrinthVersionEntry(
    val id: String = "",
    @SerialName("version_number")
    val versionNumber: String = "",
    val name: String = "",
    val files: List<ModrinthVersionFile> = emptyList(),
    @SerialName("game_versions")
    val gameVersions: List<String> = emptyList(),
    val loaders: List<String> = emptyList()
)

@Serializable
private data class ModrinthVersionFile(
    val url: String = "",
    val filename: String = "",
    val primary: Boolean = false,
    val size: Long = 0
)

// ── Install result ─────────────────────────────────────────

data class ModpackInfo(
    val name: String,
    val version: String,
    val mcVersion: String,
    val modloader: ServerSoftware,
    val modloaderVersion: String,
    val totalFiles: Int,
    val serverFiles: Int
)

data class InstallResult(
    val success: Boolean,
    val filesDownloaded: Int,
    val filesFailed: Int,
    val hashMismatches: List<String> = emptyList()
)

// ── ModpackInstaller ───────────────────────────────────────

class ModpackInstaller(private val client: HttpClient) {

    private val logger = LoggerFactory.getLogger(ModpackInstaller::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    // Max parallel downloads
    private val downloadSemaphore = Semaphore(8)

    /**
     * Resolves a modpack source (URL, slug, or local path) to a local .mrpack file.
     * Downloads from Modrinth if needed.
     */
    suspend fun resolve(input: String, downloadDir: Path): Path? {
        // Local file
        if (input.endsWith(".mrpack") && Path.of(input).exists()) {
            return Path.of(input)
        }

        // Modrinth URL: https://modrinth.com/modpack/<slug> or .../version/<id>
        val slug = extractSlug(input)
        if (slug != null) {
            return downloadFromModrinth(slug, downloadDir)
        }

        // Try as slug directly
        return downloadFromModrinth(input.trim(), downloadDir)
    }

    /**
     * Extracts the slug from a Modrinth URL.
     */
    private fun extractSlug(input: String): String? {
        val patterns = listOf(
            Regex("""modrinth\.com/modpack/([a-zA-Z0-9_-]+)"""),
            Regex("""modrinth\.com/mod/([a-zA-Z0-9_-]+)""")
        )
        for (p in patterns) {
            val match = p.find(input)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    /**
     * Downloads the latest .mrpack from Modrinth for a given project slug.
     */
    private suspend fun downloadFromModrinth(slug: String, downloadDir: Path): Path? {
        return try {
            // Verify project exists and is a modpack
            val projectResponse = client.get("https://api.modrinth.com/v2/project/$slug")
            if (projectResponse.status != HttpStatusCode.OK) {
                logger.error("Modrinth project '{}' not found (HTTP {})", slug, projectResponse.status)
                return null
            }
            val project = json.decodeFromString<ModrinthProject>(projectResponse.bodyAsText())
            if (project.projectType != "modpack") {
                logger.error("'{}' is a {} — expected a modpack", slug, project.projectType)
                return null
            }

            // Get latest version
            val versionsResponse = client.get("https://api.modrinth.com/v2/project/$slug/version")
            if (versionsResponse.status != HttpStatusCode.OK) {
                logger.error("Failed to fetch versions for '{}'", slug)
                return null
            }
            val versions = json.decodeFromString<List<ModrinthVersionEntry>>(versionsResponse.bodyAsText())
            val latest = versions.firstOrNull() ?: run {
                logger.error("No versions found for '{}'", slug)
                return null
            }

            // Find the .mrpack file
            val mrpackFile = latest.files.firstOrNull { it.filename.endsWith(".mrpack") }
                ?: latest.files.firstOrNull { it.primary }
                ?: latest.files.firstOrNull()

            if (mrpackFile == null) {
                logger.error("No downloadable file found for '{}' {}", slug, latest.versionNumber)
                return null
            }

            // Download
            if (!downloadDir.exists()) downloadDir.createDirectories()
            val targetFile = downloadDir.resolve(mrpackFile.filename)

            logger.info("Downloading {} ({})...", mrpackFile.filename, formatSize(mrpackFile.size))
            val dlResponse = client.get(mrpackFile.url)
            if (dlResponse.status != HttpStatusCode.OK) {
                logger.error("Download failed: HTTP {}", dlResponse.status)
                return null
            }
            targetFile.writeBytes(dlResponse.readRawBytes())
            logger.info("Downloaded {}", mrpackFile.filename)
            targetFile
        } catch (e: Exception) {
            logger.error("Failed to resolve modpack '{}': {}", slug, e.message)
            null
        }
    }

    /**
     * Parses the modrinth.index.json from a .mrpack ZIP file.
     */
    fun parseIndex(mrpackPath: Path): MrpackIndex? {
        return try {
            ZipFile(mrpackPath.toFile()).use { zip ->
                val entry = zip.getEntry("modrinth.index.json")
                    ?: throw IllegalArgumentException("Not a valid .mrpack — missing modrinth.index.json")
                val content = zip.getInputStream(entry).bufferedReader().readText()
                json.decodeFromString<MrpackIndex>(content)
            }
        } catch (e: Exception) {
            logger.error("Failed to parse .mrpack: {}", e.message)
            null
        }
    }

    /**
     * Extracts modpack info (name, MC version, modloader) from the parsed index.
     */
    fun getInfo(index: MrpackIndex): ModpackInfo {
        val mcVersion = index.dependencies["minecraft"] ?: "unknown"
        val (modloader, loaderVersion) = resolveModloader(index.dependencies)
        val serverFiles = index.files.filter { it.env?.server != "unsupported" }

        return ModpackInfo(
            name = index.name,
            version = index.versionId,
            mcVersion = mcVersion,
            modloader = modloader,
            modloaderVersion = loaderVersion,
            totalFiles = index.files.size,
            serverFiles = serverFiles.size
        )
    }

    /**
     * Downloads all server-side mods and files from the modpack.
     */
    suspend fun installFiles(
        index: MrpackIndex,
        templateDir: Path,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit
    ): InstallResult {
        val serverFiles = index.files.filter { it.env?.server != "unsupported" }
        val total = serverFiles.size
        var downloaded = 0
        var failed = 0
        val hashMismatches = mutableListOf<String>()

        coroutineScope {
            val jobs = serverFiles.mapIndexed { idx, file ->
                async {
                    downloadSemaphore.withPermit {
                        val success = downloadFile(file, templateDir)
                        synchronized(this@ModpackInstaller) {
                            if (success) {
                                downloaded++
                            } else {
                                failed++
                            }
                            onProgress(downloaded + failed, total, file.path.substringAfterLast("/"))
                        }
                        success
                    }
                }
            }
            jobs.awaitAll()
        }

        return InstallResult(
            success = failed == 0,
            filesDownloaded = downloaded,
            filesFailed = failed,
            hashMismatches = hashMismatches
        )
    }

    private suspend fun downloadFile(file: MrpackFile, templateDir: Path): Boolean {
        val targetPath = templateDir.resolve(file.path).normalize()
        if (!targetPath.startsWith(templateDir.normalize())) {
            logger.warn("Path traversal blocked in modpack file: {}", file.path)
            return false
        }
        val targetDir = targetPath.parent
        if (!targetDir.exists()) targetDir.createDirectories()

        val url = file.downloads.firstOrNull() ?: return false

        return try {
            val response = client.get(url)
            if (response.status != HttpStatusCode.OK) {
                logger.warn("Failed to download {}: HTTP {}", file.path, response.status)
                return false
            }

            val bytes = response.readRawBytes()

            // Verify hash
            val expectedSha1 = file.hashes["sha1"]
            if (expectedSha1 != null) {
                val actualSha1 = MessageDigest.getInstance("SHA-1").digest(bytes).toHexString()
                if (actualSha1 != expectedSha1) {
                    logger.warn("Hash mismatch for {}: expected {} got {}", file.path, expectedSha1, actualSha1)
                    // Try once more
                    val retryResponse = client.get(url)
                    if (retryResponse.status == HttpStatusCode.OK) {
                        val retryBytes = retryResponse.readRawBytes()
                        val retrySha1 = MessageDigest.getInstance("SHA-1").digest(retryBytes).toHexString()
                        if (retrySha1 == expectedSha1) {
                            targetPath.writeBytes(retryBytes)
                            return true
                        }
                    }
                    return false
                }
            }

            targetPath.writeBytes(bytes)
            true
        } catch (e: Exception) {
            logger.warn("Failed to download {}: {}", file.path, e.message)
            false
        }
    }

    /**
     * Extracts overrides/ and server-overrides/ from the .mrpack to the template directory.
     */
    fun extractOverrides(mrpackPath: Path, templateDir: Path) {
        ZipFile(mrpackPath.toFile()).use { zip ->
            val entries = zip.entries().toList()

            // Extract overrides/ (shared between client and server)
            extractPrefix(zip, entries, "overrides/", templateDir)

            // Extract server-overrides/ (server-specific, takes precedence)
            extractPrefix(zip, entries, "server-overrides/", templateDir)
        }
    }

    private fun extractPrefix(zip: ZipFile, entries: List<java.util.zip.ZipEntry>, prefix: String, targetDir: Path) {
        val normalizedTargetDir = targetDir.normalize()
        for (entry in entries) {
            if (!entry.name.startsWith(prefix) || entry.name == prefix) continue

            val relativePath = entry.name.removePrefix(prefix)
            if (relativePath.isEmpty()) continue

            val target = targetDir.resolve(relativePath).normalize()
            if (!target.startsWith(normalizedTargetDir)) {
                logger.warn("Path traversal blocked in modpack override: {}", entry.name)
                continue
            }

            if (entry.isDirectory) {
                if (!target.exists()) target.createDirectories()
            } else {
                val parent = target.parent
                if (!parent.exists()) parent.createDirectories()
                zip.getInputStream(entry).use { input ->
                    Files.write(target, input.readBytes())
                }
            }
        }
    }

    /**
     * Resolves modloader type and version from mrpack dependencies.
     */
    fun resolveModloader(dependencies: Map<String, String>): Pair<ServerSoftware, String> {
        return when {
            "fabric-loader" in dependencies -> ServerSoftware.FABRIC to (dependencies["fabric-loader"] ?: "")
            "forge" in dependencies -> ServerSoftware.FORGE to (dependencies["forge"] ?: "")
            "neoforge" in dependencies -> ServerSoftware.NEOFORGE to (dependencies["neoforge"] ?: "")
            "quilt-loader" in dependencies -> {
                logger.warn("Quilt modloader detected — using Fabric as fallback (most Quilt mods are compatible)")
                ServerSoftware.FABRIC to (dependencies["quilt-loader"] ?: "")
            }
            else -> ServerSoftware.CUSTOM to ""
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> String.format("%.1fMB", bytes / 1024.0 / 1024.0)
    }
}
