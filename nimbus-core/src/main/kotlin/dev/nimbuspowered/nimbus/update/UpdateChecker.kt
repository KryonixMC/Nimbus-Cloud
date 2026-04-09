package dev.nimbuspowered.nimbus.update

import dev.nimbuspowered.nimbus.NimbusVersion
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.jline.reader.LineReaderBuilder
import org.jline.terminal.TerminalBuilder
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Checks GitHub Releases for new Nimbus versions on startup.
 *
 * Behavior depends on whether the current build is a pre-release or stable:
 *
 * **Stable build:**
 * - Patch/Minor: auto-downloads and swaps JAR
 * - Major: prompts with y/N
 *
 * **Pre-release build:**
 * - Newer pre-release available: prompts "Pre-release vX available, update? [y/N]"
 * - Newer stable available: prompts "Stable vX available, switch to stable? [Y/n]"
 */
class UpdateChecker(
    private val baseDir: Path,
    private val repoOwner: String = "NimbusPowered",
    private val repoName: String = "Nimbus"
) {
    private val logger = LoggerFactory.getLogger(UpdateChecker::class.java)
    private val client = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }

    data class VersionInfo(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val preReleaseSuffix: String? = null
    ) : Comparable<VersionInfo> {
        val isPreRelease: Boolean get() = preReleaseSuffix != null

        override fun compareTo(other: VersionInfo): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            if (patch != other.patch) return patch.compareTo(other.patch)
            // Pre-release is lower than stable (1.0.0-beta < 1.0.0)
            return when {
                isPreRelease && !other.isPreRelease -> -1
                !isPreRelease && other.isPreRelease -> 1
                isPreRelease && other.isPreRelease -> (preReleaseSuffix ?: "").compareTo(other.preReleaseSuffix ?: "")
                else -> 0
            }
        }

        val baseVersion: String get() = "$major.$minor.$patch"
        override fun toString(): String = if (preReleaseSuffix != null) "$baseVersion-$preReleaseSuffix" else baseVersion

        companion object {
            fun parse(version: String): VersionInfo? {
                val cleaned = version.removePrefix("v").trim()
                // Split base version from pre-release suffix (e.g., "0.1.0-beta.1")
                val dashIdx = cleaned.indexOf('-')
                val base = if (dashIdx > 0) cleaned.substring(0, dashIdx) else cleaned
                val suffix = if (dashIdx > 0) cleaned.substring(dashIdx + 1) else null

                val parts = base.split(".")
                if (parts.size < 3) return null
                return try {
                    VersionInfo(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), suffix)
                } catch (_: NumberFormatException) {
                    null
                }
            }
        }
    }

    enum class UpdateType { PATCH, MINOR, MAJOR }

    data class ReleaseInfo(
        val version: VersionInfo,
        val tagName: String,
        val isPreRelease: Boolean,
        val downloadUrl: String,
        val releaseUrl: String,
        val changelog: String,
        val expectedSize: Long = 0
    )

    data class UpdateResult(
        val currentVersion: VersionInfo,
        val latestVersion: VersionInfo,
        val type: UpdateType,
        val downloadUrl: String,
        val releaseUrl: String,
        val changelog: String,
        val isPreRelease: Boolean,
        val expectedSize: Long = 0
    )

    /**
     * Check for available updates without downloading or prompting.
     * Returns the update info if a newer version exists, null otherwise.
     */
    suspend fun checkForUpdate(): UpdateResult? {
        val currentVersionStr = NimbusVersion.version
        if (currentVersionStr == "dev") return null

        val current = VersionInfo.parse(currentVersionStr) ?: return null
        val releases = fetchReleases() ?: return null

        val currentIsPreRelease = current.isPreRelease ||
            releases.any { it.tagName == "v$current" && it.isPreRelease } ||
            releases.any { it.tagName == "v${current.baseVersion}" && it.isPreRelease && it.version == current }

        val latestStable = releases.filter { !it.isPreRelease }.maxByOrNull { it.version }

        if (currentIsPreRelease) {
            // Prefer stable upgrade, then pre-release
            if (latestStable != null && latestStable.version > current) {
                return UpdateResult(
                    current, latestStable.version, classifyUpdate(current, latestStable.version),
                    latestStable.downloadUrl, latestStable.releaseUrl, latestStable.changelog,
                    isPreRelease = false, expectedSize = latestStable.expectedSize
                )
            }
            val latestPreRelease = releases.filter { it.isPreRelease && it.version > current }.maxByOrNull { it.version }
            if (latestPreRelease != null) {
                return UpdateResult(
                    current, latestPreRelease.version, classifyUpdate(current, latestPreRelease.version),
                    latestPreRelease.downloadUrl, latestPreRelease.releaseUrl, latestPreRelease.changelog,
                    isPreRelease = true, expectedSize = latestPreRelease.expectedSize
                )
            }
        } else {
            if (latestStable != null && latestStable.version > current) {
                return UpdateResult(
                    current, latestStable.version, classifyUpdate(current, latestStable.version),
                    latestStable.downloadUrl, latestStable.releaseUrl, latestStable.changelog,
                    isPreRelease = false, expectedSize = latestStable.expectedSize
                )
            }
        }

        return null
    }

    /**
     * Check for updates and handle them.
     * Returns true if the application should restart (JAR was updated).
     */
    suspend fun checkAndApply(): Boolean {
        val currentVersionStr = NimbusVersion.version
        if (currentVersionStr == "dev") {
            logger.debug("Running dev build, skipping update check")
            return false
        }

        val current = VersionInfo.parse(currentVersionStr)
        if (current == null) {
            logger.warn("Cannot parse current version '{}', skipping update check", currentVersionStr)
            return false
        }

        val releases = fetchReleases() ?: return false

        // Determine if current version is a pre-release (check GitHub, fallback to version suffix)
        val currentIsPreRelease = current.isPreRelease ||
            releases.any { it.tagName == "v$current" && it.isPreRelease } ||
            releases.any { it.tagName == "v${current.baseVersion}" && it.isPreRelease && it.version == current }

        if (currentIsPreRelease) {
            return handlePreReleaseUpdate(current, releases)
        } else {
            return handleStableUpdate(current, releases)
        }
    }

    /**
     * Pre-release flow:
     * 1. If a newer stable exists → suggest switching (default Y)
     * 2. If a newer pre-release exists → suggest updating (default N)
     */
    private suspend fun handlePreReleaseUpdate(current: VersionInfo, releases: List<ReleaseInfo>): Boolean {
        // Find latest stable release newer than current base version
        val latestStable = releases
            .filter { !it.isPreRelease }
            .maxByOrNull { it.version }

        if (latestStable != null && latestStable.version > current) {
            println()
            println(ConsoleFormatter.success("Stable release available: v${latestStable.version}"))
            println(ConsoleFormatter.hint("  You are running pre-release v$current"))
            println(ConsoleFormatter.hint("  Release: ${latestStable.releaseUrl}"))
            if (latestStable.changelog.isNotEmpty()) {
                println(ConsoleFormatter.hint("  Changelog:"))
                latestStable.changelog.lines().take(8).forEach { line ->
                    println(ConsoleFormatter.hint("    $line"))
                }
                if (latestStable.changelog.lines().size > 8) {
                    println(ConsoleFormatter.hint("    ..."))
                }
            }
            println()

            if (promptYesNo("  Switch to stable v${latestStable.version}?", defaultYes = true)) {
                val update = UpdateResult(
                    current, latestStable.version, classifyUpdate(current, latestStable.version),
                    latestStable.downloadUrl, latestStable.releaseUrl, latestStable.changelog,
                    isPreRelease = false, expectedSize = latestStable.expectedSize
                )
                return applyUpdate(update)
            }
            println(ConsoleFormatter.hint("  Staying on pre-release channel."))
            println()
        }

        // Find latest pre-release newer than current
        val latestPreRelease = releases
            .filter { it.isPreRelease && it.version > current }
            .maxByOrNull { it.version }

        if (latestPreRelease != null) {
            println()
            println(ConsoleFormatter.warn("Pre-release update available: v${latestPreRelease.version}"))
            println(ConsoleFormatter.hint("  Current: v$current"))
            println(ConsoleFormatter.hint("  Release: ${latestPreRelease.releaseUrl}"))
            println()

            if (promptYesNo("  Update to pre-release v${latestPreRelease.version}?", defaultYes = false)) {
                val update = UpdateResult(
                    current, latestPreRelease.version, classifyUpdate(current, latestPreRelease.version),
                    latestPreRelease.downloadUrl, latestPreRelease.releaseUrl, latestPreRelease.changelog,
                    isPreRelease = true, expectedSize = latestPreRelease.expectedSize
                )
                return applyUpdate(update)
            }
            println(ConsoleFormatter.hint("  Update skipped."))
            println()
        }

        if (latestStable == null && latestPreRelease == null) {
            logger.debug("Nimbus pre-release is up to date (v{})", current)
        }

        return false
    }

    /**
     * Stable flow:
     * - Patch/Minor: auto-download
     * - Major: prompt y/N
     */
    private suspend fun handleStableUpdate(current: VersionInfo, releases: List<ReleaseInfo>): Boolean {
        val latestStable = releases
            .filter { !it.isPreRelease }
            .maxByOrNull { it.version }
            ?: return false

        if (latestStable.version <= current) {
            logger.debug("Nimbus is up to date (v{})", current)
            return false
        }

        val type = classifyUpdate(current, latestStable.version)
        val update = UpdateResult(
            current, latestStable.version, type,
            latestStable.downloadUrl, latestStable.releaseUrl, latestStable.changelog,
            isPreRelease = false, expectedSize = latestStable.expectedSize
        )

        return when (type) {
            UpdateType.PATCH, UpdateType.MINOR -> {
                println()
                println(ConsoleFormatter.info("Update available: v${current} -> v${latestStable.version} (${type.name.lowercase()})"))
                println(ConsoleFormatter.hint("  Downloading automatically..."))
                applyUpdate(update)
            }
            UpdateType.MAJOR -> {
                println()
                println(ConsoleFormatter.warn("Major update available: v${current} -> v${latestStable.version}"))
                println(ConsoleFormatter.hint("  Release: ${update.releaseUrl}"))
                if (update.changelog.isNotEmpty()) {
                    println(ConsoleFormatter.hint("  Changelog:"))
                    update.changelog.lines().take(10).forEach { line ->
                        println(ConsoleFormatter.hint("    $line"))
                    }
                    if (update.changelog.lines().size > 10) {
                        println(ConsoleFormatter.hint("    ... (see release page for full changelog)"))
                    }
                }
                println()

                if (promptYesNo("  Upgrade now?", defaultYes = false)) {
                    applyUpdate(update)
                } else {
                    println(ConsoleFormatter.hint("  Update skipped. You can update later by restarting Nimbus."))
                    false
                }
            }
        }
    }

    private suspend fun fetchReleases(): List<ReleaseInfo>? {
        return try {
            val response = client.get("https://api.github.com/repos/$repoOwner/$repoName/releases?per_page=20") {
                header("Accept", "application/vnd.github.v3+json")
                header("User-Agent", "Nimbus-Cloud/${NimbusVersion.version}")
            }

            if (response.status != HttpStatusCode.OK) {
                logger.debug("GitHub API returned {}, skipping update check", response.status)
                return null
            }

            val releasesArray = json.parseToJsonElement(response.bodyAsText()).jsonArray

            releasesArray.mapNotNull { element ->
                val obj = element.jsonObject
                val tagName = obj["tag_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val version = VersionInfo.parse(tagName) ?: return@mapNotNull null
                val isPreRelease = obj["prerelease"]?.jsonPrimitive?.boolean ?: false

                // Find controller JAR asset (nimbus-core-*.jar)
                val assets = obj["assets"]?.jsonArray ?: return@mapNotNull null
                val jarAsset = assets.firstOrNull { asset ->
                    val name = asset.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                    name.startsWith("nimbus-core") && name.endsWith(".jar")
                } ?: assets.firstOrNull { asset ->
                    val name = asset.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                    name.contains("controller") && name.endsWith(".jar")
                } ?: assets.firstOrNull { asset ->
                    val name = asset.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                    name.endsWith("-all.jar")
                }

                val downloadUrl = jarAsset?.jsonObject?.get("browser_download_url")?.jsonPrimitive?.content
                    ?: return@mapNotNull null
                val expectedSize = jarAsset.jsonObject["size"]?.jsonPrimitive?.long ?: 0L

                ReleaseInfo(
                    version = if (isPreRelease && !version.isPreRelease) version.copy(preReleaseSuffix = "pre") else version,
                    tagName = tagName,
                    isPreRelease = isPreRelease,
                    downloadUrl = downloadUrl,
                    releaseUrl = obj["html_url"]?.jsonPrimitive?.content ?: "",
                    changelog = obj["body"]?.jsonPrimitive?.content ?: "",
                    expectedSize = expectedSize
                )
            }
        } catch (e: Exception) {
            logger.debug("Update check failed: {}", e.message)
            null
        }
    }

    private fun classifyUpdate(current: VersionInfo, target: VersionInfo): UpdateType = when {
        target.major != current.major -> UpdateType.MAJOR
        target.minor != current.minor -> UpdateType.MINOR
        else -> UpdateType.PATCH
    }

    private suspend fun applyUpdate(update: UpdateResult): Boolean {
        return try {
            val currentJar = resolveCurrentJar() ?: run {
                logger.warn("Cannot determine current JAR path, skipping auto-update")
                return false
            }

            // Derive versioned filename from download URL (e.g. nimbus-core-0.2.0.jar)
            val assetName = update.downloadUrl.substringAfterLast('/')
            val targetJar = currentJar.resolveSibling(assetName)

            print(ConsoleFormatter.hint("  Downloading v${update.latestVersion}... "))
            val response = client.get(update.downloadUrl) {
                header("User-Agent", "Nimbus-Cloud/${NimbusVersion.version}")
            }

            if (response.status != HttpStatusCode.OK) {
                println(ConsoleFormatter.error("failed (HTTP ${response.status})"))
                return false
            }

            val bytes = withContext(Dispatchers.IO) { response.readRawBytes() }

            // Verify download integrity: check file size matches GitHub API metadata
            if (update.expectedSize > 0 && bytes.size.toLong() != update.expectedSize) {
                println(ConsoleFormatter.error("failed (size mismatch: expected ${update.expectedSize} bytes, got ${bytes.size})"))
                logger.error("Update download size mismatch — possible corruption or tampering. Expected {} bytes, got {}.", update.expectedSize, bytes.size)
                return false
            }

            val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            logger.info("Update JAR SHA-256: {}", sha256)

            withContext(Dispatchers.IO) {
                Files.write(targetJar, bytes)
            }
            println(ConsoleFormatter.success("done (SHA-256: ${sha256.take(16)}...)"))

            val channel = if (update.isPreRelease) " (pre-release)" else ""
            println(ConsoleFormatter.successLine("Updated to v${update.latestVersion}$channel → ${targetJar.fileName}"))
            println(ConsoleFormatter.warn("  Nimbus will restart automatically..."))
            println()

            true
        } catch (e: Exception) {
            logger.error("Auto-update failed: {}", e.message)
            println(ConsoleFormatter.error("  Update failed: ${e.message}"))
            false
        }
    }

    /**
     * Cleans up old versioned JARs from previous updates.
     * Keeps only the currently running JAR. Safe to call on startup since the old JAR
     * is no longer locked (Windows file-lock concern addressed by deferring cleanup).
     */
    fun cleanupOldJars() {
        val currentJar = resolveCurrentJar() ?: return
        val parentDir = currentJar.parent ?: return
        val currentFileName = currentJar.fileName.toString()

        try {
            Files.newDirectoryStream(parentDir) { path ->
                val name = path.fileName.toString()
                name != currentFileName && name.endsWith(".jar") && (
                    name.startsWith("nimbus-core-") ||
                    (name.startsWith("nimbus-core-") && name.contains("-all")) ||
                    name == "nimbus-update.jar" ||
                    name == "nimbus-backup.jar"
                )
            }.use { stream ->
                for (oldJar in stream) {
                    try {
                        Files.deleteIfExists(oldJar)
                        logger.info("Cleaned up old JAR: {}", oldJar.fileName)
                    } catch (e: Exception) {
                        logger.debug("Could not delete old JAR {}: {}", oldJar.fileName, e.message)
                    }
                }
            }
        } catch (e: Exception) {
            logger.debug("Old JAR cleanup failed: {}", e.message)
        }
    }

    private fun resolveCurrentJar(): Path? {
        return try {
            val uri = UpdateChecker::class.java.protectionDomain.codeSource.location.toURI()
            val path = Path.of(uri)
            if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) path else null
        } catch (_: Exception) {
            null
        }
    }

    private fun promptYesNo(message: String, defaultYes: Boolean): Boolean {
        return try {
            val terminal = TerminalBuilder.builder()
                .system(true)
                .dumb(true)
                .build()
            val reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build()

            val hint = if (defaultYes) ConsoleFormatter.hint("[Y/n]") else ConsoleFormatter.hint("[y/N]")
            val prompt = "$message $hint${ConsoleFormatter.hint(":")} "
            val answer = reader.readLine(prompt).trim().lowercase()
            terminal.close()

            when {
                answer.isEmpty() -> defaultYes
                answer == "y" || answer == "yes" -> true
                answer == "n" || answer == "no" -> false
                else -> defaultYes
            }
        } catch (_: Exception) {
            defaultYes
        }
    }

    fun close() {
        client.close()
    }
}
