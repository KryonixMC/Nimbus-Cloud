package dev.nimbus.api.routes

import dev.nimbus.api.ApiMessage
import dev.nimbus.api.NimbusApi
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
    // GET /api/templates/{name}/download?token=...
    get("/api/templates/{name}/download") {
        val clientToken = call.queryParameters["token"] ?: ""
        if (clusterToken.isNotBlank() && !NimbusApi.timingSafeEquals(clientToken, clusterToken)) {
            return@get call.respond(HttpStatusCode.Unauthorized, ApiMessage(false, "Invalid token"))
        }

        val templateName = call.parameters["name"]!!
        val templateDir = templatesDir.resolve(templateName)

        if (!templateDir.toFile().exists() || !templateDir.toFile().isDirectory) {
            return@get call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Template '$templateName' not found"))
        }

        // Stream as ZIP
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            Files.walk(templateDir).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { file ->
                    val relativePath = templateDir.relativize(file).toString()
                    zos.putNextEntry(ZipEntry(relativePath))
                    Files.copy(file, zos)
                    zos.closeEntry()
                }
            }
        }

        call.respondBytes(baos.toByteArray(), ContentType.Application.Zip)
        logger.info("Served template '{}' ({} bytes)", templateName, baos.size())
    }

    // GET /api/templates/{name}/hash — returns SHA-256 hash of template contents
    get("/api/templates/{name}/hash") {
        val clientToken = call.queryParameters["token"] ?: ""
        if (clusterToken.isNotBlank() && !NimbusApi.timingSafeEquals(clientToken, clusterToken)) {
            return@get call.respond(HttpStatusCode.Unauthorized, ApiMessage(false, "Invalid token"))
        }

        val templateName = call.parameters["name"]!!
        val templateDir = templatesDir.resolve(templateName)

        if (!templateDir.toFile().exists()) {
            return@get call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Template '$templateName' not found"))
        }

        val digest = java.security.MessageDigest.getInstance("SHA-256")
        Files.walk(templateDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                digest.update(templateDir.relativize(file).toString().toByteArray())
                digest.update(Files.readAllBytes(file))
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        call.respondText(hash)
    }
}
