package dev.kryonix.nimbus

import dev.kryonix.nimbus.api.NimbusApi
import dev.kryonix.nimbus.cluster.ClusterWebSocketHandler
import dev.kryonix.nimbus.cluster.NodeManager
import dev.kryonix.nimbus.config.ConfigLoader
import dev.kryonix.nimbus.config.NimbusConfig
import dev.kryonix.nimbus.console.NimbusConsole
import dev.kryonix.nimbus.database.DatabaseManager
import dev.kryonix.nimbus.database.MetricsCollector
import dev.kryonix.nimbus.event.EventBus
import dev.kryonix.nimbus.event.NimbusEvent
import dev.kryonix.nimbus.group.GroupManager
import dev.kryonix.nimbus.loadbalancer.TcpLoadBalancer
import dev.kryonix.nimbus.module.ModuleContextImpl
import dev.kryonix.nimbus.module.ModuleManager
import dev.kryonix.nimbus.protocol.ClusterMessage
import dev.kryonix.nimbus.scaling.ScalingEngine
import dev.kryonix.nimbus.scaling.VelocityUpdater
import dev.kryonix.nimbus.stress.StressTestManager
import dev.kryonix.nimbus.update.UpdateChecker
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import dev.kryonix.nimbus.service.PortAllocator
import dev.kryonix.nimbus.service.ServiceManager
import dev.kryonix.nimbus.service.ServiceRegistry
import dev.kryonix.nimbus.setup.SetupWizard
import dev.kryonix.nimbus.template.PluginDeployer
import dev.kryonix.nimbus.template.SoftwareResolver
import dev.kryonix.nimbus.template.TemplateManager
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
    val configDir = baseDir.resolve("config")
    val groupsDir = configDir.resolve("groups")

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
    val configPath = configDir.resolve("nimbus.toml")
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
        // Restrict permissions: nimbus.toml contains API token & database credentials
        try {
            Files.setPosixFilePermissions(configPath, PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
            // Windows doesn't support POSIX permissions
        }
        logger.info("Generated API token (saved to nimbus.toml)")
        config = ConfigLoader.loadNimbusConfig(configPath)
    }

    // Check for Nimbus updates
    val updateChecker = UpdateChecker(baseDir)
    try {
        val updated = updateChecker.checkAndApply()
        if (updated) {
            logger.info("Nimbus JAR updated — restart to apply the new version.")
        }
    } catch (e: Exception) {
        logger.debug("Update check failed: {}", e.message)
    } finally {
        updateChecker.close()
    }

    // Ensure directories exist
    val templatesDir = baseDir.resolve(config.paths.templates)
    val servicesDir = baseDir.resolve(config.paths.services)
    val staticDir = servicesDir.resolve("static")
    val tempDir = servicesDir.resolve("temp")
    val logsDir = baseDir.resolve(config.paths.logs)

    val globalTemplateDir = templatesDir.resolve("global")
    val globalProxyTemplateDir = templatesDir.resolve("global_proxy")

    val modulesDir = configDir.resolve("modules")
    val displaysDir = modulesDir.resolve("display")
    val proxyDir = modulesDir.resolve("syncproxy")

    listOf(
        templatesDir, staticDir, tempDir, logsDir, configDir, groupsDir, modulesDir, displaysDir, proxyDir,
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
    PluginDeployer(baseDir).deployAll(templatesDir, staticDir, globalTemplateDir, globalProxyTemplateDir, config, softwareResolver)

    // Initialize components
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val eventBus = EventBus(scope)

    // Initialize database
    val databaseManager = DatabaseManager(baseDir, config.database)
    databaseManager.init()

    val registry = ServiceRegistry()
    val portAllocator = PortAllocator(
        lbEnabled = config.loadbalancer.enabled,
        bedrockEnabled = config.bedrock.enabled,
        bedrockBasePort = config.bedrock.basePort
    )
    val templateManager = TemplateManager()
    val groupManager = GroupManager()

    // Start metrics collector
    val metricsCollector = MetricsCollector(databaseManager, eventBus)
    val metricsJobs = metricsCollector.start()
    metricsCollector.startRetentionCleanup(scope)

    val proxySyncManager = dev.kryonix.nimbus.proxy.ProxySyncManager(proxyDir)
    proxySyncManager.init()

    // Load group configs (before module init, so modules can access groups)
    val groupConfigs = ConfigLoader.loadGroupConfigs(groupsDir)
    if (groupConfigs.isEmpty()) {
        logger.warn("No valid group configs found in {}/ — nothing to start", groupsDir)
    }
    groupManager.loadGroups(groupConfigs)
    logger.info("Found ${groupConfigs.size} groups: ${groupConfigs.joinToString { it.group.name }}")

    // ── Module system ─────────────────────────────────
    val controllerModulesDir = baseDir.resolve("modules")
    if (!controllerModulesDir.exists()) controllerModulesDir.createDirectories()

    // Console dispatcher is created early so modules can register commands during init
    val dispatcher = dev.kryonix.nimbus.console.CommandDispatcher()
    dispatcher.registry = registry
    dispatcher.groupManager = groupManager

    val moduleContext = ModuleContextImpl(
        eventBus = eventBus,
        databaseManager = databaseManager,
        registry = registry,
        groupManager = groupManager,
        config = config,
        scope = scope,
        baseDir = baseDir,
        templatesDir = templatesDir,
        dispatcher = dispatcher,
        modulesConfigDir = modulesDir
    )
    val moduleManager = ModuleManager(controllerModulesDir, moduleContext, eventBus)
    moduleManager.loadAll()
    moduleManager.enableAll()

    // ── Cluster mode ───────────────────────────────────
    val nodeManager: NodeManager? = if (config.cluster.enabled) {
        NodeManager(config.cluster, registry, eventBus, scope)
    } else null

    val heartbeatJob: Job? = nodeManager?.startHeartbeatLoop()

    // ── Load Balancer ──────────────────────────────────
    val loadBalancer: TcpLoadBalancer? = if (config.loadbalancer.enabled) {
        TcpLoadBalancer(config.loadbalancer, registry, groupManager, eventBus, scope)
    } else null

    val lbJob: Job? = loadBalancer?.start()
    if (loadBalancer != null) {
        logger.info("TCP Load Balancer started on {}:{}", config.loadbalancer.bind, config.loadbalancer.port)
    }

    // Create service manager
    val serviceManager = ServiceManager(
        config = config,
        registry = registry,
        portAllocator = portAllocator,
        templateManager = templateManager,
        groupManager = groupManager,
        eventBus = eventBus,
        scope = scope,
        softwareResolver = softwareResolver,
        nodeManager = nodeManager
    )

    // Start scaling engine
    val scalingEngine = ScalingEngine(
        registry = registry,
        serviceManager = serviceManager,
        groupManager = groupManager,
        eventBus = eventBus,
        scope = scope,
        checkIntervalMs = config.controller.heartbeatInterval,
        globalMaxServices = config.controller.maxServices
    )
    val stressTestManager = StressTestManager(registry, groupManager, eventBus, proxySyncManager, scope)
    scalingEngine.stressTestManager = stressTestManager

    val scalingJob = scalingEngine.start()
    logger.info("Scaling engine started (interval: {}ms)", config.controller.heartbeatInterval)
    if (config.bedrock.enabled) {
        logger.info("Bedrock support enabled (Geyser + Floodgate, base port {})", config.bedrock.basePort)
    }

    // Create cluster WebSocket handler and dedicated server (if cluster enabled)
    val clusterWsHandler: ClusterWebSocketHandler? = if (nodeManager != null) {
        ClusterWebSocketHandler(config.cluster, nodeManager, registry, eventBus)
    } else null

    val clusterServer: dev.kryonix.nimbus.cluster.ClusterServer? = if (clusterWsHandler != null) {
        dev.kryonix.nimbus.cluster.ClusterServer(config.cluster, clusterWsHandler, templatesDir, eventBus, scope)
    } else null

    // Create REST API (started after console.init() so events are visible)
    val api = NimbusApi(
        config = config,
        registry = registry,
        serviceManager = serviceManager,
        groupManager = groupManager,
        proxySyncManager = proxySyncManager,
        eventBus = eventBus,
        scope = scope,
        baseDir = baseDir,
        groupsDir = groupsDir,
        configPath = configPath,
        nodeManager = nodeManager,
        loadBalancer = loadBalancer,
        templatesDir = templatesDir,
        stressTestManager = stressTestManager,
        moduleContext = moduleContext,
        moduleManager = moduleManager
    )

    // Register shutdown hook for external signals (SIGTERM, SIGINT, terminal close)
    val shutdownStarted = AtomicBoolean(false)
    Runtime.getRuntime().addShutdownHook(Thread {
        if (!shutdownStarted.compareAndSet(false, true)) return@Thread
        runBlocking {
            logger.info("Shutdown signal received, stopping all services...")
            lbJob?.cancel()
            heartbeatJob?.cancel()
            clusterServer?.stop()
            // Send ShutdownAgent to all nodes
            if (nodeManager != null) {
                for (node in nodeManager.getAllNodes()) {
                    try {
                        runBlocking { node.send(ClusterMessage.ShutdownAgent()) }
                    } catch (_: Exception) {}
                }
            }
            metricsJobs.forEach { it.cancel() }
            scalingEngine.shutdown()
            scalingJob.cancel()
            moduleManager.disableAll()
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
        proxySyncManager = proxySyncManager,
        nodeManager = nodeManager,
        loadBalancer = loadBalancer,
        configPath = configPath,
        stressTestManager = stressTestManager,
        moduleManager = moduleManager,
        scalingEngine = scalingEngine,
        sharedDispatcher = dispatcher
    )
    console.init()

    // Start REST API after console init so the event is visible
    api.start()

    // Start cluster WebSocket server on dedicated agent_port (if cluster enabled)
    clusterServer?.start()
    if (clusterServer != null) {
        scope.launch {
            eventBus.emit(NimbusEvent.ClusterStarted(config.cluster.bind, config.cluster.agentPort, config.cluster.placementStrategy))
        }
    }
    if (loadBalancer != null) {
        scope.launch {
            eventBus.emit(NimbusEvent.LoadBalancerStarted(config.loadbalancer.bind, config.loadbalancer.port, config.loadbalancer.strategy))
        }
    }

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
        lbJob?.cancel()
        heartbeatJob?.cancel()
        clusterServer?.stop()
        // Send ShutdownAgent to all nodes
        if (nodeManager != null) {
            for (node in nodeManager.getAllNodes()) {
                try {
                    node.send(ClusterMessage.ShutdownAgent())
                } catch (_: Exception) {}
            }
        }
        metricsJobs.forEach { it.cancel() }
        updaterJob.cancel()
        scalingEngine.shutdown()
        scalingJob.cancel()
        moduleManager.disableAll()
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
