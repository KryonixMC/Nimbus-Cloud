package dev.nimbus

import dev.nimbus.api.NimbusApi
import dev.nimbus.config.ConfigLoader
import dev.nimbus.config.NimbusConfig
import dev.nimbus.console.NimbusConsole
import dev.nimbus.event.EventBus
import dev.nimbus.group.GroupManager
import dev.nimbus.scaling.ScalingEngine
import dev.nimbus.service.PortAllocator
import dev.nimbus.service.ServiceManager
import dev.nimbus.service.ServiceRegistry
import dev.nimbus.setup.SetupWizard
import dev.nimbus.template.SoftwareResolver
import dev.nimbus.template.TemplateManager
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path as NioPath
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

private val logger = LoggerFactory.getLogger("Nimbus")

fun main() = runBlocking {
    val baseDir = Path("").toAbsolutePath()
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
    val config = try {
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

    // Ensure directories exist
    val templatesDir = baseDir.resolve(config.paths.templates)
    val servicesDir = baseDir.resolve(config.paths.services)
    val staticDir = servicesDir.resolve("static")
    val tempDir = servicesDir.resolve("temp")
    val logsDir = baseDir.resolve(config.paths.logs)

    val globalTemplateDir = templatesDir.resolve("global")
    val globalProxyTemplateDir = templatesDir.resolve("global_proxy")

    listOf(
        templatesDir, staticDir, tempDir, logsDir, groupsDir,
        globalTemplateDir, globalTemplateDir.resolve("plugins"),
        globalProxyTemplateDir, globalProxyTemplateDir.resolve("plugins")
    ).forEach { dir ->
        if (!dir.exists()) dir.createDirectories()
    }

    // Migrate old .hub-deployed marker to .nimbus-plugins
    migratePluginTracking(globalProxyTemplateDir)

    // Deploy Nimbus Hub plugin to global_proxy (always overwrite for updates)
    deployHubPlugin(globalProxyTemplateDir)

    // Deploy bridge config so the plugin can connect to the API
    deployBridgeConfig(globalProxyTemplateDir, config)

    // Initialize components
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val eventBus = EventBus(scope)
    val registry = ServiceRegistry()
    val portAllocator = PortAllocator()
    val templateManager = TemplateManager()
    val groupManager = GroupManager()

    // Load group configs
    val groupConfigs = ConfigLoader.loadGroupConfigs(groupsDir)
    if (groupConfigs.isEmpty()) {
        logger.warn("No valid group configs found in {}/ — nothing to start", groupsDir)
    }
    groupManager.loadGroups(groupConfigs)

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
        api = api
    )
    console.init()

    // Start REST API after console init so the event is visible
    api.start()

    // Check for compatibility issues and print warnings to console
    console.printCompatWarnings(serviceManager.checkCompatibility())

    // Start minimum instances for all groups (auto-downloads JARs if missing)
    serviceManager.startMinimumInstances()

    // Start interactive console REPL (blocks until shutdown)
    console.start()

    // Console exited (shutdown command), clean up
    scalingJob.cancel()
    api.stop()
    serviceManager.stopAll()
    scope.cancel()
    logger.info("Nimbus stopped.")
}

/**
 * Migrates old `.hub-deployed` marker to the new `.nimbus-plugins` tracking file.
 */
private fun migratePluginTracking(globalProxyDir: NioPath) {
    val oldMarker = globalProxyDir.resolve(".hub-deployed")
    if (!oldMarker.exists()) return

    val trackingFile = globalProxyDir.resolve(".nimbus-plugins")
    if (!trackingFile.exists()) {
        Files.write(trackingFile, listOf("nimbus-cloud.jar"))
        logger.debug("Migrated .hub-deployed to .nimbus-plugins")
    }

    // Rename old nimbus-hub.jar → nimbus-cloud.jar
    val oldJar = globalProxyDir.resolve("plugins/nimbus-hub.jar")
    if (oldJar.exists()) {
        Files.delete(oldJar)
        logger.debug("Removed old nimbus-hub.jar (replaced by nimbus-cloud.jar)")
    }

    // Clean up old plugin data directory
    val oldDataDir = globalProxyDir.resolve("plugins/nimbus-hub")
    val newDataDir = globalProxyDir.resolve("plugins/nimbus-cloud")
    if (oldDataDir.exists() && !newDataDir.exists()) {
        Files.move(oldDataDir, newDataDir)
        logger.debug("Migrated plugin data directory nimbus-hub → nimbus-cloud")
    }

    Files.delete(oldMarker)
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
    deployPlugin(globalProxyDir, "nimbus-cloud.jar", "plugins/nimbus-cloud.jar")
}

private fun deployBridgeConfig(globalProxyDir: NioPath, config: NimbusConfig) {
    if (!config.api.enabled) {
        logger.debug("API disabled, skipping bridge config deploy")
        return
    }

    // Write bridge.json to the plugin's data directory
    val pluginDataDir = globalProxyDir.resolve("plugins").resolve("nimbus-cloud")
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
