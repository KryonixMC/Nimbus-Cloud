package dev.nimbus.template

import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeLines
import kotlin.io.path.writeText

class ConfigPatcher {

    private val logger = LoggerFactory.getLogger(ConfigPatcher::class.java)

    fun patchServerProperties(workDir: Path, port: Int) {
        val file = workDir.resolve("server.properties")
        if (!file.exists()) {
            // Create minimal server.properties so the server uses our port
            file.writeText(buildString {
                appendLine("server-port=$port")
                appendLine("online-mode=false")
                appendLine("server-ip=0.0.0.0")
            })
            logger.debug("Created server.properties with port {} at {}", port, workDir)
            return
        }

        val patched = file.readLines().map { line ->
            when {
                line.trimStart().startsWith("server-port") -> "server-port=$port"
                line.trimStart().startsWith("online-mode") -> "online-mode=false"
                else -> line
            }
        }
        file.writeLines(patched)
    }

    fun patchVelocityConfig(workDir: Path, port: Int) {
        val file = workDir.resolve("velocity.toml")
        if (!file.exists()) return

        val patched = file.readLines().map { line ->
            when {
                line.trimStart().startsWith("bind") && !line.contains("bungee") -> "bind = \"0.0.0.0:$port\""
                line.trimStart().startsWith("player-info-forwarding-mode") -> "player-info-forwarding-mode = \"modern\""
                line.trimStart().startsWith("online-mode") && !line.contains("#") -> "online-mode = true"
                else -> line
            }
        }
        file.writeLines(patched)
    }

    /**
     * Configures Paper server for Velocity modern forwarding.
     * - Sets online-mode=false in server.properties
     * - Enables velocity support in paper-global.yml or config/paper-global.yml
     * - Copies the forwarding secret from the Velocity template
     */
    fun patchPaperForVelocity(workDir: Path, velocityTemplateDir: Path) {
        // Copy forwarding.secret from Velocity template
        val secretSource = velocityTemplateDir.resolve("forwarding.secret")
        val secretTarget = workDir.resolve("forwarding.secret")
        if (secretSource.exists() && !secretTarget.exists()) {
            val secret = secretSource.readText().trim()
            secretTarget.writeText(secret)
            logger.debug("Copied forwarding.secret to {}", workDir)
        }

        // Patch paper-global.yml (Paper 1.19+)
        // This file might be in config/ subdirectory
        val paperGlobalPaths = listOf(
            workDir.resolve("config/paper-global.yml"),
            workDir.resolve("paper-global.yml")
        )

        for (paperGlobal in paperGlobalPaths) {
            if (paperGlobal.exists()) {
                patchPaperGlobalYml(paperGlobal)
                return
            }
        }

        // Paper hasn't generated configs yet — create a minimal config/paper-global.yml
        // Paper will merge this with defaults on first start
        val configDir = workDir.resolve("config")
        if (!configDir.exists()) configDir.createDirectories()
        val paperGlobal = configDir.resolve("paper-global.yml")
        paperGlobal.writeText(buildString {
            appendLine("proxies:")
            appendLine("  velocity:")
            appendLine("    enabled: true")
            appendLine("    online-mode: true")
            appendLine("    secret: '${readForwardingSecret(velocityTemplateDir)}'")
        })
        logger.debug("Created paper-global.yml with Velocity forwarding at {}", paperGlobal)
    }

    private fun patchPaperGlobalYml(file: Path) {
        val lines = file.readLines().toMutableList()
        var inVelocitySection = false
        var velocitySectionIndent = 0

        for (i in lines.indices) {
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed == "velocity:" || trimmed.startsWith("velocity:")) {
                inVelocitySection = true
                velocitySectionIndent = line.indexOf("velocity:")
                continue
            }

            if (inVelocitySection) {
                val currentIndent = line.length - line.trimStart().length
                if (trimmed.isNotEmpty() && currentIndent <= velocitySectionIndent) {
                    inVelocitySection = false
                    continue
                }
                when {
                    trimmed.startsWith("enabled:") -> lines[i] = line.replaceAfter("enabled:", " true")
                    trimmed.startsWith("online-mode:") -> lines[i] = line.replaceAfter("online-mode:", " true")
                }
            }
        }

        file.writeLines(lines)
        logger.debug("Patched paper-global.yml at {}", file)
    }

    private fun readForwardingSecret(velocityTemplateDir: Path): String {
        val secretFile = velocityTemplateDir.resolve("forwarding.secret")
        return if (secretFile.exists()) secretFile.readText().trim() else ""
    }
}
