package dev.kryonix.nimbus.service

import dev.kryonix.nimbus.cluster.NodeManager
import dev.kryonix.nimbus.cluster.RemoteServiceHandle
import dev.kryonix.nimbus.config.GroupDefinition
import dev.kryonix.nimbus.config.NimbusConfig
import dev.kryonix.nimbus.config.ServerSoftware
import dev.kryonix.nimbus.module.ModuleContextImpl
import dev.kryonix.nimbus.event.EventBus
import dev.kryonix.nimbus.event.NimbusEvent
import dev.kryonix.nimbus.group.GroupManager
import dev.kryonix.nimbus.protocol.ClusterMessage
import dev.kryonix.nimbus.template.PerformanceOptimizer
import dev.kryonix.nimbus.template.SoftwareResolver
import dev.kryonix.nimbus.template.TemplateManager
import dev.kryonix.nimbus.velocity.VelocityConfigGen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ServiceManager(
    private val config: NimbusConfig,
    private val registry: ServiceRegistry,
    private val portAllocator: PortAllocator,
    private val templateManager: TemplateManager,
    private val groupManager: GroupManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val softwareResolver: SoftwareResolver,
    private val nodeManager: NodeManager? = null,
    private val moduleContext: ModuleContextImpl? = null
) {

    private val logger = LoggerFactory.getLogger(ServiceManager::class.java)

    private val processHandles = ConcurrentHashMap<String, ServiceHandle>()
    private val velocityConfigGen = VelocityConfigGen(registry, groupManager)
    private val javaResolver = JavaResolver(config.java.toMap(), Path(config.paths.templates).toAbsolutePath().parent ?: Path("."))
    private val compatibilityChecker = CompatibilityChecker(groupManager, config, javaResolver)
    private val performanceOptimizer = PerformanceOptimizer()

    private val serviceFactory = ServiceFactory(
        config = config,
        registry = registry,
        portAllocator = portAllocator,
        templateManager = templateManager,
        groupManager = groupManager,
        softwareResolver = softwareResolver,
        compatibilityChecker = compatibilityChecker,
        eventBus = eventBus,
        velocityConfigGen = velocityConfigGen,
        moduleContext = moduleContext
    )

    suspend fun startService(groupName: String): Service? {
        val prepared = serviceFactory.prepare(groupName) ?: return null
        val (service, workDir, command, readyPattern, isModded, readyTimeout) = prepared
        val serviceName = service.name

        // Check if we should start on a remote node
        // Static services always run on the controller (persistent data in services/static/)
        val group = groupManager.getGroup(groupName)
        val memory = group?.config?.group?.resources?.memory ?: "1G"
        val remoteNode = if (service.isStatic) null else nodeManager?.selectNode(memory)

        if (remoteNode != null) {
            return startRemoteService(service, prepared, remoteNode, group)
        }

        // Local start (single-node or controller-local)
        return startLocalService(service, prepared)
    }

    private suspend fun startLocalService(service: Service, prepared: ServiceFactory.PreparedService): Service? {
        val (_, workDir, command, readyPattern, isModded, readyTimeout, env) = prepared
        val serviceName = service.name

        val processHandle = ProcessHandle()
        if (readyPattern != null) {
            processHandle.setReadyPattern(readyPattern)
        }

        return try {
            processHandle.start(workDir, command, env)
            // Store handle immediately after start so cleanupFailedStart can find it
            processHandles[serviceName] = processHandle

            service.transitionTo(ServiceState.STARTING)
            service.pid = processHandle.pid()
            service.startedAt = Instant.now()
            eventBus.emit(NimbusEvent.ServiceStarting(serviceName, service.groupName, service.port))

            launchReadyMonitor(service, processHandle, readyTimeout)
            launchExitMonitor(service, processHandle)

            service
        } catch (e: Exception) {
            logger.error("Failed to start service '{}'", serviceName, e)
            // Ensure the process is destroyed even if it wasn't stored in the map yet
            processHandles[serviceName] = processHandle
            cleanupFailedStart(service)
            null
        }
    }

    private suspend fun startRemoteService(
        service: Service,
        prepared: ServiceFactory.PreparedService,
        node: dev.kryonix.nimbus.cluster.NodeConnection,
        group: dev.kryonix.nimbus.group.ServerGroup?
    ): Service? {
        val serviceName = service.name
        val groupConfig = group?.config?.group ?: return null

        return try {
            // Update service with remote node info
            service.host = node.host
            service.nodeId = node.nodeId

            val startMsg = buildStartServiceMessage(service, groupConfig)

            // Create remote handle
            val remoteHandle = RemoteServiceHandle(serviceName, node)
            val readyPattern = prepared.readyPattern
            if (readyPattern != null) {
                remoteHandle.setReadyPattern(readyPattern)
            }
            node.remoteHandles[serviceName] = remoteHandle
            processHandles[serviceName] = remoteHandle

            // Send start command to agent
            node.send(startMsg)

            service.transitionTo(ServiceState.STARTING)
            service.startedAt = Instant.now()
            eventBus.emit(NimbusEvent.ServiceStarting(serviceName, service.groupName, service.port, node.nodeId))

            logger.info("Service '{}' starting on remote node '{}'", serviceName, node.nodeId)

            launchReadyMonitor(service, remoteHandle, prepared.readyTimeout)
            launchExitMonitor(service, remoteHandle)

            service
        } catch (e: Exception) {
            logger.error("Failed to start remote service '{}' on node '{}'", serviceName, node.nodeId, e)
            cleanupFailedStart(service)
            null
        }
    }

    private fun buildStartServiceMessage(
        service: Service,
        groupConfig: GroupDefinition
    ): ClusterMessage.StartService {
        val templatesDir = Path(config.paths.templates)
        val templateDir = templatesDir.resolve(groupConfig.template)
        val templateHash = computeTemplateHash(templateDir, groupConfig.software)
        val forwardingMode = compatibilityChecker.determineForwardingMode()
        val forwardingSecret = computeForwardingSecret()
        val isModded = groupConfig.software in listOf(
            ServerSoftware.FORGE, ServerSoftware.NEOFORGE, ServerSoftware.FABRIC
        )

        return ClusterMessage.StartService(
            serviceName = service.name,
            groupName = service.groupName,
            port = service.port,
            templateName = groupConfig.template,
            templateHash = templateHash,
            software = groupConfig.software.name,
            version = groupConfig.version,
            memory = groupConfig.resources.memory,
            jvmArgs = resolveJvmArgs(groupConfig),
            jvmOptimize = groupConfig.jvm.optimize,
            jarName = softwareResolver.jarFileName(groupConfig.software),
            modloaderVersion = groupConfig.modloaderVersion,
            readyPattern = groupConfig.readyPattern,
            readyTimeoutSeconds = if (isModded) 180 else 60,
            forwardingMode = forwardingMode,
            forwardingSecret = forwardingSecret,
            isStatic = service.isStatic,
            isModded = isModded,
            apiUrl = if (config.api.enabled) "http://${config.api.bind}:${config.api.port}" else "",
            apiToken = if (groupConfig.software == dev.kryonix.nimbus.config.ServerSoftware.VELOCITY) {
                config.api.token
            } else {
                dev.kryonix.nimbus.api.NimbusApi.deriveServiceToken(config.api.token)
            },
            javaVersion = javaResolver.requiredJavaVersion(groupConfig.version, groupConfig.software),
            bedrockPort = service.bedrockPort ?: 0,
            bedrockEnabled = config.bedrock.enabled && groupConfig.software == dev.kryonix.nimbus.config.ServerSoftware.VELOCITY
        )
    }

    private fun resolveJvmArgs(groupConfig: GroupDefinition): List<String> {
        val jvm = groupConfig.jvm
        if (jvm.optimize && jvm.args.isEmpty()) {
            return performanceOptimizer.aikarsFlags(groupConfig.resources.memory)
        }
        return jvm.args
    }

    private fun computeForwardingSecret(): String {
        return try {
            val secretFile = Path(config.paths.templates).resolve("proxy").resolve("forwarding.secret")
            if (secretFile.exists()) secretFile.toFile().readText().trim() else ""
        } catch (_: Exception) { "" }
    }

    private fun launchReadyMonitor(service: Service, handle: ServiceHandle, readyTimeout: Duration) {
        val serviceName = service.name
        scope.launch {
            try {
                val ready = handle.waitForReady(readyTimeout)
                if (ready) {
                    service.transitionTo(ServiceState.READY)
                    eventBus.emit(NimbusEvent.ServiceReady(serviceName, service.groupName))
                    logger.info("Service '{}' is ready", serviceName)
                    velocityConfigGen.updateProxyServerList()
                    reloadVelocity()
                } else {
                    logger.warn("Service '{}' did not become ready within timeout — marking as CRASHED", serviceName)
                    service.transitionTo(ServiceState.CRASHED)
                    eventBus.emit(NimbusEvent.ServiceCrashed(serviceName, -1, service.restartCount))
                }
            } catch (e: Exception) {
                logger.error("Error waiting for service '{}' to become ready — marking as CRASHED", serviceName, e)
                service.transitionTo(ServiceState.CRASHED)
            }
        }
    }

    private fun launchExitMonitor(service: Service, handle: ServiceHandle) {
        val serviceName = service.name
        val group = groupManager.getGroup(service.groupName)
        scope.launch {
            try {
                monitorProcess(
                    service, handle, service.groupName,
                    group?.config?.group?.lifecycle?.restartOnCrash ?: false,
                    group?.config?.group?.lifecycle?.maxRestarts ?: 0
                )
            } catch (e: Exception) {
                logger.error("Error monitoring service '{}'", serviceName, e)
            }
        }
    }

    private fun cleanupFailedStart(service: Service) {
        processHandles[service.name]?.destroy()
        processHandles.remove(service.name)
        portAllocator.release(service.port)
        service.bedrockPort?.let { portAllocator.releaseBedrockPort(it) }
        registry.unregister(service.name)
    }

    private fun computeTemplateHash(templateDir: Path, software: ServerSoftware): String {
        if (!templateDir.exists()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        val templatesDir = Path(config.paths.templates)

        // Hash global templates first (must match TemplateRoutes order)
        val vanillaBased = software in listOf(ServerSoftware.PAPER, ServerSoftware.PUFFERFISH, ServerSoftware.PURPUR, ServerSoftware.FOLIA, ServerSoftware.VELOCITY)
        if (vanillaBased) {
            hashDir(digest, templatesDir.resolve("global"))
        }
        if (software == ServerSoftware.VELOCITY) {
            hashDir(digest, templatesDir.resolve("global_proxy"))
        }

        // Then hash the group template
        hashDir(digest, templateDir)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hashDir(digest: MessageDigest, dir: Path) {
        if (!dir.exists()) return
        Files.walk(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                digest.update(dir.relativize(file).toString().toByteArray())
                digest.update(Files.readAllBytes(file))
            }
        }
    }

    private suspend fun monitorProcess(service: Service, handle: ServiceHandle, groupName: String, restartOnCrash: Boolean, maxRestarts: Int) {
        val serviceName = service.name
        handle.awaitExit()

        // If we intentionally stopped/drained it, do nothing
        if (service.state == ServiceState.DRAINING || service.state == ServiceState.STOPPING || service.state == ServiceState.STOPPED) {
            return
        }

        // Check if this service instance is still the active one (not replaced by a restart)
        val currentService = registry.get(serviceName)
        if (currentService !== service) {
            return
        }

        val exitCode = handle.exitCode() ?: -1

        handle.destroy()
        processHandles.remove(serviceName)
        portAllocator.release(service.port)
        service.bedrockPort?.let { portAllocator.releaseBedrockPort(it) }
        registry.unregister(serviceName)
        if (!service.isStatic) {
            cleanupWorkingDirectory(service.workingDirectory)
        }

        // Exit code 0 = clean shutdown, not a crash
        if (exitCode == 0) {
            service.transitionTo(ServiceState.STOPPED)
            logger.info("Service '{}' exited cleanly (code 0)", serviceName)
            eventBus.emit(NimbusEvent.ServiceStopped(serviceName))
            return
        }

        // Non-zero exit = actual crash
        service.transitionTo(ServiceState.CRASHED)
        logger.warn("Service '{}' crashed with exit code {}", serviceName, exitCode)
        eventBus.emit(NimbusEvent.ServiceCrashed(serviceName, exitCode, service.restartCount))

        if (restartOnCrash && service.restartCount < maxRestarts) {
            logger.info("Restarting service '{}' (attempt {}/{})", serviceName, service.restartCount + 1, maxRestarts)
            val newService = startService(groupName)
            if (newService != null) {
                newService.restartCount = service.restartCount + 1
            }
        } else if (service.restartCount >= maxRestarts) {
            logger.error("Service '{}' exceeded max restarts ({}), not restarting", serviceName, maxRestarts)
        }
    }

    /**
     * Stop a service. If the service has players and [forceful] is false, it enters DRAINING
     * first — no new players are routed to it, and it transitions to STOPPING once all players
     * leave or the drain timeout expires.
     */
    suspend fun stopService(name: String, forceful: Boolean = false): Boolean {
        val service = registry.get(name)
        if (service == null) {
            logger.warn("Cannot stop service '{}': not found", name)
            return false
        }

        // Already draining or stopping — idempotent
        if (service.state == ServiceState.DRAINING || service.state == ServiceState.STOPPING) {
            return true
        }

        // Skip draining if forced, no players, or service not ready
        if (forceful || service.playerCount == 0 || service.state != ServiceState.READY) {
            return doStop(service)
        }

        // Enter DRAINING state
        if (!service.transitionTo(ServiceState.DRAINING)) {
            return doStop(service)
        }

        val group = groupManager.getGroup(service.groupName)
        val drainTimeout = group?.config?.group?.lifecycle?.drainTimeout ?: 30L
        logger.info("Service '{}' entering drain state ({} players, {}s timeout)", name, service.playerCount, drainTimeout)
        eventBus.emit(NimbusEvent.ServiceDraining(name, service.groupName))

        // Launch drain monitor
        scope.launch {
            val deadline = Instant.now().plusSeconds(drainTimeout)
            while (service.state == ServiceState.DRAINING) {
                if (service.playerCount == 0) {
                    logger.info("Service '{}' drained (0 players), proceeding to stop", name)
                    doStop(service)
                    return@launch
                }
                if (Instant.now().isAfter(deadline)) {
                    logger.warn("Service '{}' drain timeout expired with {} players, force-stopping", name, service.playerCount)
                    doStop(service)
                    return@launch
                }
                delay(1000)
            }
        }

        return true
    }

    private suspend fun doStop(service: Service): Boolean {
        val name = service.name

        // Atomic guard: only one thread can transition to STOPPING
        if (!service.transitionTo(ServiceState.STOPPING)) {
            logger.debug("Service '{}' is already stopping or stopped", name)
            return false
        }

        return try {
            eventBus.emit(NimbusEvent.ServiceStopping(name))
            logger.info("Stopping service '{}'", name)

            val handle = processHandles[name]
            if (handle != null) {
                handle.stopGracefully(30.seconds)
                handle.destroy()
            }

            portAllocator.release(service.port)
            service.bedrockPort?.let { portAllocator.releaseBedrockPort(it) }
            service.transitionTo(ServiceState.STOPPED)
            eventBus.emit(NimbusEvent.ServiceStopped(name))

            registry.unregister(name)
            processHandles.remove(name)

            if (!service.isStatic) {
                cleanupWorkingDirectory(service.workingDirectory)
            }

            // Update Velocity proxy server list and reload
            velocityConfigGen.updateProxyServerList()
            reloadVelocity()

            logger.info("Service '{}' stopped and cleaned up", name)
            true
        } catch (e: Exception) {
            logger.error("Error stopping service '{}'", name, e)
            false
        }
    }

    suspend fun purgeService(name: String) {
        val service = registry.get(name) ?: throw IllegalArgumentException("Service '$name' not found")

        logger.warn("Purging service '{}' (state: {})", name, service.state)

        // Force-kill the process if it still exists
        val handle = processHandles.remove(name)
        handle?.destroy()

        // Release resources
        portAllocator.release(service.port)
        service.bedrockPort?.let { portAllocator.releaseBedrockPort(it) }
        registry.unregister(name)

        if (!service.isStatic) {
            cleanupWorkingDirectory(service.workingDirectory)
        }

        velocityConfigGen.updateProxyServerList()
        reloadVelocity()

        eventBus.emit(NimbusEvent.ServiceStopped(name))
        logger.info("Service '{}' purged", name)
    }

    suspend fun restartService(name: String): Service? {
        val service = registry.get(name)
        if (service == null) {
            logger.warn("Cannot restart service '{}': not found", name)
            return null
        }

        val groupName = service.groupName
        logger.info("Restarting service '{}' in group '{}'", name, groupName)

        stopService(name)
        // Static services reuse the same name automatically (lowest available = the one we just stopped)
        return startService(groupName)
    }

    fun checkCompatibility() = compatibilityChecker.checkCompatibility()

    fun determineForwardingMode() = compatibilityChecker.determineForwardingMode()

    /**
     * Starts minimum instances for all groups in a phased order:
     * 1. Proxy groups first — waits until all proxies are READY
     * 2. Backend groups after proxies are ready
     *
     * This ensures the forwarding secret is available and the proxy is accepting
     * connections before any backend server attempts to register.
     */
    suspend fun startMinimumInstances() {
        logger.info("Starting minimum instances for all groups")

        val allGroups = groupManager.getAllGroups()
        val proxyGroups = allGroups.filter { it.config.group.software == ServerSoftware.VELOCITY }
        val backendGroups = allGroups.filter { it.config.group.software != ServerSoftware.VELOCITY }

        // Phase 1: Start proxy groups and wait for them to become READY
        if (proxyGroups.isNotEmpty()) {
            logger.info("Startup phase 1: Starting proxy group(s)...")
            val proxyServices = startGroupsAndCollect(proxyGroups)
            if (proxyServices.isNotEmpty()) {
                logger.info("Waiting for {} proxy service(s) to become ready...", proxyServices.size)
                val allReady = awaitServicesReady(proxyServices, timeoutMs = 120_000)
                if (allReady) {
                    logger.info("All proxy services are ready")
                } else {
                    logger.warn("Not all proxy services became ready within timeout — starting backends anyway")
                }
            }
        }

        // Phase 2: Start all backend groups
        if (backendGroups.isNotEmpty()) {
            logger.info("Startup phase 2: Starting backend group(s)...")
            startGroupsAndCollect(backendGroups)
        }
    }

    private suspend fun startGroupsAndCollect(groups: List<dev.kryonix.nimbus.group.ServerGroup>): List<Service> {
        val started = mutableListOf<Service>()
        for (group in groups) {
            val currentCount = registry.countByGroup(group.name)
            val needed = group.minInstances - currentCount
            if (needed > 0) {
                logger.info("Group '{}' needs {} more instance(s) (current: {}, min: {})", group.name, needed, currentCount, group.minInstances)
                repeat(needed) {
                    val service = startService(group.name)
                    if (service != null) started.add(service)
                }
            }
        }
        return started
    }

    /**
     * Waits until all given services have reached READY or CRASHED state,
     * or until the timeout expires. Uses the EventBus to listen for state changes.
     */
    private suspend fun awaitServicesReady(services: List<Service>, timeoutMs: Long): Boolean {
        if (services.isEmpty()) return true

        val pending = services.map { it.name }.toMutableSet()
        val done = CompletableDeferred<Boolean>()

        // Listen for ready/crashed events
        val readyJob = eventBus.on<NimbusEvent.ServiceReady> { event ->
            pending.remove(event.serviceName)
            if (pending.isEmpty() && !done.isCompleted) done.complete(true)
        }
        val crashedJob = eventBus.on<NimbusEvent.ServiceCrashed> { event ->
            pending.remove(event.serviceName)
            if (pending.isEmpty() && !done.isCompleted) done.complete(true)
        }

        // Timeout fallback
        val timeoutJob = scope.launch {
            delay(timeoutMs)
            if (!done.isCompleted) done.complete(false)
        }

        // Check if any services already reached their state before we subscribed
        pending.removeAll { name ->
            val svc = registry.get(name)
            svc == null || svc.state == ServiceState.READY || svc.state == ServiceState.CRASHED || svc.state == ServiceState.STOPPED
        }
        if (pending.isEmpty() && !done.isCompleted) done.complete(true)

        val result = done.await()
        readyJob.cancel()
        crashedJob.cancel()
        timeoutJob.cancel()
        return result
    }

    suspend fun stopAll() {
        logger.info("Stopping all services (ordered: game -> lobby -> proxy)")
        val allServices = registry.getAll()

        // Categorize services: game servers (stopOnEmpty=true), lobbies (stopOnEmpty=false), proxies
        val proxies = allServices.filter {
            val group = groupManager.getGroup(it.groupName)
            group?.config?.group?.software == ServerSoftware.VELOCITY
        }
        val backends = allServices.filter {
            val group = groupManager.getGroup(it.groupName)
            group != null && group.config.group.software != ServerSoftware.VELOCITY
        }
        val gameServers = backends.filter {
            val group = groupManager.getGroup(it.groupName)
            group?.config?.group?.lifecycle?.stopOnEmpty == true
        }
        val lobbies = backends.filter {
            val group = groupManager.getGroup(it.groupName)
            group?.config?.group?.lifecycle?.stopOnEmpty != true
        }

        if (gameServers.isNotEmpty()) {
            logger.info("Stopping {} game server(s)...", gameServers.size)
            for (service in gameServers) {
                stopService(service.name, forceful = true)
            }
        }

        if (lobbies.isNotEmpty()) {
            logger.info("Stopping {} lobby/lobbies...", lobbies.size)
            for (service in lobbies) {
                stopService(service.name, forceful = true)
            }
        }

        if (proxies.isNotEmpty()) {
            logger.info("Stopping {} proxy/proxies...", proxies.size)
            for (service in proxies) {
                stopService(service.name, forceful = true)
            }
        }

        logger.info("All services stopped")
    }

    /**
     * Converts a running dynamic service to static.
     * Copies the current working directory to services/static/{name}/ and marks it as static,
     * so it won't be cleaned up on stop and will be reused on next start.
     */
    suspend fun convertToStatic(serviceName: String): Boolean {
        val service = registry.get(serviceName)
        if (service == null) {
            logger.warn("Cannot convert '{}': service not found", serviceName)
            return false
        }
        if (service.isStatic) {
            logger.warn("Service '{}' is already static", serviceName)
            return false
        }

        val servicesDir = Path(config.paths.services)
        val staticDir = servicesDir.resolve("static").resolve(serviceName)

        return try {
            withContext(Dispatchers.IO) {
                staticDir.createDirectories()
                // Copy current working directory contents to static location
                Files.walk(service.workingDirectory).use { stream ->
                    stream.forEach { source ->
                        val target = staticDir.resolve(service.workingDirectory.relativize(source))
                        if (Files.isDirectory(source)) {
                            target.createDirectories()
                        } else {
                            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                }
            }
            service.isStatic = true
            logger.info("Converted service '{}' to static (copied to {})", serviceName, staticDir)
            true
        } catch (e: Exception) {
            logger.error("Failed to convert service '{}' to static", serviceName, e)
            false
        }
    }

    fun getProcessHandle(serviceName: String): ServiceHandle? {
        return processHandles[serviceName]
    }

    suspend fun executeCommand(serviceName: String, command: String): Boolean {
        val handle = processHandles[serviceName]
        if (handle == null) {
            logger.warn("Cannot execute command on '{}': no process handle found", serviceName)
            return false
        }

        // Sanitize: strip newlines to prevent command injection via stdin
        val sanitized = command.replace("\r", "").replace("\n", "")
        if (sanitized != command) {
            logger.warn("Stripped newlines from command sent to '{}' (possible injection attempt)", serviceName)
        }

        return try {
            handle.sendCommand(sanitized)
            logger.debug("Executed command '{}' on service '{}'", sanitized, serviceName)
            true
        } catch (e: Exception) {
            logger.error("Failed to execute command on service '{}'", serviceName, e)
            false
        }
    }

    /**
     * Sends "velocity reload" to the running Velocity proxy to pick up config changes.
     */
    private suspend fun reloadVelocity() {
        val proxyService = registry.getAll().firstOrNull { service ->
            groupManager.getGroup(service.groupName)?.config?.group?.software == ServerSoftware.VELOCITY &&
                (service.state == ServiceState.READY)
        } ?: return
        val handle = processHandles[proxyService.name] ?: return
        try {
            handle.sendCommand("velocity reload")
            logger.debug("Sent 'velocity reload' to {}", proxyService.name)
        } catch (e: Exception) {
            logger.warn("Failed to reload Velocity: {}", e.message)
        }
    }

    private fun cleanupWorkingDirectory(workDir: Path) {
        if (!workDir.exists()) return
        try {
            Files.walk(workDir).use { stream ->
                stream.sorted(Comparator.reverseOrder())
                    .forEach(Files::delete)
            }
            logger.debug("Cleaned up working directory: {}", workDir)
        } catch (e: Exception) {
            logger.warn("Failed to clean up working directory: {}", workDir, e)
        }
    }
}
