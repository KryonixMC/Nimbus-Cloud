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

    // Deploy Nimbus Hub plugin to global_proxy (always overwrite for updates)
    deployHubPlugin(globalProxyTemplateDir)

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

private fun deployHubPlugin(globalProxyDir: NioPath) {
    val pluginsDir = globalProxyDir.resolve("plugins")
    val targetFile = pluginsDir.resolve("nimbus-hub.jar")

    // If the file was manually removed, don't re-deploy
    // Only deploy on fresh install or update existing
    val markerFile = globalProxyDir.resolve(".hub-deployed")
    if (markerFile.exists() && !targetFile.exists()) {
        logger.debug("Nimbus Hub plugin was removed by user, skipping deploy")
        return
    }

    val resource = object {}.javaClass.classLoader.getResourceAsStream("plugins/nimbus-hub.jar")
    if (resource == null) {
        logger.debug("Nimbus Hub plugin not found in resources, skipping")
        return
    }

    if (!pluginsDir.exists()) pluginsDir.createDirectories()
    resource.use { input ->
        Files.copy(input, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    // Mark that we've deployed at least once
    if (!markerFile.exists()) Files.createFile(markerFile)
    logger.info("Deployed Nimbus Hub plugin to {}", targetFile)
}
