package dev.nimbuspowered.nimbus.template

import dev.nimbuspowered.nimbus.config.ServerSoftware
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
import kotlin.io.path.name
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

// ── CurseForge API models ──────────────────────────────────

@Serializable
private data class CurseForgeResponse<T>(val data: T)

@Serializable
private data class CurseForgeSearchResult(
    val id: Int = 0,
    val name: String = "",
    val slug: String = "",
    @SerialName("classId")
    val classId: Int = 0,  // 4471 = modpack
    @SerialName("latestFilesIndexes")
    val latestFilesIndexes: List<CurseForgeFileIndex> = emptyList()
)

@Serializable
private data class CurseForgeFileIndex(
    @SerialName("fileId")
    val fileId: Int = 0,
    @SerialName("gameVersion")
    val gameVersion: String = "",
    val filename: String = ""
)

@Serializable
private data class CurseForgeFile(
    val id: Int = 0,
    @SerialName("displayName")
    val displayName: String = "",
    val fileName: String = "",
    @SerialName("downloadUrl")
    val downloadUrl: String? = null,
    @SerialName("serverPackFileId")
    val serverPackFileId: Int? = null,
    @SerialName("fileLength")
    val fileLength: Long = 0,
    @SerialName("gameVersions")
    val gameVersions: List<String> = emptyList()
)

