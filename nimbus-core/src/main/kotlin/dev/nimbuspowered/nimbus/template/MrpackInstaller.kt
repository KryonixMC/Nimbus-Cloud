package dev.nimbuspowered.nimbus.template

import dev.nimbuspowered.nimbus.config.ServerSoftware
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.toInputStream
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

internal class MrpackInstaller(
    private val client: HttpClient,
    private val json: Json
) {

    private val logger = LoggerFactory.getLogger(MrpackInstaller::class.java)
    private val downloadSemaphore = Semaphore(8)

    fun extractSlug(input: String): String? {
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

    suspend fun download(slug: String, downloadDir: Path): Path? {
        return try {
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

            val mrpackFile = latest.files.firstOrNull { it.filename.endsWith(".mrpack") }
                ?: latest.files.firstOrNull { it.primary }
                ?: latest.files.firstOrNull()

            if (mrpackFile == null) {
                logger.error("No downloadable file found for '{}' {}", slug, latest.versionNumber)
                return null
            }

            if (!downloadDir.exists()) downloadDir.createDirectories()
            val targetFile = downloadDir.resolve(mrpackFile.filename)

            logger.info("Downloading {} ({})...", mrpackFile.filename, formatSize(mrpackFile.size))
            client.prepareGet(mrpackFile.url).execute { dlResponse ->
                if (dlResponse.status != HttpStatusCode.OK) {
                    logger.error("Download failed: HTTP {}", dlResponse.status)
                    return@execute
                }
                dlResponse.bodyAsChannel().toInputStream().use { input ->
                    Files.newOutputStream(targetFile).use { out -> input.copyTo(out, 65536) }
                }
            }
            if (!targetFile.exists()) return null
            logger.info("Downloaded {}", mrpackFile.filename)
            targetFile
        } catch (e: Exception) {
            logger.error("Failed to resolve modpack '{}': {}", slug, e.message)
            null
        }
    }

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
                        synchronized(this@MrpackInstaller) {
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
            if (!streamDownloadWithHash(url, targetPath, file.hashes["sha1"], file.path)) {
                logger.warn("Retrying download for {}", file.path)
                streamDownloadWithHash(url, targetPath, file.hashes["sha1"], file.path)
            } else true
        } catch (e: Exception) {
            logger.warn("Failed to download {}: {}", file.path, e.message)
            false
        }
    }

    private suspend fun streamDownloadWithHash(url: String, targetPath: Path, expectedSha1: String?, fileName: String): Boolean {
        var ok = false
        client.prepareGet(url).execute { response ->
            if (response.status != HttpStatusCode.OK) {
                logger.warn("Failed to download {}: HTTP {}", fileName, response.status)
                return@execute
            }

            val digest = if (expectedSha1 != null) MessageDigest.getInstance("SHA-1") else null
            response.bodyAsChannel().toInputStream().use { input ->
                Files.newOutputStream(targetPath).use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        digest?.update(buf, 0, n)
                    }
                }
            }

            if (expectedSha1 != null && digest != null) {
                val actualSha1 = digest.digest().joinToString("") { "%02x".format(it) }
                if (actualSha1 != expectedSha1) {
                    logger.warn("Hash mismatch for {}: expected {} got {}", fileName, expectedSha1, actualSha1)
                    Files.deleteIfExists(targetPath)
                    return@execute
                }
            }
            ok = true
        }
        return ok
    }

    fun extractOverrides(mrpackPath: Path, templateDir: Path) {
        ZipFile(mrpackPath.toFile()).use { zip ->
            val entries = zip.entries().toList()
            extractPrefix(zip, entries, "overrides/", templateDir)
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

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> String.format("%.1fMB", bytes / 1024.0 / 1024.0)
    }
}
