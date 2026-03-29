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
import java.util.concurrent.atomic.AtomicBoolean
import dev.nimbus.service.PortAllocator
import dev.nimbus.service.ServiceManager
import dev.nimbus.service.ServiceRegistry
import dev.nimbus.setup.SetupWizard
import dev.nimbus.template.PluginDeployer
import dev.nimbus.template.SoftwareResolver
import dev.nimbus.template.TemplateManager
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
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

            Runtime.getRuntime().addShutdownHook(Thread {
                if (process.isAlive) {
                    process.destroy()
                    process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                    if (process.isAlive) process.destroyForcibly()
                }
            })
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

    // Shared SoftwareResolver instance (used by SetupWizard, NimbusConsole, VelocityUpdater)
    val softwareResolver = SoftwareResolver()

    // Run setup wizard if this is a fresh install
    val setupWizard = SetupWizard(baseDir, softwareResolver)
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

    // Deploy and update all Nimbus plugins
    PluginDeployer(baseDir).deployAll(templatesDir, staticDir, globalTemplateDir, globalProxyTemplateDir, config)

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
        scope = scope,
        softwareResolver = softwareResolver
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
    val shutdownStarted = AtomicBoolean(false)
    Runtime.getRuntime().addShutdownHook(Thread {
        if (!shutdownStarted.compareAndSet(false, true)) return@Thread
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
    if (shutdownStarted.compareAndSet(false, true)) {
        updaterJob.cancel()
        scalingJob.cancel()
        api.stop()
        serviceManager.stopAll()
        scope.cancel()
        logger.info("Nimbus stopped.")
    }
}

private fun generateApiToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}
