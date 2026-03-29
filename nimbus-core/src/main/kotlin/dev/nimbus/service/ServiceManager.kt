package dev.nimbus.service

import dev.nimbus.config.NimbusConfig
import dev.nimbus.config.ServerSoftware
import dev.nimbus.event.EventBus
import dev.nimbus.event.NimbusEvent
import dev.nimbus.group.GroupManager
import dev.nimbus.template.SoftwareResolver
import dev.nimbus.template.TemplateManager
import dev.nimbus.velocity.VelocityConfigGen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds

class ServiceManager(
    private val config: NimbusConfig,
    private val registry: ServiceRegistry,
    private val portAllocator: PortAllocator,
    private val templateManager: TemplateManager,
    private val groupManager: GroupManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val softwareResolver: SoftwareResolver
) {

    private val logger = LoggerFactory.getLogger(ServiceManager::class.java)

    private val processHandles = ConcurrentHashMap<String, ProcessHandle>()
    private val velocityConfigGen = VelocityConfigGen(registry, groupManager)
    private val compatibilityChecker = CompatibilityChecker(
        groupManager, config,
        JavaResolver(config.java.toMap(), Path(config.paths.templates).toAbsolutePath().parent ?: Path("."))
    )

    private val serviceFactory = ServiceFactory(
        config = config,
        registry = registry,
        portAllocator = portAllocator,
        templateManager = templateManager,
        groupManager = groupManager,
        softwareResolver = softwareResolver,
        compatibilityChecker = compatibilityChecker,
        eventBus = eventBus,
        velocityConfigGen = velocityConfigGen
    )

    suspend fun startService(groupName: String): Service? {
        val prepared = serviceFactory.prepare(groupName) ?: return null
        val (service, workDir, command, readyPattern, isModded, readyTimeout) = prepared
        val serviceName = service.name

        return try {
            val processHandle = ProcessHandle()
            if (readyPattern != null) {
                processHandle.setReadyPattern(readyPattern)
            }
            processHandle.start(workDir, command)
            processHandles[serviceName] = processHandle

            service.transitionTo(ServiceState.STARTING)
            service.pid = processHandle.pid()
            service.startedAt = Instant.now()

            // Wait for server to become ready
            scope.launch {
                try {
                    val ready = processHandle.waitForReady(readyTimeout)
                    if (ready) {
                        service.transitionTo(ServiceState.READY)
                        eventBus.emit(NimbusEvent.ServiceReady(serviceName, service.groupName))
                        logger.info("Service '{}' is ready", serviceName)
                        // Update Velocity proxy server list and reload
                        velocityConfigGen.updateProxyServerList()
                        reloadVelocity()
                    } else {
                        logger.warn("Service '{}' did not become ready within timeout", serviceName)
                    }
                } catch (e: Exception) {
                    logger.error("Error waiting for service '{}' to become ready", serviceName, e)
                }
            }

            // Monitor for unexpected process exit
            val group = groupManager.getGroup(service.groupName)
            scope.launch {
                try {
                    monitorProcess(
                        service, processHandle, service.groupName,
                        group?.config?.group?.lifecycle?.restartOnCrash ?: false,
                        group?.config?.group?.lifecycle?.maxRestarts ?: 0
                    )
                } catch (e: Exception) {
                    logger.error("Error monitoring service '{}'", serviceName, e)
                }
            }

            service
        } catch (e: Exception) {
            logger.error("Failed to start service '{}'", serviceName, e)
            processHandles[serviceName]?.destroy()
            processHandles.remove(serviceName)
            portAllocator.release(service.port)
            registry.unregister(serviceName)
            null
        }
    }

    private suspend fun monitorProcess(service: Service, handle: ProcessHandle, groupName: String, restartOnCrash: Boolean, maxRestarts: Int) {
        val serviceName = service.name
        handle.awaitExit()

        // If we intentionally stopped it, do nothing
        if (service.state == ServiceState.STOPPING || service.state == ServiceState.STOPPED) {
            return
        }

        // Check if this service instance is still the active one (not replaced by a restart)
        val currentService = registry.get(serviceName)
        if (currentService !== service) {
            return
        }

        val exitCode = handle.exitCode() ?: -1

        // Clean up the instance
        handle.destroy()
        processHandles.remove(serviceName)
        portAllocator.release(service.port)
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

    suspend fun stopService(name: String): Boolean {
        val service = registry.get(name)
        if (service == null) {
            logger.warn("Cannot stop service '{}': not found", name)
            return false
        }

        return try {
            eventBus.emit(NimbusEvent.ServiceStopping(name))
            service.transitionTo(ServiceState.STOPPING)
            logger.info("Stopping service '{}'", name)

            val handle = processHandles[name]
            if (handle != null) {
                handle.stopGracefully(30.seconds)
                handle.destroy()
            }

            portAllocator.release(service.port)
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

    suspend fun startMinimumInstances() {
        logger.info("Starting minimum instances for all groups")
        for (group in groupManager.getAllGroups()) {
            val currentCount = registry.countByGroup(group.name)
            val needed = group.minInstances - currentCount
            if (needed > 0) {
                logger.info("Group '{}' needs {} more instance(s) (current: {}, min: {})", group.name, needed, currentCount, group.minInstances)
                repeat(needed) {
                    startService(group.name)
                }
            }
        }
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
                stopService(service.name)
            }
        }

        if (lobbies.isNotEmpty()) {
            logger.info("Stopping {} lobby/lobbies...", lobbies.size)
            for (service in lobbies) {
                stopService(service.name)
            }
        }

        if (proxies.isNotEmpty()) {
            logger.info("Stopping {} proxy/proxies...", proxies.size)
            for (service in proxies) {
                stopService(service.name)
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

    fun getProcessHandle(serviceName: String): ProcessHandle? {
        return processHandles[serviceName]
    }

    suspend fun executeCommand(serviceName: String, command: String): Boolean {
        val handle = processHandles[serviceName]
        if (handle == null) {
            logger.warn("Cannot execute command on '{}': no process handle found", serviceName)
            return false
        }

        return try {
            handle.sendCommand(command)
            logger.debug("Executed command '{}' on service '{}'", command, serviceName)
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
