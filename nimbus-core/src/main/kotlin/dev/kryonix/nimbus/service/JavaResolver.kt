package dev.kryonix.nimbus.service

import dev.kryonix.nimbus.config.ServerSoftware
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.isDirectory
import kotlin.io.path.setPosixFilePermissions

/**
 * Resolves the correct Java executable for a given Minecraft server version.
 * Auto-detects installed JDKs, downloads missing ones from Adoptium.
 */
class JavaResolver(
    private val configuredPaths: Map<Int, String> = emptyMap(),
    private val nimbusDir: Path = Path.of(".")
) {

    private val logger = LoggerFactory.getLogger(JavaResolver::class.java)
    private val client = HttpClient(CIO)

    // Directory where Nimbus stores auto-downloaded JDKs
    private val jdkCacheDir: Path = nimbusDir.resolve("jdks")

    // Cache of detected Java installations: major version -> executable path
    private val detected: MutableMap<Int, String> by lazy { detectInstalledJavas().toMutableMap() }

    /**
     * Returns the minimum required Java major version for a given MC version.
     * Supports both old (1.x.x) and new (26.x) versioning schemes.
     */
    fun requiredJavaVersion(mcVersion: String, software: ServerSoftware): Int {
        if (software == ServerSoftware.VELOCITY) return 21

        val parts = mcVersion.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return 21

        // New versioning scheme: 26.x, 27.x, etc.
        if (major >= 2) {
            return when {
                major >= 26 -> 25                       // 26.1+ -> Java 25+
                else -> 21
            }
        }

        // Old versioning scheme: 1.x.x
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return 21
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0

        return when {
            minor >= 21 -> 21                           // 1.21+ -> Java 21+
            minor == 20 && patch >= 5 -> 21             // 1.20.5+ -> Java 21+
            minor in 18..20 -> 17                       // 1.18-1.20.4 -> Java 17+
            minor == 17 -> 16                           // 1.17 -> Java 16+
            else -> 16                                  // 1.16 and below -> Java 16 (minimum supported)
        }
    }

    /**
     * Returns the maximum Java version a MC version supports.
     * Old MC versions break on newer Java. Returns null if no upper limit.
     */
    fun maxJavaVersion(mcVersion: String, software: ServerSoftware): Int? {
        if (software == ServerSoftware.VELOCITY) return null

        val parts = mcVersion.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
        if (major >= 2) return null

        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return null

        return when {
            minor >= 17 -> null                         // 1.17+ has no upper limit
            else -> 16                                  // 1.8-1.16 max Java 16
        }
    }

    /**
     * Resolves the best Java executable for a server.
     * If no compatible version is found, auto-downloads from Adoptium.
     */
    suspend fun resolve(mcVersion: String, software: ServerSoftware, groupJavaPath: String = ""): String {
        // 1. Per-group override
        if (groupJavaPath.isNotEmpty()) {
            val path = Path.of(groupJavaPath)
            if (path.exists() && path.isExecutable()) return groupJavaPath
            logger.warn("Configured java_path '{}' not found, falling back to auto-detection", groupJavaPath)
        }

        val minJava = requiredJavaVersion(mcVersion, software)
        val maxJava = maxJavaVersion(mcVersion, software)

        // Merge configured + detected
        val allJavas = detected.toMutableMap()
        for ((ver, path) in configuredPaths) {
            if (Path.of(path).exists()) allJavas[ver] = path
        }

        // Prefer the exact minimum version (mods often require exactly this version)
        // Fall back to lowest compatible if exact match not available
        val compatible = allJavas.keys
            .filter { it >= minJava && (maxJava == null || it <= maxJava) }
            .sorted()

        if (compatible.isNotEmpty()) {
            // Prefer exact match for the required version
            val exact = compatible.firstOrNull { it == minJava }
            val best = exact ?: compatible.first()
            return allJavas[best]!!
        }

        // Nothing in range — auto-download the best version
        val targetVersion = if (maxJava != null) maxJava else minJava
        logger.info("No Java {}{} found — downloading automatically...", minJava, if (maxJava != null) "-$maxJava" else "+")

        val downloaded = downloadJdk(targetVersion)
        if (downloaded != null) {
            detected[targetVersion] = downloaded
            return downloaded
        }

        // Download failed — try lowest available >= min as last resort
        val fallback = allJavas.keys.filter { it >= minJava }.sorted().firstOrNull()
        if (fallback != null) {
            logger.warn("Auto-download failed. Using Java {} — may not be compatible with MC {}!", fallback, mcVersion)
            return allJavas[fallback]!!
        }

        logger.warn("No compatible Java found for MC {}, using system 'java'", mcVersion)
        return "java"
    }

    /**
     * Non-suspend version for compatibility checks (doesn't download).
     */
    fun resolveSync(mcVersion: String, software: ServerSoftware): String? {
        val minJava = requiredJavaVersion(mcVersion, software)
        val maxJava = maxJavaVersion(mcVersion, software)

        val allJavas = detected.toMutableMap()
        for ((ver, path) in configuredPaths) {
            if (Path.of(path).exists()) allJavas[ver] = path
        }

        val compatible = allJavas.keys
            .filter { it >= minJava && (maxJava == null || it <= maxJava) }
            .sortedDescending()

        return if (compatible.isNotEmpty()) allJavas[compatible.first()] else null
    }

    // ── Auto-download from Adoptium ────────────────────────────

    /**
     * Downloads a JDK from Adoptium (Eclipse Temurin) and caches it locally.
     * Returns the path to the java executable, or null on failure.
     */
    private suspend fun downloadJdk(majorVersion: Int): String? {
        return try {
            if (!jdkCacheDir.exists()) Files.createDirectories(jdkCacheDir)

            // Check if already cached
            val cachedJava = findCachedJdk(majorVersion)
            if (cachedJava != null) return cachedJava

            val os = detectOS()
            val arch = detectArch()

            // Adoptium API: get latest release for this major version
            val apiUrl = "https://api.adoptium.net/v3/binary/latest/$majorVersion/ga/$os/$arch/jdk/hotspot/normal/eclipse"
            logger.info("Downloading Java {} from Adoptium ({}/{})...", majorVersion, os, arch)

            val response = client.get(apiUrl)
            if (response.status != HttpStatusCode.OK) {
                logger.error("Failed to download Java {}: HTTP {} — install it manually", majorVersion, response.status)
                return null
            }

            // Save the archive
            val ext = if (os == "windows") "zip" else "tar.gz"
            val archiveFile = jdkCacheDir.resolve("temurin-$majorVersion.$ext")
            val bytes = response.readRawBytes()
            Files.write(archiveFile, bytes)
            val sizeMb = String.format("%.1f", bytes.size / 1024.0 / 1024.0)
            logger.info("Downloaded Java {} ({} MB), extracting...", majorVersion, sizeMb)

            // Extract
            val extractDir = jdkCacheDir.resolve("java-$majorVersion")
            if (extractDir.exists()) {
                Files.walk(extractDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
            Files.createDirectories(extractDir)

            if (ext == "tar.gz") {
                val process = ProcessBuilder("tar", "xzf", archiveFile.toAbsolutePath().toString(), "--strip-components=1", "-C", extractDir.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start()
                process.waitFor()
            } else {
                val process = ProcessBuilder("unzip", "-q", archiveFile.toAbsolutePath().toString(), "-d", extractDir.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start()
                process.waitFor()
            }

            // Clean up archive
            Files.deleteIfExists(archiveFile)

            // Find java binary
            val javaBin = findJavaBin(extractDir)
            if (javaBin != null) {
                // Ensure executable permissions
                try {
                    Path.of(javaBin).setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
                } catch (_: Exception) { }
                logger.info("Java {} installed to {}", majorVersion, javaBin)
                return javaBin
            }

            // tar might have a nested directory (e.g., jdk-17.0.2+8/)
            val nested = extractDir.toFile().listFiles()?.firstOrNull { it.isDirectory }
            if (nested != null) {
                val nestedJava = findJavaBin(nested.toPath())
                if (nestedJava != null) {
                    try {
                        Path.of(nestedJava).setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
                    } catch (_: Exception) { }
                    logger.info("Java {} installed to {}", majorVersion, nestedJava)
                    return nestedJava
                }
            }

            logger.error("Downloaded Java {} but could not find java binary in {}", majorVersion, extractDir)
            null
        } catch (e: Exception) {
            logger.error("Failed to download Java {}: {}", majorVersion, e.message)
            null
        }
    }

    private fun findCachedJdk(majorVersion: Int): String? {
        val cacheDir = jdkCacheDir.resolve("java-$majorVersion")
        if (!cacheDir.exists()) return null
        return findJavaBin(cacheDir)
            ?: cacheDir.toFile().listFiles()?.firstOrNull { it.isDirectory }?.let { findJavaBin(it.toPath()) }
    }

    private fun detectOS(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("linux") -> "linux"
            os.contains("mac") || os.contains("darwin") -> "mac"
            os.contains("win") -> "windows"
            else -> "linux"
        }
    }

    private fun detectArch(): String {
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            arch.contains("aarch64") || arch.contains("arm64") -> "aarch64"
            arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x64") -> "x64"
            arch.contains("arm") -> "arm"
            arch.contains("x86") || arch.contains("i386") || arch.contains("i686") -> "x32"
            else -> "x64"
        }
    }

    // ── System scanning ────────────────────────────────────────

    private fun detectInstalledJavas(): Map<Int, String> {
        val found = mutableMapOf<Int, String>()

        // Check cached/downloaded JDKs first
        if (jdkCacheDir.exists() && jdkCacheDir.isDirectory()) {
            try {
                Files.list(jdkCacheDir).use { stream ->
                    stream.filter { it.isDirectory() }.forEach { jdkDir ->
                        val version = extractVersionFromPath(jdkDir.fileName.toString())
                        val javaBin = findJavaBin(jdkDir)
                            ?: jdkDir.toFile().listFiles()?.firstOrNull { it.isDirectory }?.let { findJavaBin(it.toPath()) }
                        if (version != null && javaBin != null && version !in found) {
                            found[version] = javaBin
                            logger.debug("Found cached Java {}: {}", version, javaBin)
                        }
                    }
                }
            } catch (_: Exception) { }
        }

        // Check environment variables
        for (version in listOf(16, 17, 21, 22, 23, 24, 25, 26)) {
            val envVar = "JAVA_${version}_HOME"
            val home = System.getenv(envVar)
            if (home != null) {
                val javaBin = findJavaBin(Path.of(home))
                if (javaBin != null && version !in found) {
                    found[version] = javaBin
                    logger.debug("Found Java {} via {}: {}", version, envVar, javaBin)
                }
            }
        }

        // Check JAVA_HOME
        val javaHome = System.getenv("JAVA_HOME")
        if (javaHome != null) {
            val javaBin = findJavaBin(Path.of(javaHome))
            if (javaBin != null) {
                val version = probeJavaVersion(javaBin)
                if (version != null && version !in found) {
                    found[version] = javaBin
                }
            }
        }

        // Scan common directories
        val scanDirs = listOf(
            "/usr/lib/jvm",
            "/usr/java",
            "/usr/local/java",
            "${System.getProperty("user.home")}/.sdkman/candidates/java",
            "${System.getProperty("user.home")}/.jdks",
            "/mnt/c/Program Files/Java",
            "/mnt/c/Program Files/Eclipse Adoptium",
            "/mnt/c/Program Files/Microsoft"
        )

        for (scanDir in scanDirs) {
            val dir = Path.of(scanDir)
            if (!dir.exists() || !dir.isDirectory()) continue
            try {
                Files.list(dir).use { stream ->
                    stream.filter { it.isDirectory() }.forEach { jdkDir ->
                        val javaBin = findJavaBin(jdkDir)
                        if (javaBin != null) {
                            val version = extractVersionFromPath(jdkDir.fileName.toString())
                                ?: probeJavaVersion(javaBin)
                            if (version != null && version !in found) {
                                found[version] = javaBin
                            }
                        }
                    }
                }
            } catch (_: Exception) { }
        }

        // System java as fallback
        if (found.isEmpty()) {
            val version = probeJavaVersion("java")
            if (version != null) found[version] = "java"
        }

        if (found.isNotEmpty()) {
            logger.info("Detected Java installations: {}", found.entries.sortedBy { it.key }.joinToString(", ") { "Java ${it.key}" })
        } else {
            logger.warn("No Java installations detected")
        }

        return found
    }

    private fun findJavaBin(jdkDir: Path): String? {
        val candidates = listOf(
            jdkDir.resolve("bin/java"),
            jdkDir.resolve("bin/java.exe")
        )
        return candidates.firstOrNull { it.exists() }?.toAbsolutePath()?.toString()
    }

    private fun extractVersionFromPath(dirName: String): Int? {
        val patterns = listOf(
            Regex("""(?:java|jdk|temurin|zulu|corretto|graalvm|liberica|semeru)-?(\d+)"""),
            Regex("""^(\d+)\."""),
            Regex("""^jdk1\.(\d+)"""),
            Regex("""(\d+)\.0""")
        )
        for (pattern in patterns) {
            val match = pattern.find(dirName)
            if (match != null) {
                val ver = match.groupValues[1].toIntOrNull()
                if (ver != null) return if (ver == 1) 8 else ver
            }
        }
        return null
    }

    private fun probeJavaVersion(javaBin: String): Int? {
        return try {
            val process = ProcessBuilder(javaBin, "-version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            val match = Regex("""version "(\d+)(?:\.(\d+))?""").find(output)
            if (match != null) {
                val major = match.groupValues[1].toIntOrNull() ?: return null
                if (major == 1) match.groupValues[2].toIntOrNull() else major
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun getDetectedVersions(): Map<Int, String> = detected

    fun close() {
        client.close()
    }
}
