package dev.kryonix.nimbus.agent

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class TemplateDownloader(
    private val templatesDir: Path,
    private val controllerBaseUrl: String,
    private val token: String
) {
    private val logger = LoggerFactory.getLogger(TemplateDownloader::class.java)
    private val client = HttpClient(CIO)
    private val templateHashes = mutableMapOf<String, String>()

    suspend fun ensureTemplate(templateName: String, expectedHash: String, software: String = ""): Boolean {
        val templateDir = templatesDir.resolve(templateName)

        // Check if we already have this version
        val currentHash = templateHashes[templateName]
        if (templateDir.exists() && currentHash == expectedHash) {
            logger.debug("Template '{}' is up to date", templateName)
            return true
        }

        // Download from controller (include software so global templates are bundled)
        logger.info("Downloading template '{}' from controller...", templateName)
        val softwareParam = if (software.isNotBlank()) "&software=$software" else ""
        val url = "$controllerBaseUrl/api/templates/$templateName/download?token=$token$softwareParam"

        val response = client.get(url)
        if (response.status != HttpStatusCode.OK) {
            logger.error("Failed to download template '{}': HTTP {}", templateName, response.status)
            return false
        }

        // Extract ZIP to template dir
        if (templateDir.exists()) {
            Files.walk(templateDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
        templateDir.createDirectories()

        val bytes = response.readRawBytes()
        ZipInputStream(bytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val target = templateDir.resolve(entry.name)
                if (entry.isDirectory) {
                    target.createDirectories()
                } else {
                    target.parent.createDirectories()
                    Files.write(target, zis.readBytes())
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        templateHashes[templateName] = expectedHash
        logger.info("Template '{}' downloaded and extracted ({} bytes)", templateName, bytes.size)
        return true
    }
}
