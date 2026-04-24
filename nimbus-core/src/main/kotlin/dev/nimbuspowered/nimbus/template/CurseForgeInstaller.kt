package dev.nimbuspowered.nimbus.template

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

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

internal class CurseForgeInstaller(
    private val client: HttpClient,
    private val apiKey: String,
    private val json: Json
) {

    private val logger = LoggerFactory.getLogger(CurseForgeInstaller::class.java)

    fun extractSlug(input: String): String? {
        val pattern = Regex("""curseforge\.com/minecraft/modpacks/([a-zA-Z0-9_-]+)""")
        val match = pattern.find(input)
        return match?.groupValues?.get(1)
    }

    suspend fun download(slug: String, downloadDir: Path): Path? {
        return try {
            val searchResponse = client.get("https://api.curseforge.com/v1/mods/search") {
                header("x-api-key", apiKey)
                parameter("gameId", 432)
                parameter("classId", 4471)
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

            val filesResponse = client.get("https://api.curseforge.com/v1/mods/${modpack.id}/files") {
                header("x-api-key", apiKey)
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

            val fileToDownload = if (latestFile.serverPackFileId != null) {
                val spResponse = client.get("https://api.curseforge.com/v1/mods/${modpack.id}/files/${latestFile.serverPackFileId}") {
                    header("x-api-key", apiKey)
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

            if (!downloadDir.exists()) downloadDir.createDirectories()
            val targetFile = downloadDir.resolve(fileToDownload.fileName)

            logger.info("Downloading {} from CurseForge ({})...", fileToDownload.fileName, formatSize(fileToDownload.fileLength))
            client.prepareGet(downloadUrl).execute { dlResponse ->
                if (dlResponse.status != HttpStatusCode.OK) {
                    logger.error("Download failed: HTTP {}", dlResponse.status)
                    return@execute
                }
                dlResponse.bodyAsChannel().toInputStream().use { input ->
                    Files.newOutputStream(targetFile).use { out -> input.copyTo(out, 65536) }
                }
            }
            if (!targetFile.exists()) return null
            logger.info("Downloaded {}", fileToDownload.fileName)
            targetFile
        } catch (e: Exception) {
            logger.error("Failed to resolve CurseForge modpack '{}': {}", slug, e.message)
            null
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> String.format("%.1fMB", bytes / 1024.0 / 1024.0)
    }
}
