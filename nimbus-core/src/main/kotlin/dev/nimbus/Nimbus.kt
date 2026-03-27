package dev.nimbus

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
    val config = if (configPath.exists()) {
        ConfigLoader.loadNimbusConfig(configPath)
    } else {
        logger.warn("nimbus.toml not found, using defaults")
        NimbusConfig()
    }

    // Ensure directories exist
    val templatesDir = baseDir.resolve(config.paths.templates)
    val runningDir = baseDir.resolve(config.paths.running)
    val logsDir = baseDir.resolve(config.paths.logs)

    listOf(templatesDir, runningDir, logsDir, groupsDir).forEach { dir ->
        if (!dir.exists()) dir.createDirectories()
    }

    // Initialize components
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val eventBus = EventBus(scope)
    val registry = ServiceRegistry()
    val portAllocator = PortAllocator()
    val templateManager = TemplateManager()
    val groupManager = GroupManager()

    // Load group configs
    val groupConfigs = ConfigLoader.loadGroupConfigs(groupsDir)
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

    // Register shutdown hook
    Runtime.getRuntime().addShutdownHook(Thread {
        runBlocking {
            logger.info("Shutdown signal received, stopping all services...")
            scalingJob.cancel()
            serviceManager.stopAll()
            scope.cancel()
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
        softwareResolver = softwareResolver
    )
    console.init()

    // Start minimum instances for all groups (auto-downloads JARs if missing)
    serviceManager.startMinimumInstances()

    // Start interactive console REPL (blocks until shutdown)
    console.start()

    // Console exited (shutdown command), clean up
    scalingJob.cancel()
    serviceManager.stopAll()
    scope.cancel()
    logger.info("Nimbus stopped.")
}
