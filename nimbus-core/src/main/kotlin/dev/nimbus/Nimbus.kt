package dev.nimbus

import dev.nimbus.api.NimbusApi
import dev.nimbus.config.ConfigLoader
import dev.nimbus.config.NimbusConfig
import dev.nimbus.console.NimbusConsole
import dev.nimbus.event.EventBus
import dev.nimbus.group.GroupManager
import dev.nimbus.permissions.PermissionManager
import dev.nimbus.scaling.ScalingEngine
import dev.nimbus.scaling.VelocityUpdater
import java.security.SecureRandom
import dev.nimbus.service.PortAllocator
import dev.nimbus.service.ServiceManager
import dev.nimbus.service.ServiceRegistry
import dev.nimbus.setup.SetupWizard
import dev.nimbus.template.SoftwareResolver
import dev.nimbus.template.TemplateManager
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path as NioPath
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

private val logger by lazy { LoggerFactory.getLogger("Nimbus") }

fun main() {
    // Relaunch with --enable-native-access=ALL-UNNAMED if not already set (suppresses JLine warnings on Java 21+)
    if (needsNativeAccessRelaunch()) {
        val javaExe = ProcessHandle.current().info().command().orElse("java")
        val jarPath = java.nio.file.Paths.get(Nimbus::class.java.protectionDomain.codeSource.location.toURI()).toString()
        val currentArgs = ManagementFactory.getRuntimeMXBean().inputArguments

        if (jarPath != null) {
            val cmd = mutableListOf(javaExe)
            cmd.addAll(currentArgs)
            cmd.add("--enable-native-access=ALL-UNNAMED")
            cmd.addAll(listOf("-jar", jarPath))

            val process = ProcessBuilder(cmd)
                .inheritIO()
                .start()

            Runtime.getRuntime().addShutdownHook(Thread { process.destroyForcibly() })
            System.exit(process.waitFor())
        }
    }

    nimbusMain()
}

private fun needsNativeAccessRelaunch(): Boolean {
    val runtimeVersion = Runtime.version().feature()
    if (runtimeVersion < 21) return false
    return ManagementFactory.getRuntimeMXBean().inputArguments
        .none { it.startsWith("--enable-native-access") }
}

/** Marker class for locating the JAR path */
private class Nimbus

