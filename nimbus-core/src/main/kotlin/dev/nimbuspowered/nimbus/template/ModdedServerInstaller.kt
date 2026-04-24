package dev.nimbuspowered.nimbus.template

import dev.nimbuspowered.nimbus.config.ServerSoftware
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize

internal class ModdedServerInstaller(
    private val client: HttpClient,
    private val json: Json
) {
    private val logger = LoggerFactory.getLogger(ModdedServerInstaller::class.java)

    // ── Forge version fetching ─────────────────────────────────────

    suspend fun fetchForgeVersions(mcVersion: String): SoftwareResolver.VersionList {
        return try {
            val url = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"
            val response = client.get(url)
            if (response.status != HttpStatusCode.OK) return SoftwareResolver.VersionList.EMPTY
            val data = json.decodeFromString<ForgePromotions>(response.bodyAsText())
            val versions = data.promos.entries
                .filter { it.key.startsWith("$mcVersion-") }
                .map { it.value }
                .distinct()
            SoftwareResolver.VersionList(stable = versions, snapshots = emptyList())
        } catch (e: Exception) {
            logger.error("Failed to fetch Forge versions: {}", e.message)
            SoftwareResolver.VersionList.EMPTY
        }
    }

    suspend fun fetchForgeGameVersions(): SoftwareResolver.VersionList {
        return try {
            val url = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"
            val response = client.get(url)
            if (response.status != HttpStatusCode.OK) return SoftwareResolver.VersionList.EMPTY
            val data = json.decodeFromString<ForgePromotions>(response.bodyAsText())
            val versions = data.promos.keys.map { it.substringBefore("-") }.distinct().sortedDescending()
            SoftwareResolver.VersionList(stable = versions, snapshots = emptyList())
        } catch (e: Exception) {
            logger.error("Failed to fetch Forge game versions: {}", e.message)
            SoftwareResolver.VersionList.EMPTY
        }
    }

    // ── NeoForge version fetching ───────────────────────────────

    suspend fun fetchNeoForgeVersions(mcVersion: String): SoftwareResolver.VersionList {
        return try {
            val url = "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge"
            val response = client.get(url)
            if (response.status != HttpStatusCode.OK) return SoftwareResolver.VersionList.EMPTY
            val data = json.decodeFromString<NeoForgeVersionsResponse>(response.bodyAsText())
            val parts = mcVersion.split(".")
            val minor = parts.getOrNull(1) ?: return SoftwareResolver.VersionList.EMPTY
            val patch = parts.getOrNull(2) ?: "0"
            val prefix = "$minor.$patch."
            val matching = data.versions.filter { it.startsWith(prefix) }.reversed()
            val stable = matching.filter { !it.contains("beta") && !it.contains("rc") }
            val snapshots = matching.filter { it.contains("beta") || it.contains("rc") }
            SoftwareResolver.VersionList(stable = stable.ifEmpty { matching }, snapshots = snapshots)
        } catch (e: Exception) {
            logger.error("Failed to fetch NeoForge versions: {}", e.message)
            SoftwareResolver.VersionList.EMPTY
        }
    }

    suspend fun fetchNeoForgeGameVersions(): SoftwareResolver.VersionList {
        return try {
            val url = "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge"
            val response = client.get(url)
            if (response.status != HttpStatusCode.OK) return SoftwareResolver.VersionList.EMPTY
            val data = json.decodeFromString<NeoForgeVersionsResponse>(response.bodyAsText())
            val mcVersions = data.versions.mapNotNull { ver ->
                val vParts = ver.split(".")
                if (vParts.size >= 2) "1.${vParts[0]}.${vParts[1]}" else null
            }.distinct().reversed()
            SoftwareResolver.VersionList(stable = mcVersions, snapshots = emptyList())
        } catch (e: Exception) {
            logger.error("Failed to fetch NeoForge game versions: {}", e.message)
            SoftwareResolver.VersionList.EMPTY
        }
    }

    // ── Fabric version fetching ─────────────────────────────────

    suspend fun fetchFabricLoaderVersions(): SoftwareResolver.VersionList {
        return try {
            val url = "https://meta.fabricmc.net/v2/versions/loader"
            val response = client.get(url)
            if (response.status != HttpStatusCode.OK) return SoftwareResolver.VersionList.EMPTY
            val data = json.decodeFromString<List<FabricLoaderVersion>>(response.bodyAsText())
            val stable = data.filter { it.stable }.map { it.version }
            val snapshots = data.filter { !it.stable }.map { it.version }
            SoftwareResolver.VersionList(stable = stable, snapshots = snapshots)
        } catch (e: Exception) {
            logger.error("Failed to fetch Fabric loader versions: {}", e.message)
            SoftwareResolver.VersionList.EMPTY
        }
    }

    suspend fun fetchFabricGameVersions(): SoftwareResolver.VersionList {
        return try {
            val url = "https://meta.fabricmc.net/v2/versions/game"
            val response = client.get(url)
            if (response.status != HttpStatusCode.OK) return SoftwareResolver.VersionList.EMPTY
            val data = json.decodeFromString<List<FabricGameVersion>>(response.bodyAsText())
            val stable = data.filter { it.stable }.map { it.version }
            val snapshots = data.filter { !it.stable }.map { it.version }
            SoftwareResolver.VersionList(stable = stable, snapshots = snapshots)
        } catch (e: Exception) {
            logger.error("Failed to fetch Fabric game versions: {}", e.message)
            SoftwareResolver.VersionList.EMPTY
        }
    }

    // ── Server installers ──────────────────────────────────────

    suspend fun installForge(mcVersion: String, forgeVersion: String, targetDir: Path): Boolean {
        return try {
            val loaderVer = forgeVersion.ifEmpty {
                val versions = fetchForgeVersions(mcVersion)
                versions.latest ?: run {
                    logger.error("No Forge versions found for MC {}", mcVersion)
                    return false
                }
            }

            val installerUrl = "https://maven.minecraftforge.net/net/minecraftforge/forge/$mcVersion-$loaderVer/forge-$mcVersion-$loaderVer-installer.jar"
            val installerFile = targetDir.resolve("forge-installer.jar")

            Files.createDirectories(targetDir)

            val response = client.get(installerUrl)
            if (response.status != HttpStatusCode.OK) {
                logger.error("Failed to download Forge installer: HTTP {}", response.status)
                return false
            }
            response.bodyAsChannel().toInputStream().use { input ->
                Files.newOutputStream(installerFile).use { out -> input.copyTo(out, 65536) }
            }
            logger.info("Downloaded Forge installer ({} MB)", String.format("%.1f", installerFile.fileSize() / 1024.0 / 1024.0))

            val process = withContext(Dispatchers.IO) {
                ProcessBuilder("java", "-jar", "forge-installer.jar", "--installServer")
                    .directory(targetDir.toFile())
                    .redirectErrorStream(true)
                    .start()
            }

            val output = withContext(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
            val exitCode = withContext(Dispatchers.IO) { process.waitFor() }

            Files.deleteIfExists(installerFile)
            Files.deleteIfExists(targetDir.resolve("forge-installer.jar.log"))

            if (exitCode != 0) {
                logger.error("Forge installer failed (exit code {}): {}", exitCode, output.takeLast(500))
                return false
            }

            logger.info("Forge {}-{} installation complete", mcVersion, loaderVer)
            true
        } catch (e: Exception) {
            logger.error("Failed to install Forge: {}", e.message, e)
            false
        }
    }

    suspend fun installNeoForge(mcVersion: String, neoforgeVersion: String, targetDir: Path): Boolean {
        return try {
            val loaderVer = neoforgeVersion.ifEmpty {
                val versions = fetchNeoForgeVersions(mcVersion)
                versions.latest ?: run {
                    logger.error("No NeoForge versions found for MC {}", mcVersion)
                    return false
                }
            }

            val installerUrl = "https://maven.neoforged.net/releases/net/neoforged/neoforge/$loaderVer/neoforge-$loaderVer-installer.jar"
            val installerFile = targetDir.resolve("neoforge-installer.jar")

            Files.createDirectories(targetDir)

            val response = client.get(installerUrl)
            if (response.status != HttpStatusCode.OK) {
                logger.error("Failed to download NeoForge installer: HTTP {}", response.status)
                return false
            }
            response.bodyAsChannel().toInputStream().use { input ->
                Files.newOutputStream(installerFile).use { out -> input.copyTo(out, 65536) }
            }
            logger.info("Downloaded NeoForge installer ({} MB)", String.format("%.1f", installerFile.fileSize() / 1024.0 / 1024.0))

            val process = withContext(Dispatchers.IO) {
                ProcessBuilder("java", "-jar", "neoforge-installer.jar", "--install-server")
                    .directory(targetDir.toFile())
                    .redirectErrorStream(true)
                    .start()
            }

            val output = withContext(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
            val exitCode = withContext(Dispatchers.IO) { process.waitFor() }

            Files.deleteIfExists(installerFile)
            Files.deleteIfExists(targetDir.resolve("neoforge-installer.jar.log"))

            if (exitCode != 0) {
                logger.error("NeoForge installer failed (exit code {}): {}", exitCode, output.takeLast(500))
                return false
            }

            logger.info("NeoForge {} installation complete", loaderVer)
            true
        } catch (e: Exception) {
            logger.error("Failed to install NeoForge: {}", e.message, e)
            false
        }
    }

    suspend fun installFabric(mcVersion: String, loaderVersion: String, targetDir: Path): Boolean {
        return try {
            // Always use the latest stable Fabric loader — it's backwards compatible
            // and avoids conflicts with proxy mods that may require newer versions
            val latestLoader = fetchFabricLoaderVersions().latest
            val loaderVer = latestLoader ?: loaderVersion.ifEmpty {
                logger.error("No Fabric loader versions found")
                return false
            }
            if (loaderVersion.isNotEmpty() && latestLoader != null && latestLoader != loaderVersion) {
                logger.info("Upgrading Fabric loader {} -> {} (latest stable, backwards compatible)", loaderVersion, latestLoader)
            }

            val installerVer = try {
                val response = client.get("https://meta.fabricmc.net/v2/versions/installer")
                if (response.status != HttpStatusCode.OK) throw Exception("HTTP ${response.status}")
                val installers = json.decodeFromString<List<FabricInstallerVersion>>(response.bodyAsText())
                installers.firstOrNull { it.stable }?.version ?: installers.first().version
            } catch (e: Exception) {
                logger.warn("Failed to fetch Fabric installer version, using fallback: {}", e.message)
                "1.0.1"
            }

            val launcherUrl = "https://meta.fabricmc.net/v2/versions/loader/$mcVersion/$loaderVer/$installerVer/server/jar"

            Files.createDirectories(targetDir)

            val response = client.get(launcherUrl)
            if (response.status != HttpStatusCode.OK) {
                logger.error("Failed to download Fabric server: HTTP {}", response.status)
                return false
            }

            val targetFile = targetDir.resolve("server.jar")
            response.bodyAsChannel().toInputStream().use { input ->
                Files.newOutputStream(targetFile).use { out -> input.copyTo(out, 65536) }
            }

            val sizeMb = String.format("%.1f", targetFile.fileSize() / 1024.0 / 1024.0)
            logger.info("Downloaded Fabric server launcher ({} MB) — MC {} / Loader {}", sizeMb, mcVersion, loaderVer)
            true
        } catch (e: Exception) {
            logger.error("Failed to install Fabric: {}", e.message, e)
            false
        }
    }

    // ── Server JAR detection ────────────────────────────────────

    fun hasServerJar(templateDir: Path, software: ServerSoftware): Boolean {
        val serverJar = templateDir.resolve("server.jar")
        if (serverJar.exists()) return true
        // Check for modded JARs (forge-*.jar, neoforge-*.jar)
        val prefix = when (software) {
            ServerSoftware.FORGE -> "forge-"
            ServerSoftware.NEOFORGE -> "neoforge-"
            else -> return false
        }
        val hasJar = templateDir.toFile().listFiles()?.any {
            it.name.startsWith(prefix) && it.name.endsWith(".jar") && !it.name.contains("installer")
        } ?: false
        if (hasJar) return true
        // Modern Forge/NeoForge: installer creates libraries/ with args file instead of a JAR
        return findArgsFile(templateDir.resolve("libraries")) != null
    }

    // ── Modded startup command ─────────────────────────────────

    fun getModdedStartCommand(software: ServerSoftware, templateDir: Path, customJarName: String = ""): List<String> {
        return when (software) {
            ServerSoftware.CUSTOM -> {
                val jarName = customJarName.ifEmpty { "server.jar" }
                listOf("-jar", jarName)
            }
            ServerSoftware.FORGE, ServerSoftware.NEOFORGE -> {
                // Check for modern Forge/NeoForge with @libraries args file
                val libsDir = templateDir.resolve("libraries")
                val argsFile = findArgsFile(libsDir)
                if (argsFile != null) {
                    listOf("@$argsFile")
                } else {
                    val jar = findModdedJar(templateDir, software)
                    listOf("-jar", jar)
                }
            }
            ServerSoftware.FABRIC -> listOf("-jar", "server.jar")
            else -> listOf("-jar", jarFileName(software))
        }
    }

    private fun findArgsFile(libsDir: Path): String? {
        if (!libsDir.exists()) return null
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val target = if (isWindows) "win_args.txt" else "unix_args.txt"
        return try {
            Files.walk(libsDir, Int.MAX_VALUE, FileVisitOption.FOLLOW_LINKS).use { stream ->
                stream.filter { it.fileName.toString() == target }
                    .findFirst()
                    .map { libsDir.parent.relativize(it).toString() }
                    .orElse(null)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findModdedJar(templateDir: Path, software: ServerSoftware): String {
        val prefix = when (software) {
            ServerSoftware.FORGE -> "forge-"
            ServerSoftware.NEOFORGE -> "neoforge-"
            else -> ""
        }
        val jar = templateDir.toFile().listFiles()?.find {
            it.name.startsWith(prefix) && it.name.endsWith(".jar") && !it.name.contains("installer")
        }
        return jar?.name ?: "server.jar"
    }

    fun jarFileName(software: ServerSoftware): String = when (software) {
        ServerSoftware.VELOCITY -> "velocity.jar"
        else -> "server.jar"
    }
}
