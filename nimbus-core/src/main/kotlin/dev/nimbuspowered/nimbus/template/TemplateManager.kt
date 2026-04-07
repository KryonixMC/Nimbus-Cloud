package dev.nimbuspowered.nimbus.template

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink

class TemplateManager {

    private val logger = LoggerFactory.getLogger(TemplateManager::class.java)

    // Directories that should be symlinked instead of copied (large, read-only at runtime)
    private val symlinkDirs = setOf("libraries")

    fun prepareService(
        templateName: String,
        targetDir: Path,
        templatesDir: Path,
        preserveExisting: Boolean = false
    ): Path {
        val sourceDir = templatesDir.resolve(templateName)

        require(sourceDir.exists() && sourceDir.isDirectory()) {
            "Template directory does not exist: $sourceDir"
        }

        logger.info("Preparing service from template '{}' -> {}{}", templateName, targetDir,
            if (preserveExisting) " (static, preserving existing)" else "")

        Files.createDirectories(targetDir)

        Files.walk(sourceDir).use { stream ->
            stream.forEach { source ->
                val relativePath = sourceDir.relativize(source)
                val destination = targetDir.resolve(relativePath)

                // Check if this is a top-level directory that should be symlinked
                val topLevelName = relativePath.getName(0).toString()
                if (topLevelName in symlinkDirs && source != sourceDir) {
                    // If we're at the top-level symlink dir itself, create the symlink
                    if (relativePath.nameCount == 1 && Files.isDirectory(source)) {
                        if (!destination.exists() && !destination.isSymbolicLink()) {
                            Files.createSymbolicLink(destination, source.toAbsolutePath())
                            logger.debug("Symlinked {} -> {}", destination, source.toAbsolutePath())
                        }
                    }
                    // Skip all children of symlinked directories (the symlink covers them)
                    return@forEach
                }

                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination)
                } else if (preserveExisting && destination.exists()) {
                    // Static services: don't overwrite existing files (world data, configs, etc.)
                    return@forEach
                } else {
                    try {
                        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
                    } catch (e: java.nio.file.FileSystemException) {
                        logger.debug("Skipping locked file: {} ({})", destination, e.message)
                    }
                }
            }
        }

        logger.info("Template '{}' prepared in '{}'", templateName, targetDir)
        return targetDir
    }

    /**
     * Prepares a service from a stack of templates applied in order.
     * The first template is the base, subsequent templates overlay on top (overwriting files).
     * This enables template composition: e.g., ["global", "paper-shared", "BedWars"].
     */
    fun prepareServiceFromStack(
        templateNames: List<String>,
        targetDir: Path,
        templatesDir: Path,
        preserveExisting: Boolean = false
    ): Path {
        require(templateNames.isNotEmpty()) { "Template stack must not be empty" }

        if (templateNames.size == 1) {
            return prepareService(templateNames.first(), targetDir, templatesDir, preserveExisting)
        }

        logger.info("Preparing service from template stack {} -> {}", templateNames, targetDir)
        Files.createDirectories(targetDir)

        // Apply first template as base
        prepareService(templateNames.first(), targetDir, templatesDir, preserveExisting)

        // Overlay remaining templates in order (always overwrite)
        for (i in 1 until templateNames.size) {
            val overlayDir = templatesDir.resolve(templateNames[i])
            if (overlayDir.exists() && overlayDir.isDirectory()) {
                applyGlobalTemplate(overlayDir, targetDir)
                logger.info("Applied template overlay '{}' to '{}'", templateNames[i], targetDir)
            } else {
                logger.warn("Template '{}' in stack does not exist, skipping", templateNames[i])
            }
        }

        return targetDir
    }

    /**
     * Overlays a global template directory onto the service working directory.
     * Always overwrites existing files (used for shared plugins, configs, etc.).
     */
    fun applyGlobalTemplate(globalDir: Path, targetDir: Path) {
        if (!globalDir.exists() || !globalDir.isDirectory()) return

        Files.walk(globalDir).use { stream ->
            stream.forEach { source ->
                if (source == globalDir) return@forEach
                val relativePath = globalDir.relativize(source)
                val destination = targetDir.resolve(relativePath)

                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination)
                } else {
                    try {
                        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
                    } catch (e: java.nio.file.FileSystemException) {
                        logger.debug("Skipping locked file: {} ({})", destination, e.message)
                    }
                }
            }
        }

        logger.debug("Applied global template '{}' to '{}'", globalDir.fileName, targetDir)
    }
}
