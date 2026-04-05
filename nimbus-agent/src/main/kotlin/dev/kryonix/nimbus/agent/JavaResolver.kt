package dev.kryonix.nimbus.agent

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
import kotlin.io.path.isDirectory
import kotlin.io.path.setPosixFilePermissions

/**
 * Resolves the correct Java executable for a given major version.
 * Auto-detects installed JDKs, downloads missing ones from Adoptium.
 *
 * Simplified agent version: receives the target Java version directly
 * from the controller (no MC version logic needed).
 */
class JavaResolver(
    private val configuredPaths: Map<Int, String> = emptyMap(),
    private val baseDir: Path = Path.of(".")
) {

    private val logger = LoggerFactory.getLogger(JavaResolver::class.java)
    private val client = HttpClient(CIO)
    private val jdkCacheDir: Path = baseDir.resolve("jdks")

    private val detected: MutableMap<Int, String> by lazy { detectInstalledJavas().toMutableMap() }

    /**
     * Resolves the best Java executable for a required major version.
     * If no compatible version is found, auto-downloads from Adoptium.
     *
     * @param requiredVersion Minimum Java major version (e.g. 8, 17, 21)
     * @return Path to the java executable
     */
    suspend fun resolve(requiredVersion: Int): String {
        if (requiredVersion <= 0) return "java"

        val allJavas = detected.toMutableMap()
        for ((ver, path) in configuredPaths) {
            if (Path.of(path).exists()) allJavas[ver] = path
        }

        // Prefer exact match, then lowest compatible
        val compatible = allJavas.keys.filter { it >= requiredVersion }.sorted()
        if (compatible.isNotEmpty()) {
            val exact = compatible.firstOrNull { it == requiredVersion }
            val best = exact ?: compatible.first()
            return allJavas[best]!!
        }

        // Nothing found — auto-download
        logger.info("No Java {}+ found — downloading automatically...", requiredVersion)
        val downloaded = downloadJdk(requiredVersion)
        if (downloaded != null) {
            detected[requiredVersion] = downloaded
            return downloaded
        }

        // Last resort: any available Java
        val fallback = allJavas.keys.sorted().firstOrNull()
        if (fallback != null) {
            logger.warn("Auto-download failed. Using Java {} — may not be compatible!", fallback)
            return allJavas[fallback]!!
        }

        logger.warn("No Java installations found, using system 'java'")
        return "java"
    }

    // ── Auto-download from Adoptium ────────────────────────────

    private suspend fun downloadJdk(majorVersion: Int): String? {
        return try {
            if (!jdkCacheDir.exists()) Files.createDirectories(jdkCacheDir)

            val cachedJava = findCachedJdk(majorVersion)
            if (cachedJava != null) return cachedJava

            val os = detectOS()
            val arch = detectArch()

            val apiUrl = "https://api.adoptium.net/v3/binary/latest/$majorVersion/ga/$os/$arch/jdk/hotspot/normal/eclipse"
            logger.info("Downloading Java {} from Adoptium ({}/{})...", majorVersion, os, arch)

            val response = client.get(apiUrl)
            if (response.status != HttpStatusCode.OK) {
                logger.error("Failed to download Java {}: HTTP {} — install it manually", majorVersion, response.status)
                return null
            }

            val ext = if (os == "windows") "zip" else "tar.gz"
            val archiveFile = jdkCacheDir.resolve("temurin-$majorVersion.$ext")
            val bytes = response.readRawBytes()
            Files.write(archiveFile, bytes)
            val sizeMb = String.format("%.1f", bytes.size / 1024.0 / 1024.0)
            logger.info("Downloaded Java {} ({} MB), extracting...", majorVersion, sizeMb)

            val extractDir = jdkCacheDir.resolve("java-$majorVersion")
            if (extractDir.exists()) {
                Files.walk(extractDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
            Files.createDirectories(extractDir)

            if (ext == "tar.gz") {
                ProcessBuilder("tar", "xzf", archiveFile.toAbsolutePath().toString(), "--strip-components=1", "-C", extractDir.toAbsolutePath().toString())
                    .redirectErrorStream(true).start().waitFor()
            } else {
                ProcessBuilder("unzip", "-q", archiveFile.toAbsolutePath().toString(), "-d", extractDir.toAbsolutePath().toString())
                    .redirectErrorStream(true).start().waitFor()
            }

            Files.deleteIfExists(archiveFile)

            val javaBin = findJavaBin(extractDir)
                ?: extractDir.toFile().listFiles()?.firstOrNull { it.isDirectory }?.let { findJavaBin(it.toPath()) }

            if (javaBin != null) {
                try { Path.of(javaBin).setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x")) } catch (_: Exception) {}
                logger.info("Java {} installed to {}", majorVersion, javaBin)
                return javaBin
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
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Check environment variables
        for (version in listOf(16, 17, 21, 22, 23, 24, 25, 26)) {
            val envVar = "JAVA_${version}_HOME"
            val home = System.getenv(envVar)
            if (home != null) {
                val javaBin = findJavaBin(Path.of(home))
                if (javaBin != null && version !in found) {
                    found[version] = javaBin
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
            } catch (_: Exception) {}
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

    // ── Utility ────────────────────────────────────────────────

    private fun findJavaBin(jdkDir: Path): String? {
        val candidates = listOf(jdkDir.resolve("bin/java"), jdkDir.resolve("bin/java.exe"))
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
            val process = ProcessBuilder(javaBin, "-version").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            val match = Regex("""version "(\d+)(?:\.(\d+))?""").find(output)
            if (match != null) {
                val major = match.groupValues[1].toIntOrNull() ?: return null
                if (major == 1) match.groupValues[2].toIntOrNull() else major
            } else null
        } catch (_: Exception) { null }
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

    fun close() {
        client.close()
    }
}
