package dev.nimbuspowered.nimbus.agent

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

    /**
     * Ensures all templates in a stack are downloaded. Uses a combined hash
     * keyed by the stack signature to detect when any template has changed.
     */
    suspend fun ensureTemplates(templateNames: List<String>, expectedHash: String, software: String = ""): Boolean {
        if (templateNames.size <= 1) {
            return ensureTemplate(templateNames.firstOrNull() ?: return false, expectedHash, software)
        }

        val stackKey = templateNames.joinToString("+")
        val currentHash = templateHashes[stackKey]
        val allExist = templateNames.all { templatesDir.resolve(it).exists() }
        if (allExist && currentHash == expectedHash) {
            logger.debug("Template stack {} is up to date", templateNames)
            return true
        }

        // Download each template individually
        for (tmpl in templateNames) {
            val ok = ensureTemplate(tmpl, "", software)
            if (!ok) return false
        }
        templateHashes[stackKey] = expectedHash
        return true
    }

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
        val normalizedTemplateDir = templateDir.normalize().toAbsolutePath()
        ZipInputStream(bytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val target = normalizedTemplateDir.resolve(entry.name).normalize()
                // Zip Slip protection: ensure extracted path stays within template directory
                if (!target.startsWith(normalizedTemplateDir)) {
                    logger.warn("Skipping malicious ZIP entry '{}' (path traversal attempt)", entry.name)
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
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
