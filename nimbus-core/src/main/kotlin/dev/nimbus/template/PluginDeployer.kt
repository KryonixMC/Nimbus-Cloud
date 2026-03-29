package dev.nimbus.template

import dev.nimbus.config.NimbusConfig
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class PluginDeployer(private val baseDir: Path) {

    private val logger = LoggerFactory.getLogger(PluginDeployer::class.java)

    fun deployAll(
        templatesDir: Path,
        staticDir: Path,
        globalTemplateDir: Path,
        globalProxyTemplateDir: Path,
        config: NimbusConfig
    ) {
        // Deploy Nimbus Bridge plugin to global_proxy (always overwrite for updates)
        deployHubPlugin(globalProxyTemplateDir)

        // Deploy Nimbus SDK plugin to global (all backend servers: Paper, Purpur, etc.)
        deploySdkPlugin(globalTemplateDir)

        // Auto-update Nimbus plugins where the user has placed them (templates + static services)
        autoUpdateNimbusPlugins(templatesDir, staticDir)

        // Deploy bridge config so the plugin can connect to the API
        deployBridgeConfig(globalProxyTemplateDir, config)

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

    private fun deploySdkPlugin(globalDir: Path) {
        deployPlugin(globalDir, "nimbus-sdk.jar", "plugins/nimbus-sdk.jar")
    }

    /**
     * Scans template directories and static service directories for Nimbus plugins
     * (nimbus-signs.jar, nimbus-npc.jar, etc.) and replaces them with the latest
     * version from embedded resources. Only updates where the user has already
     * placed the plugin -- does NOT deploy to new locations.
     */
    private fun autoUpdateNimbusPlugins(templatesDir: Path, staticDir: Path) {
        val nimbusPlugins = listOf("nimbus-signs.jar")
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
     * Extracts optional plugins (SDK, Signs) from the embedded resources
     * into the plugins/ directory at the Nimbus root. Users can then copy
     * these JARs to their server's plugins/ folder as needed.
     */
    private fun extractOptionalPlugins(pluginsDir: Path) {
        if (!pluginsDir.exists()) pluginsDir.createDirectories()

        val optionalPlugins = mapOf(
            "nimbus-sdk.jar" to "plugins/nimbus-sdk.jar",
            "nimbus-signs.jar" to "plugins/nimbus-signs.jar"
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
