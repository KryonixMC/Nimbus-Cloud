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

    fun patchVelocityConfig(workDir: Path, port: Int, forwardingMode: String = "modern", lbProxyProtocol: Boolean = false) {
        val file = workDir.resolve("velocity.toml")
        if (!file.exists()) return

        val patched = file.readLines().map { line ->
            when {
                line.trimStart().startsWith("bind") && !line.contains("bungee") -> "bind = \"0.0.0.0:$port\""
                line.trimStart().startsWith("player-info-forwarding-mode") -> "player-info-forwarding-mode = \"$forwardingMode\""
                line.trimStart().startsWith("online-mode") && !line.contains("#") -> "online-mode = true"
                lbProxyProtocol && line.trimStart().startsWith("haproxy-protocol") -> "haproxy-protocol = true"
                else -> line
            }
        }
        file.writeLines(patched)
        logger.info("Velocity forwarding mode set to '{}'", forwardingMode)
    }

    /**
     * Configures a backend server for BungeeCord/legacy forwarding via spigot.yml.
     * Works for ALL server versions (1.8.8+), used when pre-1.13 servers are in the network.
     */
    fun patchSpigotForBungeeCord(workDir: Path) {
        val file = workDir.resolve("spigot.yml")
        if (file.exists()) {
            val lines = file.readLines().toMutableList()
            var inSettings = false
            var foundBungeecord = false

            for (i in lines.indices) {
                val trimmed = lines[i].trim()
                if (trimmed == "settings:" || trimmed.startsWith("settings:")) {
                    inSettings = true
                    continue
                }
                if (inSettings) {
                    val currentIndent = lines[i].length - lines[i].trimStart().length
                    if (trimmed.isNotEmpty() && currentIndent == 0 && !trimmed.startsWith("#")) {
                        inSettings = false
                        continue
                    }
                    if (trimmed.startsWith("bungeecord:")) {
                        lines[i] = lines[i].replaceAfter("bungeecord:", " true")
                        foundBungeecord = true
                    }
                }
            }

            if (!foundBungeecord) {
                // Append to settings section or create it
                val settingsIdx = lines.indexOfFirst { it.trim() == "settings:" || it.trim().startsWith("settings:") }
                if (settingsIdx >= 0) {
                    lines.add(settingsIdx + 1, "  bungeecord: true")
                } else {
                    lines.add("settings:")
                    lines.add("  bungeecord: true")
                }
            }

            file.writeLines(lines)
        } else {
            // Create minimal spigot.yml
            file.writeText(buildString {
                appendLine("settings:")
                appendLine("  bungeecord: true")
            })
        }
        logger.debug("Patched spigot.yml for BungeeCord forwarding at {}", workDir)
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

        // Paper 1.13–1.18: velocity-support in paper.yml
        val paperYml = workDir.resolve("paper.yml")
        if (paperYml.exists()) {
            patchPaperYml(paperYml, readForwardingSecret(velocityTemplateDir))
            return
        }

        // Paper 1.19+: proxies.velocity in paper-global.yml (may be in config/ subdirectory)
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

        // Paper hasn't generated configs yet — create both formats so whichever version picks it up
        // paper.yml for 1.13–1.18
        val secret = readForwardingSecret(velocityTemplateDir)
        paperYml.writeText(buildString {
            appendLine("settings:")
            appendLine("  velocity-support:")
            appendLine("    enabled: true")
            appendLine("    online-mode: true")
            appendLine("    secret: '$secret'")
        })
        // config/paper-global.yml for 1.19+
        val configDir = workDir.resolve("config")
        if (!configDir.exists()) configDir.createDirectories()
        val paperGlobal = configDir.resolve("paper-global.yml")
        paperGlobal.writeText(buildString {
            appendLine("proxies:")
            appendLine("  velocity:")
            appendLine("    enabled: true")
            appendLine("    online-mode: true")
            appendLine("    secret: '$secret'")
        })
        logger.debug("Created paper.yml + paper-global.yml with Velocity forwarding at {}", workDir)
    }

    /**
     * Patches paper.yml (Paper 1.13–1.18) for Velocity forwarding.
     * Sets settings.velocity-support.enabled=true, online-mode=true, secret=<secret>
     */
    private fun patchPaperYml(file: Path, secret: String) {
        val lines = file.readLines().toMutableList()
        var inVelocitySupport = false
        var velocityIndent = 0

        for (i in lines.indices) {
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed == "velocity-support:" || trimmed.startsWith("velocity-support:")) {
                inVelocitySupport = true
                velocityIndent = line.indexOf("velocity-support:")
                continue
            }

            if (inVelocitySupport) {
                val currentIndent = line.length - line.trimStart().length
                if (trimmed.isNotEmpty() && currentIndent <= velocityIndent) {
                    inVelocitySupport = false
                    continue
                }
                when {
                    trimmed.startsWith("enabled:") -> lines[i] = line.replaceAfter("enabled:", " true")
                    trimmed.startsWith("online-mode:") -> lines[i] = line.replaceAfter("online-mode:", " true")
                    trimmed.startsWith("secret:") -> lines[i] = line.replaceAfter("secret:", " '$secret'")
                }
            }
        }

        file.writeLines(lines)
        logger.debug("Patched paper.yml (1.13-1.18) for Velocity forwarding at {}", file)
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

    /**
     * Configures FabricProxy-Lite for proxy forwarding on Fabric servers.
     * Supports both modern (Velocity secret) and legacy (BungeeCord) modes.
     */
    fun patchFabricProxyLite(workDir: Path, velocityTemplateDir: Path, forwardingMode: String) {
        val configDir = workDir.resolve("config")
        if (!configDir.exists()) configDir.createDirectories()

        val configFile = configDir.resolve("FabricProxy-Lite.toml")
        val secret = readForwardingSecret(velocityTemplateDir)

        if (forwardingMode == "modern") {
            if (secret.isEmpty()) {
                logger.warn("No forwarding.secret found — FabricProxy-Lite modern forwarding will not work")
                return
            }
            writeOrPatchFabricProxyConfig(configFile, hackOnlineMode = true, secret = secret)
            logger.info("FabricProxy-Lite configured for modern forwarding")
        } else {
            // Legacy/BungeeCord mode: hackOnlineMode = false, no secret needed
            writeOrPatchFabricProxyConfig(configFile, hackOnlineMode = false, secret = "")
            logger.info("FabricProxy-Lite configured for legacy (BungeeCord) forwarding")
        }
    }

    private fun writeOrPatchFabricProxyConfig(configFile: Path, hackOnlineMode: Boolean, secret: String) {
        if (configFile.exists()) {
            val patched = configFile.readLines().map { line ->
                when {
                    line.trimStart().startsWith("hackOnlineMode") -> "hackOnlineMode = $hackOnlineMode"
                    line.trimStart().startsWith("secret") -> "secret = \"$secret\""
                    else -> line
                }
            }
            configFile.writeLines(patched)
        } else {
            configFile.writeText(buildString {
                appendLine("hackOnlineMode = $hackOnlineMode")
                appendLine("hackEarlySend = false")
                appendLine("hackMessageChain = false")
                appendLine("disconnectMessage = \"This server requires you to connect through the proxy.\"")
                appendLine("secret = \"$secret\"")
            })
        }
    }

    /**
     * Configures proxy-compatible-forge for proxy forwarding on Forge/NeoForge servers.
     * Supports both modern (Velocity secret) and legacy (BungeeCord) modes.
     */
    fun patchForgeProxy(workDir: Path, velocityTemplateDir: Path, forwardingMode: String) {
        val configDir = workDir.resolve("config")
        if (!configDir.exists()) configDir.createDirectories()

        val configFile = configDir.resolve("proxy-compatible-forge-server.toml")

        if (forwardingMode == "modern") {
            val secret = readForwardingSecret(velocityTemplateDir)
            if (secret.isEmpty()) {
                logger.warn("No forwarding.secret found — proxy-compatible-forge modern forwarding will not work")
                return
            }
            configFile.writeText(buildString {
                appendLine("[general]")
                appendLine("\tforwardingMode = \"MODERN\"")
                appendLine("\tsecret = \"$secret\"")
            })
            logger.info("proxy-compatible-forge configured for modern forwarding")
        } else {
            configFile.writeText(buildString {
                appendLine("[general]")
                appendLine("\tforwardingMode = \"LEGACY\"")
                appendLine("\tsecret = \"\"")
            })
            logger.info("proxy-compatible-forge configured for legacy (BungeeCord) forwarding")
        }
    }

    private fun readForwardingSecret(velocityTemplateDir: Path): String {
        val secretFile = velocityTemplateDir.resolve("forwarding.secret")
        return if (secretFile.exists()) secretFile.readText().trim() else ""
    }
}