@Serializable
private data class CurseForgeModpack(
    val id: Int = 0,
    val name: String = "",
    val slug: String = "",
    @SerialName("classId")
    val classId: Int = 0
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

    private val logger = LoggerFactory.getLogger(ModpackInstaller::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    // Max parallel downloads
    private val downloadSemaphore = Semaphore(8)

    val hasCurseForgeKey: Boolean get() = curseForgeApiKey.isNotBlank()

    /**
     * Resolves a modpack source (URL, slug, or local path) to a local file.
     * Supports: .mrpack, .zip (server pack), Modrinth URLs/slugs, CurseForge URLs/slugs.
     */
    suspend fun resolve(input: String, downloadDir: Path): Path? {
        // Local .mrpack file
        if (input.endsWith(".mrpack") && Path.of(input).exists()) {
            return Path.of(input)
        }

        // Local .zip file (server pack)
        if (input.endsWith(".zip") && Path.of(input).exists()) {
            return Path.of(input)
        }

        // CurseForge URL
        val cfSlug = extractCurseForgeSlug(input)
        if (cfSlug != null) {
            return downloadFromCurseForge(cfSlug, downloadDir)
        }

        // CurseForge prefix: curseforge:slug
        if (input.startsWith("curseforge:")) {
            val slug = input.removePrefix("curseforge:").trim()
            return downloadFromCurseForge(slug, downloadDir)
        }

        // Modrinth URL
        val mrSlug = extractModrinthSlug(input)
        if (mrSlug != null) {
            return downloadFromModrinth(mrSlug, downloadDir)
        }

        // Try as Modrinth slug first (most common), then CurseForge
        val modrinth = downloadFromModrinth(input.trim(), downloadDir)
        if (modrinth != null) return modrinth

        // Fallback to CurseForge if API key is available
        if (hasCurseForgeKey) {
            val cf = downloadFromCurseForge(input.trim(), downloadDir)
            if (cf != null) return cf
        }

        return null
    }

    /**
     * Extracts the slug from a Modrinth URL.
     */
    private fun extractModrinthSlug(input: String): String? {
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
     * Extracts the slug from a CurseForge URL.
     */
    private fun extractCurseForgeSlug(input: String): String? {
        val pattern = Regex("""curseforge\.com/minecraft/modpacks/([a-zA-Z0-9_-]+)""")
        val match = pattern.find(input)
        return match?.groupValues?.get(1)
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

    // ── CurseForge API ──────────────────────────────────────────

    /**
     * Downloads the server pack for a CurseForge modpack by slug.
     * Requires a configured CurseForge API key.
     */
    private suspend fun downloadFromCurseForge(slug: String, downloadDir: Path): Path? {
        if (!hasCurseForgeKey) {
            logger.error("CurseForge API key not configured — set [curseforge] api_key in nimbus.toml")
            return null
        }

        return try {
            // Search for modpack by slug
            val searchResponse = client.get("https://api.curseforge.com/v1/mods/search") {
                header("x-api-key", curseForgeApiKey)
                parameter("gameId", 432) // Minecraft
                parameter("classId", 4471) // Modpacks
                parameter("slug", slug)
                parameter("pageSize", 1)
            }
            if (searchResponse.status != HttpStatusCode.OK) {
                logger.error("CurseForge search failed for '{}' (HTTP {})", slug, searchResponse.status)
                return null
            }

            val searchResult = json.decodeFromString<CurseForgeResponse<List<CurseForgeSearchResult>>>(searchResponse.bodyAsText())
            val modpack = searchResult.data.firstOrNull { it.slug == slug } ?: run {
                logger.error("CurseForge modpack '{}' not found", slug)
                return null
            }

            if (modpack.classId != 4471) {
                logger.error("'{}' is not a modpack (classId={})", slug, modpack.classId)
                return null
            }

            // Get latest file for the modpack
            val filesResponse = client.get("https://api.curseforge.com/v1/mods/${modpack.id}/files") {
                header("x-api-key", curseForgeApiKey)
                parameter("pageSize", 1)
            }
            if (filesResponse.status != HttpStatusCode.OK) {
                logger.error("Failed to fetch files for CurseForge modpack '{}'", slug)
                return null
            }

            val filesResult = json.decodeFromString<CurseForgeResponse<List<CurseForgeFile>>>(filesResponse.bodyAsText())
            val latestFile = filesResult.data.firstOrNull() ?: run {
                logger.error("No files found for CurseForge modpack '{}'", slug)
                return null
            }

            // Prefer server pack if available
            val fileToDownload = if (latestFile.serverPackFileId != null) {
                val spResponse = client.get("https://api.curseforge.com/v1/mods/${modpack.id}/files/${latestFile.serverPackFileId}") {
                    header("x-api-key", curseForgeApiKey)
                }
                if (spResponse.status == HttpStatusCode.OK) {
                    json.decodeFromString<CurseForgeResponse<CurseForgeFile>>(spResponse.bodyAsText()).data
                } else latestFile
            } else latestFile

            val downloadUrl = fileToDownload.downloadUrl
            if (downloadUrl == null) {
                logger.error("CurseForge modpack '{}' has restricted distribution — download the server pack manually and use: import /path/to/ServerFiles.zip", slug)
                return null
            }

            // Download
            if (!downloadDir.exists()) downloadDir.createDirectories()
            val targetFile = downloadDir.resolve(fileToDownload.fileName)

            logger.info("Downloading {} from CurseForge ({})...", fileToDownload.fileName, formatSize(fileToDownload.fileLength))
            val dlResponse = client.get(downloadUrl)
            if (dlResponse.status != HttpStatusCode.OK) {
                logger.error("Download failed: HTTP {}", dlResponse.status)
                return null
            }
            targetFile.writeBytes(dlResponse.readRawBytes())
            logger.info("Downloaded {}", fileToDownload.fileName)
            targetFile
        } catch (e: Exception) {
            logger.error("Failed to resolve CurseForge modpack '{}': {}", slug, e.message)
            null
        }
    }

    // ── Server Pack ZIP detection ──────────────────────────────

    /**
     * Checks if a ZIP file is a pre-built server pack (contains mods/ and startup scripts/installers).
     */
    fun isServerPack(zipPath: Path): Boolean {
        if (!zipPath.name.endsWith(".zip")) return false
        return try {
            ZipFile(zipPath.toFile()).use { zip ->
                val entries = zip.entries().toList().map { it.name }
                val hasMods = entries.any { it.startsWith("mods/") && it.endsWith(".jar") }
                val hasStartup = entries.any {
                    it == "startserver.sh" || it == "startserver.bat" ||
                    it == "run.sh" || it == "run.bat" ||
                    it == "start.sh" || it == "start.bat" ||
                    it.matches(Regex("""(neo)?forge-[\d.]+-installer\.jar""")) ||
                    it == "fabric-server-launch.jar"
                }
                hasMods && hasStartup
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Detects modloader, MC version, and mod count from a server pack ZIP.
     */
    fun getServerPackInfo(zipPath: Path): ModpackInfo? {
        return try {
            ZipFile(zipPath.toFile()).use { zip ->
                val entries = zip.entries().toList()
                val entryNames = entries.map { it.name }

                // Count mods (only direct children of mods/)
                val modCount = entryNames.count { it.startsWith("mods/") && it.endsWith(".jar") && it.count { c -> c == '/' } == 1 }

                // Detect modloader and version from startup scripts or installer filenames
                val (modloader, loaderVersion, mcVersion) = detectFromServerPack(zip, entries, entryNames)

                // Derive pack name from filename
                val packName = zipPath.name
                    .removeSuffix(".zip")
                    .replace(Regex("[-_]?[Ss]erver[Ff]iles[-_]?"), "")
                    .replace(Regex("[-_]?[Ss]erver[-_]?[Pp]ack[-_]?"), "")
                    .ifEmpty { zipPath.name.removeSuffix(".zip") }

                // Derive version from filename
                val versionMatch = Regex("""[\-_](\d+\.\d+(?:\.\d+)?)""").find(zipPath.name)
                val packVersion = versionMatch?.groupValues?.get(1) ?: ""

                ModpackInfo(
                    name = packName,
                    version = packVersion,
                    mcVersion = mcVersion,
                    modloader = modloader,
                    modloaderVersion = loaderVersion,
                    totalFiles = modCount,
                    serverFiles = modCount,
                    source = ModpackSource.SERVER_PACK
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to analyze server pack: {}", e.message)
            null
        }
    }

    /**
     * Detects modloader type, loader version, and MC version from server pack contents.
     * Returns Triple(modloader, loaderVersion, mcVersion).
     */
    private fun detectFromServerPack(
        zip: ZipFile,
        entries: List<java.util.zip.ZipEntry>,
        entryNames: List<String>
    ): Triple<ServerSoftware, String, String> {
        // Check for NeoForge installer: neoforge-<version>-installer.jar
        val neoforgeInstaller = entryNames.firstOrNull { it.matches(Regex("""neoforge-[\d.]+-installer\.jar""")) }
        if (neoforgeInstaller != null) {
            val nfVersion = Regex("""neoforge-([\d.]+)-installer\.jar""").find(neoforgeInstaller)?.groupValues?.get(1) ?: ""
            val mcVer = detectMcVersionFromNeoForge(nfVersion) ?: detectMcVersionFromScripts(zip, entries) ?: "unknown"
            return Triple(ServerSoftware.NEOFORGE, nfVersion, mcVer)
        }

        // Check for Forge installer: forge-<mcversion>-<forgeversion>-installer.jar
        val forgeInstaller = entryNames.firstOrNull { it.matches(Regex("""forge-[\d.]+-[\d.]+-installer\.jar""")) }
        if (forgeInstaller != null) {
            val match = Regex("""forge-([\d.]+)-([\d.]+)-installer\.jar""").find(forgeInstaller)
            val mcVer = match?.groupValues?.get(1) ?: "unknown"
            val forgeVer = match?.groupValues?.get(2) ?: ""
            return Triple(ServerSoftware.FORGE, forgeVer, mcVer)
        }

        // Check for Fabric
        val hasFabric = entryNames.any { it == "fabric-server-launch.jar" }
        if (hasFabric) {
            val mcVer = detectMcVersionFromScripts(zip, entries) ?: "unknown"
            return Triple(ServerSoftware.FABRIC, "", mcVer)
        }

        // Check startup scripts for hints
        val mcVer = detectMcVersionFromScripts(zip, entries) ?: "unknown"

        // Check if startserver.sh mentions a specific loader
        for (scriptName in listOf("startserver.sh", "startserver.bat", "run.sh", "run.bat", "start.sh", "variables.txt")) {
            val scriptEntry = entries.firstOrNull { it.name == scriptName } ?: continue
            val content = zip.getInputStream(scriptEntry).bufferedReader().readText()

            if (content.contains("NEOFORGE", ignoreCase = true) || content.contains("neoforge", ignoreCase = false)) {
                val ver = Regex("""NEOFORGE_VERSION[=:]?\s*(\S+)""", RegexOption.IGNORE_CASE).find(content)?.groupValues?.get(1) ?: ""
                return Triple(ServerSoftware.NEOFORGE, ver, mcVer)
            }
            if (content.contains("FORGE", ignoreCase = true) && !content.contains("NEOFORGE", ignoreCase = true)) {
                val ver = Regex("""FORGE_VERSION[=:]?\s*(\S+)""", RegexOption.IGNORE_CASE).find(content)?.groupValues?.get(1) ?: ""
                return Triple(ServerSoftware.FORGE, ver, mcVer)
            }
            if (content.contains("fabric", ignoreCase = true)) {
                return Triple(ServerSoftware.FABRIC, "", mcVer)
            }
        }

        return Triple(ServerSoftware.CUSTOM, "", mcVer)
    }

    /**
     * Detects MC version from NeoForge version number.
     * NeoForge 21.x = MC 1.21.x mapping.
     */
    private fun detectMcVersionFromNeoForge(nfVersion: String): String? {
        val major = nfVersion.split(".").firstOrNull()?.toIntOrNull() ?: return null
        // NeoForge 21.x → MC 1.21.x, NeoForge 20.x → MC 1.20.x
        val minor = nfVersion.split(".").getOrNull(1)?.toIntOrNull() ?: 0
        return when {
            major >= 20 -> {
                // NeoForge 21.1.x → MC 1.21.1, NeoForge 21.0.x → MC 1.21
                if (minor > 0) "1.$major.$minor" else "1.$major"
            }
            else -> null
        }
    }

    /**
     * Tries to extract MC version from startup scripts.
     */
    private fun detectMcVersionFromScripts(zip: ZipFile, entries: List<java.util.zip.ZipEntry>): String? {
        for (scriptName in listOf("startserver.sh", "startserver.bat", "run.sh", "run.bat", "variables.txt", "server.properties")) {
            val entry = entries.firstOrNull { it.name == scriptName } ?: continue
            val content = zip.getInputStream(entry).bufferedReader().readText()

            // Look for MC version patterns like "Minecraft 1.21" or "mc1.21.1"
            val mcPattern = Regex("""(?:Minecraft|mc)\s*(\d+\.\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
            mcPattern.find(content)?.groupValues?.get(1)?.let { return it }

            // Look for version in unix_args.txt path: libraries/net/neoforged/neoforge/<version>/unix_args.txt
            val argsPath = Regex("""libraries/net/neoforged/neoforge/([\d.]+)/""").find(content)
            if (argsPath != null) {
                val nfVer = argsPath.groupValues[1]
                detectMcVersionFromNeoForge(nfVer)?.let { return it }
            }

            // Forge-style: libraries/net/minecraftforge/forge/<mcver>-<forgever>/
            val forgePath = Regex("""libraries/net/minecraftforge/forge/(\d+\.\d+(?:\.\d+)?)-""").find(content)
            if (forgePath != null) return forgePath.groupValues[1]
        }
        return null
    }

    /**
     * Extracts all files from a server pack ZIP to the template directory.
     * Skips installer JARs and startup scripts (Nimbus manages its own startup).
     */
    fun extractServerPack(zipPath: Path, templateDir: Path) {
        val normalizedTarget = templateDir.normalize()
        ZipFile(zipPath.toFile()).use { zip ->
            val entries = zip.entries().toList()
            for (entry in entries) {
                if (entry.isDirectory) continue

                // Skip files Nimbus manages itself
                val name = entry.name
                if (name.matches(Regex("""(neo)?forge-[\d.]+-installer\.jar""")) ||
                    name == "startserver.sh" || name == "startserver.bat" ||
                    name == "run.sh" || name == "run.bat" ||
                    name == "start.sh" || name == "start.bat" ||
                    name == "user_jvm_args.txt" ||
                    name == "README.txt" || name == "README.md") continue

                val target = templateDir.resolve(name).normalize()
                if (!target.startsWith(normalizedTarget)) {
                    logger.warn("Path traversal blocked in server pack: {}", name)
                    continue
                }

                val parent = target.parent
                if (!parent.exists()) parent.createDirectories()
                zip.getInputStream(entry).use { input ->
                    Files.write(target, input.readBytes())
                }
            }
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
