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
import java.security.MessageDigest
import kotlin.io.path.fileSize

internal class PaperFamilyResolver(
    private val client: HttpClient,
    private val json: Json
) {
    private val logger = LoggerFactory.getLogger(PaperFamilyResolver::class.java)

    // ── Version fetching ────────────────────────────────────────

    suspend fun fetchPaperVersions(): SoftwareResolver.VersionList {
        return try {
            val response = client.get("https://api.papermc.io/v2/projects/paper")
            if (response.status != HttpStatusCode.OK) return SoftwareResolver.VersionList.EMPTY
            val data = json.decodeFromString<PaperProjectResponse>(response.bodyAsText())
            categorizeVersions(data.versions)
        } catch (e: Exception) {
            logger.error("Failed to fetch Paper versions: {}", e.message)
            SoftwareResolver.VersionList.EMPTY
        }
    }

    suspend fun fetchFoliaVersions(): SoftwareResolver.VersionList {
        return try {
            val response = client.get("https://api.papermc.io/v2/projects/folia")
            if (response.status != HttpStatusCode.OK) return SoftwareResolver.VersionList.EMPTY
            val data = json.decodeFromString<PaperProjectResponse>(response.bodyAsText())
            categorizeVersions(data.versions)
        } catch (e: Exception) {
            logger.error("Failed to fetch Folia versions: {}", e.message)
            SoftwareResolver.VersionList.EMPTY
        }
    }

    suspend fun fetchPurpurVersions(): SoftwareResolver.VersionList {
        return try {
            val response = client.get("https://api.purpurmc.org/v2/purpur")
            if (response.status != HttpStatusCode.OK) return SoftwareResolver.VersionList.EMPTY
            val data = json.decodeFromString<PurpurProjectResponse>(response.bodyAsText())
            categorizeVersions(data.versions)
        } catch (e: Exception) {
            logger.error("Failed to fetch Purpur versions: {}", e.message)
            SoftwareResolver.VersionList.EMPTY
        }
    }

    suspend fun fetchPufferfishVersions(): SoftwareResolver.VersionList {
        return try {
            val response = client.get("https://ci.pufferfish.host/api/json?tree=jobs[name]")
            if (response.status != HttpStatusCode.OK) return SoftwareResolver.VersionList.EMPTY
            val data = json.decodeFromString<PufferfishCIResponse>(response.bodyAsText())
            // Extract MC major versions from job names like "Pufferfish-1.21"
            // Filter out very old branches (< 1.19) that are unlikely to receive updates
            val versions = data.jobs
                .map { it.name }
                .filter { it.startsWith("Pufferfish-") && !it.contains("Purpur") }
                .map { it.removePrefix("Pufferfish-") }
                .filter { isVersionAtLeast(it, "1.19") }
                .sortedDescending()
            SoftwareResolver.VersionList(stable = versions, snapshots = emptyList())
        } catch (e: Exception) {
            logger.error("Failed to fetch Pufferfish versions: {}", e.message)
            SoftwareResolver.VersionList.EMPTY
        }
    }

    suspend fun fetchLeafVersions(): SoftwareResolver.VersionList {
        return try {
            val response = client.get("https://api.leafmc.one/v2/projects/leaf")
            if (response.status != HttpStatusCode.OK) return SoftwareResolver.VersionList.EMPTY
            val data = json.decodeFromString<PaperProjectResponse>(response.bodyAsText())
            categorizeVersions(data.versions)
        } catch (e: Exception) {
            logger.error("Failed to fetch Leaf versions: {}", e.message)
            SoftwareResolver.VersionList.EMPTY
        }
    }

    suspend fun fetchVelocityVersions(): SoftwareResolver.VersionList {
        return try {
            val response = client.get("https://api.papermc.io/v2/projects/velocity")
            if (response.status != HttpStatusCode.OK) return SoftwareResolver.VersionList.EMPTY
            val data = json.decodeFromString<PaperProjectResponse>(response.bodyAsText())
            // Velocity uses SNAPSHOT versions as primary distribution —
            // treat all versions as stable to avoid false update suggestions
            SoftwareResolver.VersionList(stable = data.versions.reversed(), snapshots = emptyList())
        } catch (e: Exception) {
            logger.error("Failed to fetch Velocity versions: {}", e.message)
            SoftwareResolver.VersionList.EMPTY
        }
    }

    // ── JAR downloads ───────────────────────────────────────────

    suspend fun downloadJar(software: ServerSoftware, version: String, targetDir: Path): Path? {
        return when (software) {
            ServerSoftware.PURPUR -> downloadPurpur(version, targetDir)
            ServerSoftware.PUFFERFISH -> downloadPufferfish(version, targetDir)
            ServerSoftware.LEAF -> downloadLeaf(version, targetDir)
            ServerSoftware.PAPER, ServerSoftware.FOLIA, ServerSoftware.VELOCITY -> downloadPaperMC(software, version, targetDir)
            else -> null
        }
    }

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
        } catch (e: UnknownServerVersionException) {
            throw e
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
        } catch (e: UnknownServerVersionException) {
            throw e
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
            jarResponse.bodyAsChannel().toInputStream().use { input ->
                Files.newOutputStream(targetFile).use { out -> input.copyTo(out, 65536) }
            }

            val sizeMb = String.format("%.1f", targetFile.fileSize() / 1024.0 / 1024.0)
            logger.info("Downloaded Pufferfish {} ({} MB)", artifact.fileName, sizeMb)
            targetFile
        } catch (e: UnknownServerVersionException) {
            throw e
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
        } catch (e: UnknownServerVersionException) {
            throw e
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

        // Stream to disk while computing SHA-256 digest for verification
        val digest = if (!expectedSha256.isNullOrBlank()) MessageDigest.getInstance("SHA-256") else null
        jarResponse.bodyAsChannel().toInputStream().use { input ->
            Files.newOutputStream(targetFile).use { out ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    digest?.update(buf, 0, n)
                }
            }
        }

        // Verify SHA-256 checksum if provided by the API
        if (digest != null && !expectedSha256.isNullOrBlank()) {
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                logger.error("SHA-256 mismatch for {} {} {}! Expected: {}, got: {}. Download rejected.",
                    software, version, buildInfo, expectedSha256, actualSha256)
                Files.deleteIfExists(targetFile)
                return null
            }
            logger.debug("SHA-256 verified for {} {} {}", software, version, buildInfo)
        }

        val sizeMb = String.format("%.1f", targetFile.fileSize() / 1024.0 / 1024.0)
        logger.info("Downloaded {} {} {} ({} MB)", software, version, buildInfo, sizeMb)
        return targetFile
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun categorizeVersions(versions: List<String>): SoftwareResolver.VersionList {
        val stable = mutableListOf<String>()
        val snapshots = mutableListOf<String>()

        for (v in versions) {
            if (v.contains("pre") || v.contains("rc") || v.contains("SNAPSHOT")) {
                snapshots.add(v)
            } else {
                stable.add(v)
            }
        }

        return SoftwareResolver.VersionList(
            stable = stable.reversed(),     // newest first
            snapshots = snapshots.reversed()
        )
    }

    fun jarFileName(software: ServerSoftware): String = when (software) {
        ServerSoftware.VELOCITY -> "velocity.jar"
        else -> "server.jar"
    }
}
