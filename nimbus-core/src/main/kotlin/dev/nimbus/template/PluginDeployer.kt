package dev.nimbus.template

import dev.nimbus.config.NimbusConfig
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class PluginDeployer(private val baseDir: Path) {

    private val logger = LoggerFactory.getLogger(PluginDeployer::class.java)

    fun deployAll(
        templatesDir: Path,
        staticDir: Path,
        globalTemplateDir: Path,
        globalProxyTemplateDir: Path,
        config: NimbusConfig,
        softwareResolver: SoftwareResolver? = null
    ) {
        // Deploy Nimbus Bridge plugin to global_proxy (always overwrite for updates)
        deployHubPlugin(globalProxyTemplateDir)

        // Deploy Nimbus SDK plugin to global (all backend servers: Paper, Purpur, etc.)
        deploySdkPlugin(globalTemplateDir, config.permissions.deployPlugin)

        // Deploy ProtocolLib to global (required by SDK for fake player spawning)
        deployPlugin(globalTemplateDir, "ProtocolLib.jar", "plugins/ProtocolLib.jar")

        // Deploy FancyNpcs to global (required by Display plugin for player NPCs)
        deployPlugin(globalTemplateDir, "FancyNpcs.jar", "plugins/FancyNpcs.jar")

        // Auto-update Nimbus plugins where the user has placed them (templates + static services)
        autoUpdateNimbusPlugins(templatesDir, staticDir)

        // Deploy server icon to global_proxy (Velocity picks it up automatically)
        deployServerIcon(globalProxyTemplateDir)

        // Deploy bridge config so the plugin can connect to the API
        deployBridgeConfig(globalProxyTemplateDir, config)

        // Deploy Bedrock plugins (Geyser + Floodgate) if enabled
        if (config.bedrock.enabled && softwareResolver != null) {
            deployBedrockPlugins(templatesDir, globalTemplateDir, globalProxyTemplateDir, softwareResolver)
        }

        // Extract optional plugins to plugins/ for easy installation on servers
        extractOptionalPlugins(baseDir.resolve("plugins"))
    }

    /**
     * Tracks deployed plugins in `.nimbus-plugins`.
     * If a plugin was deployed before but the JAR is missing -> user removed it -> skip.
     * If a plugin was never deployed -> deploy and track.
     * If a plugin exists -> overwrite (update).
     */
    private fun deployPlugin(globalProxyDir: Path, fileName: String, resourcePath: String) {
        val pluginsDir = globalProxyDir.resolve("plugins")
        val targetFile = pluginsDir.resolve(fileName)
        val trackingFile = globalProxyDir.resolve(".nimbus-plugins")

        // Read tracking list
        val tracked = if (trackingFile.exists()) {
            Files.readAllLines(trackingFile).map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        } else {
            mutableSetOf()
        }

        // If previously deployed but JAR was manually removed -> skip
        if (fileName in tracked && !targetFile.exists()) {
            logger.debug("{} was removed by user, skipping deploy", fileName)
            return
        }

        // Load from classpath resources
        val resource = object {}.javaClass.classLoader.getResourceAsStream(resourcePath)
        if (resource == null) {
            logger.debug("{} not found in resources, skipping", fileName)
            return
        }

        if (!pluginsDir.exists()) pluginsDir.createDirectories()
        resource.use { input ->
            Files.copy(input, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }

        // Track the plugin
        if (fileName !in tracked) {
            tracked.add(fileName)
            Files.write(trackingFile, tracked)
        }

        logger.info("Deployed {} to {}", fileName, targetFile)
    }

    private fun deployHubPlugin(globalProxyDir: Path) {
        deployPlugin(globalProxyDir, "nimbus-bridge.jar", "plugins/nimbus-bridge.jar")
    }

    private fun deploySdkPlugin(globalDir: Path, deployPerms: Boolean = true) {
        deployPlugin(globalDir, "nimbus-sdk.jar", "plugins/nimbus-sdk.jar")
        if (deployPerms) {
            deployPlugin(globalDir, "nimbus-perms.jar", "plugins/nimbus-perms.jar")
        }
    }

    /**
     * Scans template directories and static service directories for Nimbus plugins
     * (nimbus-display.jar, etc.) and replaces them with the latest
     * version from embedded resources. Only updates where the user has already
     * placed the plugin -- does NOT deploy to new locations.
     */
    private fun autoUpdateNimbusPlugins(templatesDir: Path, staticDir: Path) {
        val nimbusPlugins = listOf("nimbus-display.jar", "nimbus-perms.jar")
        var updated = 0

        // Scan all template directories (except global/global_proxy which are handled separately)
        if (templatesDir.exists()) {
            Files.list(templatesDir)
                .filter { Files.isDirectory(it) }
                .filter { it.fileName.toString() !in listOf("global", "global_proxy") }
                .forEach { templateDir ->
                    updated += updatePluginsInDir(templateDir.resolve("plugins"), nimbusPlugins)
                }
        }

        // Scan all static service directories
        if (staticDir.exists()) {
            Files.list(staticDir)
                .filter { Files.isDirectory(it) }
                .forEach { serviceDir ->
                    updated += updatePluginsInDir(serviceDir.resolve("plugins"), nimbusPlugins)
                }
        }

        if (updated > 0) {
            logger.info("Auto-updated {} Nimbus plugin(s) in templates/static services", updated)
        }
    }

    private fun updatePluginsInDir(pluginsDir: Path, pluginNames: List<String>): Int {
        if (!pluginsDir.exists()) return 0
        var count = 0

        for (fileName in pluginNames) {
            val targetFile = pluginsDir.resolve(fileName)
            if (!targetFile.exists()) continue // User hasn't placed it here -- skip

            val resource = object {}.javaClass.classLoader.getResourceAsStream("plugins/$fileName")
            if (resource == null) continue

            resource.use { input ->
                Files.copy(input, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            logger.debug("Updated {} in {}", fileName, pluginsDir)
            count++
        }
        return count
    }

    private fun deployServerIcon(globalProxyDir: Path) {
        val targetFile = globalProxyDir.resolve("server-icon.png")
        if (targetFile.exists()) return

        val resource = object {}.javaClass.classLoader.getResourceAsStream("server-icon.png")
        if (resource == null) {
            logger.debug("server-icon.png not found in resources, skipping")
            return
        }

        resource.use { input ->
            Files.copy(input, targetFile)
        }
        logger.info("Deployed default server-icon.png to {}", globalProxyDir)
    }

    private fun deployBridgeConfig(globalProxyDir: Path, config: NimbusConfig) {
        if (!config.api.enabled) {
            logger.debug("API disabled, skipping bridge config deploy")
            return
        }

        // Write bridge.json to the plugin's data directory
        val pluginDataDir = globalProxyDir.resolve("plugins").resolve("nimbus-bridge")
        if (!pluginDataDir.exists()) pluginDataDir.createDirectories()

        val bridgeConfig = pluginDataDir.resolve("bridge.json")
        val apiUrl = "http://${config.api.bind}:${config.api.port}"

        val json = """
            {
              "api_url": "$apiUrl",
              "token": "${config.api.token}"
            }
        """.trimIndent()

        Files.writeString(bridgeConfig, json)
        logger.info("Deployed bridge config (API: {})", apiUrl)
    }

    /**
     * Downloads and deploys Geyser + Floodgate for Bedrock Edition support.
     * - Geyser (velocity) → global_proxy/plugins/ (proxy only)
     * - Floodgate (velocity) → global_proxy/plugins/ (proxy)
     * - Floodgate (paper) → global/plugins/ (all backends)
     * - key.pem distributed from proxy template to global templates
     */
    private fun deployBedrockPlugins(
        templatesDir: Path,
        globalTemplateDir: Path,
        globalProxyTemplateDir: Path,
        softwareResolver: SoftwareResolver
    ) {
        runBlocking {
            // Download Geyser to proxy template
            softwareResolver.ensureGeyserPlugin(globalProxyTemplateDir)

            // Download Floodgate to proxy and backend templates
            softwareResolver.ensureFloodgatePlugin(globalProxyTemplateDir, "velocity")
            softwareResolver.ensureFloodgatePlugin(globalTemplateDir, "spigot")
        }

        // Distribute Floodgate key.pem from proxy template to global templates
        distributeFloodgateKey(templatesDir, globalTemplateDir, globalProxyTemplateDir)

        logger.info("Bedrock support deployed (Geyser + Floodgate)")
    }

    /**
     * Copies Floodgate's key.pem from the proxy template to global templates
     * so all proxy and backend instances share the same authentication key.
     */
    private fun distributeFloodgateKey(templatesDir: Path, globalTemplateDir: Path, globalProxyTemplateDir: Path) {
        // Floodgate generates key.pem on first proxy run in plugins/floodgate/
        val proxyKeyFile = templatesDir.resolve("proxy").resolve("plugins").resolve("floodgate").resolve("key.pem")
        if (!proxyKeyFile.exists()) {
            logger.debug("Floodgate key.pem not found yet — will be generated on first proxy start")
            return
        }

        // Copy to global (backends) and global_proxy (proxies)
        for (globalDir in listOf(globalTemplateDir, globalProxyTemplateDir)) {
            val targetDir = globalDir.resolve("plugins").resolve("floodgate")
            if (!targetDir.exists()) targetDir.createDirectories()
            val targetFile = targetDir.resolve("key.pem")
            Files.copy(proxyKeyFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
        }
        logger.info("Distributed Floodgate key.pem to global templates")
    }

    /**
     * Extracts optional plugins (SDK, Display) from the embedded resources
     * into the plugins/ directory at the Nimbus root. Users can then copy
     * these JARs to their server's plugins/ folder as needed.
     */
    private fun extractOptionalPlugins(pluginsDir: Path) {
        if (!pluginsDir.exists()) pluginsDir.createDirectories()

        val optionalPlugins = mapOf(
            "nimbus-sdk.jar" to "plugins/nimbus-sdk.jar",
            "nimbus-display.jar" to "plugins/nimbus-display.jar",
            "nimbus-perms.jar" to "plugins/nimbus-perms.jar"
        )

        for ((fileName, resourcePath) in optionalPlugins) {
            val targetFile = pluginsDir.resolve(fileName)
            val resource = object {}.javaClass.classLoader.getResourceAsStream(resourcePath)
            if (resource == null) continue

            resource.use { input ->
                Files.copy(input, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            logger.debug("Extracted {} to {}", fileName, pluginsDir)
        }

        logger.info("Optional plugins available in {}/", pluginsDir)
    }
}
