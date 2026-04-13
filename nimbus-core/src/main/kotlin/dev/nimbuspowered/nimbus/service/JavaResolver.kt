package dev.nimbuspowered.nimbus.service

import dev.nimbuspowered.nimbus.config.ServerSoftware
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.ZipInputStream
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
    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 600_000  // 10 min for large JDK downloads
            socketTimeoutMillis = 30_000
        }
    }

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

        // Download failed — try lowest available in range as last resort
        val fallback = allJavas.keys
            .filter { it >= minJava && (maxJava == null || it <= maxJava) }
            .sorted().firstOrNull()
        if (fallback != null) {
            logger.warn("Auto-download failed. Using Java {} for MC {}", fallback, mcVersion)
            return allJavas[fallback]!!
        }

        // Nothing in range — try closest version above as absolute last resort
        val closestAbove = allJavas.keys.filter { it >= minJava }.sorted().firstOrNull()
        if (closestAbove != null) {
            logger.warn("No Java in range {}-{} available. Using Java {} for MC {} — may cause issues!", minJava, maxJava, closestAbove, mcVersion)
            return allJavas[closestAbove]!!
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

    // ── Auto-download from multiple providers ────────────────────

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Downloads a JDK and caches it locally.
     * Tries providers in order: Adoptium → Azul Zulu → Amazon Corretto.
     * Returns the path to the java executable, or null on failure.
     */
    private suspend fun downloadJdk(majorVersion: Int): String? {
        if (!jdkCacheDir.exists()) Files.createDirectories(jdkCacheDir)

        val cachedJava = findCachedJdk(majorVersion)
        if (cachedJava != null) return cachedJava

        val os = detectOS()
        val arch = detectArch()

        data class JdkProvider(val name: String, val resolve: suspend () -> String?)
        val providers = listOf(
            JdkProvider("Adoptium") { adoptiumUrl(majorVersion, os, arch) },
            JdkProvider("Azul Zulu") { zuluUrl(majorVersion, os, arch) },
            JdkProvider("Corretto") { correttoUrl(majorVersion, os, arch) }
        )

        val ext = if (os == "windows") "zip" else "tar.gz"
        val archiveFile = jdkCacheDir.resolve("jdk-$majorVersion.$ext")

        for (provider in providers) {
            val name = provider.name
            try {
                val url = provider.resolve() ?: continue
                logger.info("Downloading Java {} from {} ({}/{})...", majorVersion, name, os, arch)

                val downloaded = streamToFile(url, archiveFile)
                if (!downloaded) {
                    logger.debug("{} download failed for Java {}, trying next provider...", name, majorVersion)
                    continue
                }

                val result = extractJdk(majorVersion, os, archiveFile)
                if (result != null) {
                    logger.info("Java {} installed from {} to {}", majorVersion, name, result)
                    return result
                }
            } catch (e: Exception) {
                logger.debug("{} failed for Java {}: {}, trying next provider...", name, majorVersion, e.message)
                Files.deleteIfExists(archiveFile)
            }
        }

        logger.error("Failed to download Java {} from all providers — install it manually", majorVersion)
        return null
    }

    /**
     * Streams a URL directly to a file without buffering in memory.
     * Uses prepareGet/execute for true streaming (avoids OOM on large downloads).
     */
    private suspend fun streamToFile(url: String, target: Path): Boolean {
        return client.prepareGet(url).execute { response ->
            if (response.status != HttpStatusCode.OK) return@execute false
            val channel = response.bodyAsChannel()
            var totalBytes = 0L
            Files.newOutputStream(target).use { out ->
                val buffer = ByteArray(8192)
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer)
                    if (read > 0) {
                        out.write(buffer, 0, read)
                        totalBytes += read
                    }
                }
            }
            val sizeMb = String.format("%.1f", totalBytes / 1024.0 / 1024.0)
            logger.info("Downloaded {} MB", sizeMb)
            totalBytes > 0
        }
    }

    private fun adoptiumUrl(majorVersion: Int, os: String, arch: String): String {
        return "https://api.adoptium.net/v3/binary/latest/$majorVersion/ga/$os/$arch/jdk/hotspot/normal/eclipse"
    }

    private suspend fun zuluUrl(majorVersion: Int, os: String, arch: String): String? {
        val zuluOs = when (os) { "mac" -> "macos"; else -> os }
        val zuluArch = when (arch) { "x64" -> "x86"; "x32" -> "i686"; else -> arch }
        val ext = if (os == "windows") "zip" else "tar.gz"
        val apiUrl = "https://api.azul.com/metadata/v1/zulu/packages/" +
            "?java_version=$majorVersion&os=$zuluOs&arch=$zuluArch" +
            "&archive_type=$ext&java_package_type=jdk&latest=true&availability_types=CA"

        val response = client.get(apiUrl)
        if (response.status != HttpStatusCode.OK) return null

        val packages = json.parseToJsonElement(response.bodyAsText()).jsonArray
        if (packages.isEmpty()) return null

        return packages[0].jsonObject["download_url"]?.jsonPrimitive?.contentOrNull
    }

    private fun correttoUrl(majorVersion: Int, os: String, arch: String): String? {
        // Corretto only provides LTS + recent versions: 8, 11, 17, 21, 23, 24, 25
        val correttoOs = when (os) { "mac" -> "macos"; else -> os }
        val ext = if (os == "windows") "zip" else "tar.gz"
        return "https://corretto.aws/downloads/latest/amazon-corretto-$majorVersion-$arch-$correttoOs-jdk.$ext"
    }

    private fun extractJdk(majorVersion: Int, os: String, archiveFile: Path): String? {
        val ext = if (os == "windows") "zip" else "tar.gz"
        val extractDir = jdkCacheDir.resolve("java-$majorVersion")
        if (extractDir.exists()) {
            Files.walk(extractDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
        Files.createDirectories(extractDir)

        val extracted = if (ext == "tar.gz") {
            val exitCode = ProcessBuilder("tar", "xzf", archiveFile.toAbsolutePath().toString(), "--strip-components=1", "-C", extractDir.toAbsolutePath().toString())
                .redirectErrorStream(true).start().waitFor()
            exitCode == 0
        } else {
            extractZip(archiveFile, extractDir)
        }

        Files.deleteIfExists(archiveFile)

        if (!extracted) {
            logger.debug("Extraction failed for Java {}", majorVersion)
            return null
        }

        val javaBin = findJavaBin(extractDir)
            ?: extractDir.toFile().listFiles()?.firstOrNull { it.isDirectory }?.let { findJavaBin(it.toPath()) }

        if (javaBin != null) {
            try { Path.of(javaBin).setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x")) } catch (_: Exception) {}
            return javaBin
        }

        logger.debug("Could not find java binary in {}", extractDir)
        return null
    }

    /**
     * Extracts a .zip archive using Java's built-in ZipInputStream (no external tools needed).
     * Strips the top-level directory (like tar --strip-components=1).
     */
    private fun extractZip(zipFile: Path, targetDir: Path): Boolean {
        return try {
            ZipInputStream(Files.newInputStream(zipFile)).use { zis ->
                var entry = zis.nextEntry
                // Detect common top-level directory to strip
                val topDir = entry?.name?.substringBefore("/", "")
                var entryCount = 0
                var totalExtractedSize = 0L
                val maxEntries = 50_000
                val maxTotalSize = 10L * 1024 * 1024 * 1024 // 10 GB
                while (entry != null) {
                    entryCount++
                    if (entryCount > maxEntries) {
                        logger.error("ZIP extraction aborted: exceeded {} entries", maxEntries)
                        return false
                    }
                    val name = entry.name
                    // Strip top-level directory (e.g., "jdk-16.0.2+7/bin/java" -> "bin/java")
                    val stripped = if (topDir != null && topDir.isNotEmpty() && name.startsWith("$topDir/")) {
                        name.removePrefix("$topDir/")
                    } else {
                        name
                    }
                    if (stripped.isEmpty()) {
                        entry = zis.nextEntry
                        continue
                    }
                    val outPath = targetDir.resolve(stripped)
                    if (entry.isDirectory) {
                        Files.createDirectories(outPath)
                    } else {
                        Files.createDirectories(outPath.parent)
                        Files.newOutputStream(outPath).use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = zis.read(buf)
                                if (n <= 0) break
                                totalExtractedSize += n
                                if (totalExtractedSize > maxTotalSize) {
                                    logger.error("ZIP extraction aborted: exceeded {}GB total size", maxTotalSize / (1024 * 1024 * 1024))
                                    return false
                                }
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            logger.debug("ZIP extraction failed: {}", e.message)
            false
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

        // Check JAVA_HOME and JDK_HOME
        for (envName in listOf("JAVA_HOME", "JDK_HOME")) {
            val envValue = System.getenv(envName)
            if (envValue != null) {
                val javaBin = findJavaBin(Path.of(envValue))
                if (javaBin != null) {
                    val version = probeJavaVersion(javaBin)
                    if (version != null && version !in found) {
                        found[version] = javaBin
                        logger.debug("Found Java {} via {}: {}", version, envName, javaBin)
                    }
                }
            }
        }

        // Scan common directories (platform-specific)
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val scanDirs = buildList {
            // Unix / WSL paths
            add("/usr/lib/jvm")
            add("/usr/java")
            add("/usr/local/java")
            add("${System.getProperty("user.home")}/.sdkman/candidates/java")
            add("${System.getProperty("user.home")}/.jdks")
            // WSL-mapped Windows paths
            add("/mnt/c/Program Files/Java")
            add("/mnt/c/Program Files/Eclipse Adoptium")
            add("/mnt/c/Program Files/Microsoft")
            // Native Windows paths
            if (isWindows) {
                val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
                add("$programFiles\\Java")
                add("$programFiles\\Eclipse Adoptium")
                add("$programFiles\\Microsoft")
                add("$programFiles\\BellSoft")
                add("$programFiles\\Amazon Corretto")
                add("$programFiles\\Zulu")
                add("$programFiles\\AdoptOpenJDK")
                val programFilesX86 = System.getenv("ProgramFiles(x86)")
                if (programFilesX86 != null) {
                    add("$programFilesX86\\Java")
                }
                val localAppData = System.getenv("LOCALAPPDATA")
                if (localAppData != null) {
                    add("$localAppData\\Programs\\Eclipse Adoptium")
                }
            }
        }

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