fun nimbusMain() = runBlocking {
    val baseDir = Path("").toAbsolutePath()

    // Rotate latest.log → YYYY-MM-DD-N.log.gz (Minecraft-style)
    LogRotation.rotate(baseDir.resolve("logs"))
    val groupsDir = baseDir.resolve("groups")

    // Run setup wizard if this is a fresh install
    val setupWizard = SetupWizard(baseDir, SoftwareResolver())
    if (setupWizard.isSetupNeeded()) {
        val shouldStart = setupWizard.run()
        if (!shouldStart) {
            logger.info("Setup cancelled.")
            return@runBlocking
        }
    }

    // Load main config
    val configPath = baseDir.resolve("nimbus.toml")
    var config = try {
        if (configPath.exists()) {
            ConfigLoader.loadNimbusConfig(configPath)
        } else {
            logger.warn("nimbus.toml not found, using defaults")
            NimbusConfig()
        }
    } catch (e: Exception) {
        logger.error("Fatal: Failed to load nimbus.toml — {}", e.message)
        logger.error("Fix the config file and restart Nimbus.")
        return@runBlocking
    }

    // Auto-generate API token if missing (existing installs without [api] section)
    if (config.api.enabled && config.api.token.isBlank() && configPath.exists()) {
        val token = generateApiToken()
        val tomlContent = configPath.toFile().readText()
        if (tomlContent.contains("[api]")) {
            val updated = tomlContent.replace(
                Regex("""token\s*=\s*".*""""),
                "token = \"$token\""
            )
            configPath.toFile().writeText(updated)
        } else {
            configPath.toFile().appendText("\n[api]\nenabled = true\nbind = \"127.0.0.1\"\nport = 8080\ntoken = \"$token\"\n")
        }
        logger.info("Generated API token (saved to nimbus.toml)")
        config = ConfigLoader.loadNimbusConfig(configPath)
    }

    // Ensure directories exist
    val templatesDir = baseDir.resolve(config.paths.templates)
    val servicesDir = baseDir.resolve(config.paths.services)
    val staticDir = servicesDir.resolve("static")
    val tempDir = servicesDir.resolve("temp")
    val logsDir = baseDir.resolve(config.paths.logs)

    val globalTemplateDir = templatesDir.resolve("global")
    val globalProxyTemplateDir = templatesDir.resolve("global_proxy")

    val permissionsDir = baseDir.resolve("permissions")
    val displaysDir = baseDir.resolve("displays")
    val proxyDir = baseDir.resolve("proxy")

    listOf(
        templatesDir, staticDir, tempDir, logsDir, groupsDir, permissionsDir, displaysDir, proxyDir,
        globalTemplateDir, globalTemplateDir.resolve("plugins"),
        globalProxyTemplateDir, globalProxyTemplateDir.resolve("plugins")
    ).forEach { dir ->
        if (!dir.exists()) dir.createDirectories()
    }

    // Clean temp directory from previous session
    if (tempDir.exists()) {
        tempDir.toFile().deleteRecursively()
        tempDir.createDirectories()
        logger.info("Cleared temp directory: {}", tempDir)
    }

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

    // Initialize components
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val eventBus = EventBus(scope)
    val registry = ServiceRegistry()
    val portAllocator = PortAllocator()
    val templateManager = TemplateManager()
    val groupManager = GroupManager()
    val permissionManager = PermissionManager(permissionsDir)
    permissionManager.init()

    val displayManager = dev.nimbus.display.DisplayManager(displaysDir)
    displayManager.init()

    val proxySyncManager = dev.nimbus.proxy.ProxySyncManager(proxyDir)
    proxySyncManager.init()

    // Load group configs
    val groupConfigs = ConfigLoader.loadGroupConfigs(groupsDir)
    if (groupConfigs.isEmpty()) {
        logger.warn("No valid group configs found in {}/ — nothing to start", groupsDir)
    }
    groupManager.loadGroups(groupConfigs)

    // Auto-generate display configs for groups (signs + NPCs)
    displayManager.ensureDisplays(groupConfigs)

    logger.info("Found ${groupConfigs.size} groups: ${groupConfigs.joinToString { it.group.name }}")

    // Create service manager
    val serviceManager = ServiceManager(
        config = config,
        registry = registry,
        portAllocator = portAllocator,
        templateManager = templateManager,
        groupManager = groupManager,
        eventBus = eventBus,
        scope = scope
    )

    // Start scaling engine
    val scalingEngine = ScalingEngine(
        registry = registry,
        serviceManager = serviceManager,
        groupManager = groupManager,
        eventBus = eventBus,
        scope = scope,
        checkIntervalMs = config.controller.heartbeatInterval
    )
    val scalingJob = scalingEngine.start()
    logger.info("Scaling engine started (interval: {}ms)", config.controller.heartbeatInterval)

    // Create REST API (started after console.init() so events are visible)
    val api = NimbusApi(
        config = config,
        registry = registry,
        serviceManager = serviceManager,
        groupManager = groupManager,
        permissionManager = permissionManager,
        displayManager = displayManager,
        proxySyncManager = proxySyncManager,
        eventBus = eventBus,
        scope = scope,
        baseDir = baseDir,
        groupsDir = groupsDir,
        configPath = configPath
    )

    // Register shutdown hook for external signals (SIGTERM, SIGINT, terminal close)
    Runtime.getRuntime().addShutdownHook(Thread {
        runBlocking {
            logger.info("Shutdown signal received, stopping all services...")
            scalingJob.cancel()
            api.stop()
            try {
                serviceManager.stopAll()
            } catch (e: Exception) {
                logger.error("Error during shutdown: {}", e.message)
            }
            scope.cancel()
            logger.info("Nimbus stopped.")
        }
    })

    // Initialize console (banner + event listener) before starting services
    val softwareResolver = SoftwareResolver()
    val console = NimbusConsole(
        config = config,
        serviceManager = serviceManager,
        registry = registry,
        groupManager = groupManager,
        eventBus = eventBus,
        scope = scope,
        groupsDir = groupsDir,
        softwareResolver = softwareResolver,
        api = api,
        permissionManager = permissionManager,
        proxySyncManager = proxySyncManager
    )
    console.init()

    // Start REST API after console init so the event is visible
    api.start()

    // Check for compatibility issues and print warnings to console
    console.printCompatWarnings(serviceManager.checkCompatibility())

    // Start Velocity auto-updater (checks every 6 hours)
    val velocityUpdater = VelocityUpdater(
        groupManager = groupManager,
        registry = registry,
        softwareResolver = softwareResolver,
        eventBus = eventBus,
        scope = scope,
        templatesDir = templatesDir,
        groupsDir = groupsDir
    )
    val updaterJob = velocityUpdater.start()

    // Start minimum instances for all groups (auto-downloads JARs if missing)
    serviceManager.startMinimumInstances()

    // Start interactive console REPL (blocks until shutdown)
    console.start()

    // Console exited (shutdown command), clean up
    updaterJob.cancel()
    scalingJob.cancel()
    api.stop()
    serviceManager.stopAll()
    scope.cancel()
    logger.info("Nimbus stopped.")
}

/**
 * Tracks deployed plugins in `.nimbus-plugins`.
 * If a plugin was deployed before but the JAR is missing → user removed it → skip.
 * If a plugin was never deployed → deploy and track.
 * If a plugin exists → overwrite (update).
 */
private fun deployPlugin(globalProxyDir: NioPath, fileName: String, resourcePath: String) {
    val pluginsDir = globalProxyDir.resolve("plugins")
    val targetFile = pluginsDir.resolve(fileName)
    val trackingFile = globalProxyDir.resolve(".nimbus-plugins")

    // Read tracking list
    val tracked = if (trackingFile.exists()) {
        Files.readAllLines(trackingFile).map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
    } else {
        mutableSetOf()
    }

    // If previously deployed but JAR was manually removed → skip
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

private fun deployHubPlugin(globalProxyDir: NioPath) {
    deployPlugin(globalProxyDir, "nimbus-bridge.jar", "plugins/nimbus-bridge.jar")
}

private fun deploySdkPlugin(globalDir: NioPath) {
    deployPlugin(globalDir, "nimbus-sdk.jar", "plugins/nimbus-sdk.jar")
}

/**
 * Scans template directories and static service directories for Nimbus plugins
 * (nimbus-signs.jar, nimbus-npc.jar, etc.) and replaces them with the latest
 * version from embedded resources. Only updates where the user has already
 * placed the plugin — does NOT deploy to new locations.
 */
private fun autoUpdateNimbusPlugins(templatesDir: NioPath, staticDir: NioPath) {
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

private fun updatePluginsInDir(pluginsDir: NioPath, pluginNames: List<String>): Int {
    if (!pluginsDir.exists()) return 0
    var count = 0

    for (fileName in pluginNames) {
        val targetFile = pluginsDir.resolve(fileName)
        if (!targetFile.exists()) continue // User hasn't placed it here — skip

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

private fun deployBridgeConfig(globalProxyDir: NioPath, config: NimbusConfig) {
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
private fun extractOptionalPlugins(pluginsDir: NioPath) {
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

private fun generateApiToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}
