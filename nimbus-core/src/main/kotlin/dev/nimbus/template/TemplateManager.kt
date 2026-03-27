package dev.nimbus.template

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class TemplateManager {

    private val logger = LoggerFactory.getLogger(TemplateManager::class.java)

    fun prepareService(
        templateName: String,
        serviceName: String,
        templatesDir: Path,
        runningDir: Path
    ): Path {
        val sourceDir = templatesDir.resolve(templateName)
        val targetDir = runningDir.resolve(serviceName)

        require(sourceDir.exists() && sourceDir.isDirectory()) {
            "Template directory does not exist: $sourceDir"
        }

        logger.info("Preparing service '{}' from template '{}' -> {}", serviceName, templateName, targetDir)

        Files.createDirectories(targetDir)

        Files.walk(sourceDir).use { stream ->
            stream.forEach { source ->
                val destination = targetDir.resolve(sourceDir.relativize(source))
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination)
                } else {
                    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }

        logger.info("Template '{}' copied to '{}'", templateName, targetDir)
        return targetDir
    }
}
