package dev.nimbuspowered.nimbus.template

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Copies changed files from a service's working directory back to its template directory.
 * Used for persistent game modes (SMP, SkyBlock) where config changes should survive restarts.
 */
class ServiceDeployer {

    private val logger = LoggerFactory.getLogger(ServiceDeployer::class.java)

    /**
     * Deploys changed files from [workDir] back to [templateDir].
     *
     * @param workDir The service's working directory (source of changes)
     * @param templateDir The template directory to update
     * @param excludePatterns Glob patterns for files/directories to exclude
     * @return Number of files deployed
     */
    fun deployBack(workDir: Path, templateDir: Path, excludePatterns: List<String>): Int {
        if (!workDir.exists() || !workDir.isDirectory()) return 0
        if (!templateDir.exists()) Files.createDirectories(templateDir)

        var deployed = 0

        Files.walk(workDir).use { stream ->
            stream.forEach { source ->
                if (source == workDir) return@forEach
                val relativePath = workDir.relativize(source)
                val relativeStr = relativePath.toString().replace('\\', '/')

                // Check exclusion patterns
                if (shouldExclude(relativeStr, excludePatterns)) return@forEach

                val target = templateDir.resolve(relativePath)

                if (source.isDirectory()) {
                    if (!target.exists()) Files.createDirectories(target)
                } else if (source.isRegularFile()) {
                    // Only copy if the file is new or has different content
                    if (!target.exists() || !filesEqual(source, target)) {
                        Files.createDirectories(target.parent)
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                        deployed++
                    }
                }
            }
        }

        if (deployed > 0) {
            logger.info("Deployed {} changed file(s) from {} back to template {}", deployed, workDir, templateDir)
        }
        return deployed
    }

    private fun shouldExclude(relativePath: String, patterns: List<String>): Boolean {
        val topLevel = relativePath.split("/").first()
        for (pattern in patterns) {
            if (pattern.startsWith("*.")) {
                // Extension pattern: *.tmp matches any file ending in .tmp
                if (relativePath.endsWith(pattern.removePrefix("*"))) return true
            } else {
                // Directory/file name pattern: matches top-level directory or exact file name
                if (topLevel == pattern || relativePath == pattern) return true
            }
        }
        return false
    }

    private fun filesEqual(a: Path, b: Path): Boolean {
        if (Files.size(a) != Files.size(b)) return false
        val digestA = sha256(a)
        val digestB = sha256(b)
        return digestA.contentEquals(digestB)
    }

    private fun sha256(path: Path): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(Files.readAllBytes(path))
        return digest.digest()
    }
}
