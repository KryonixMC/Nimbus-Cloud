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
                    // If we're at the top-level symlink dir itself, link it
                    if (relativePath.nameCount == 1 && Files.isDirectory(source)) {
                        if (!destination.exists() && !destination.isSymbolicLink()) {
                            linkDirectory(source.toAbsolutePath(), destination)
                        }
                    }
                    // Skip all children — the link/copy covers them
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

    /**
     * Links a directory from source to destination. Tries in order:
     * 1. Windows junction (mklink /J) — no admin rights needed, works on NTFS
     * 2. Symbolic link — works on Linux/macOS, Windows needs Developer Mode
     * 3. Full recursive copy — always works, but uses more disk space
     */
    private fun linkDirectory(source: Path, destination: Path) {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")

        // On Windows, try a directory junction first (no admin rights required)
        if (isWindows) {
            try {
                val process = ProcessBuilder("cmd", "/c", "mklink", "/J",
                    destination.toAbsolutePath().toString(),
                    source.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                if (exitCode == 0 && destination.exists()) {
                    logger.debug("Created junction {} -> {}", destination, source)
                    return
                }
                logger.debug("Junction failed (exit {}): {}", exitCode, output.trim())
            } catch (e: Exception) {
                logger.debug("Junction failed for {}: {}", destination, e.message)
            }
        }

        // Try symbolic link (Linux/macOS, or Windows with Developer Mode)
        try {
            Files.createSymbolicLink(destination, source)
            logger.debug("Symlinked {} -> {}", destination, source)
            return
        } catch (e: Exception) {
            logger.debug("Symlink failed for {}: {}", destination, e.message)
        }

        // Last resort: full directory copy
        logger.info("Copying directory {} -> {} (symlink/junction unavailable)", source.fileName, destination)
        Files.walk(source).use { stream ->
            stream.forEach { src ->
                val dest = destination.resolve(source.relativize(src))
                try {
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dest)
                    } else {
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
                    }
                } catch (e: Exception) {
                    logger.debug("Copy failed: {} ({})", dest, e.message)
                }
            }
        }
    }
}
