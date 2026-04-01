package dev.kryonix.nimbus.api.routes

import dev.kryonix.nimbus.api.ApiMessage
import dev.kryonix.nimbus.api.NimbusApi
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val logger = LoggerFactory.getLogger("TemplateRoutes")

fun Route.templateRoutes(
    templatesDir: Path,
    clusterToken: String
) {
    // GET /api/templates/{name}/download?token=...&software=PAPER
    get("/api/templates/{name}/download") {
        val clientToken = call.queryParameters["token"] ?: ""
        if (clusterToken.isNotBlank() && !NimbusApi.timingSafeEquals(clientToken, clusterToken)) {
            return@get call.respond(HttpStatusCode.Unauthorized, ApiMessage(false, "Invalid token"))
        }

        val templateName = call.parameters["name"]!!
        val software = call.queryParameters["software"]?.uppercase() ?: ""
        val templateDir = templatesDir.resolve(templateName)

        if (!templateDir.toFile().exists() || !templateDir.toFile().isDirectory) {
            return@get call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Template '$templateName' not found"))
        }

        // Collect directories to include: group template + applicable global templates
        val dirsToInclude = mutableListOf(templateDir)
        val globalDirs = resolveGlobalDirs(templatesDir, software)
        dirsToInclude.addAll(globalDirs)

        // Stream as ZIP — group template first, then global overlays
        val baos = ByteArrayOutputStream()
        val addedPaths = mutableSetOf<String>()

        ZipOutputStream(baos).use { zos ->
            // Global templates first (so group template files take priority)
            for (globalDir in globalDirs) {
                if (!globalDir.toFile().exists()) continue
                addDirectoryToZip(zos, globalDir, addedPaths)
            }
            // Group template last (overwrites global files with same path)
            addDirectoryToZip(zos, templateDir, addedPaths)
        }

        call.respondBytes(baos.toByteArray(), ContentType.Application.Zip)
        logger.info("Served template '{}' with {} global overlay(s) ({} bytes)", templateName, globalDirs.size, baos.size())
    }

    // GET /api/templates/{name}/hash?software=PAPER — returns SHA-256 hash including global templates
    get("/api/templates/{name}/hash") {
        val clientToken = call.queryParameters["token"] ?: ""
        if (clusterToken.isNotBlank() && !NimbusApi.timingSafeEquals(clientToken, clusterToken)) {
            return@get call.respond(HttpStatusCode.Unauthorized, ApiMessage(false, "Invalid token"))
        }

        val templateName = call.parameters["name"]!!
        val software = call.queryParameters["software"]?.uppercase() ?: ""
        val templateDir = templatesDir.resolve(templateName)

        if (!templateDir.toFile().exists()) {
            return@get call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Template '$templateName' not found"))
        }

        val digest = java.security.MessageDigest.getInstance("SHA-256")

        // Hash global templates first, then group template
        val globalDirs = resolveGlobalDirs(templatesDir, software)
        for (globalDir in globalDirs) {
            if (!globalDir.toFile().exists()) continue
            hashDirectory(digest, globalDir)
        }
        hashDirectory(digest, templateDir)

        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        call.respondText(hash)
    }
}

/**
 * Determines which global template directories apply for a given software type.
 */
private fun resolveGlobalDirs(templatesDir: Path, software: String): List<Path> {
    val dirs = mutableListOf<Path>()
    val vanillaBased = software in listOf("PAPER", "PURPUR", "VELOCITY")
    if (vanillaBased) {
        dirs.add(templatesDir.resolve("global"))
    }
    if (software == "VELOCITY") {
        dirs.add(templatesDir.resolve("global_proxy"))
    }
    return dirs
}

private fun addDirectoryToZip(zos: ZipOutputStream, dir: Path, addedPaths: MutableSet<String>) {
    Files.walk(dir).use { stream ->
        stream.filter { Files.isRegularFile(it) }.forEach { file ->
            val relativePath = dir.relativize(file).toString().replace('\\', '/')
            if (relativePath !in addedPaths) {
                zos.putNextEntry(ZipEntry(relativePath))
                Files.copy(file, zos)
                zos.closeEntry()
                addedPaths.add(relativePath)
            }
        }
    }
}

private fun hashDirectory(digest: java.security.MessageDigest, dir: Path) {
    Files.walk(dir).use { stream ->
        stream.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
            digest.update(dir.relativize(file).toString().toByteArray())
            digest.update(Files.readAllBytes(file))
        }
    }
}
