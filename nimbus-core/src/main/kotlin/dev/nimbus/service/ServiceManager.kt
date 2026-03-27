package dev.nimbus.service

import dev.nimbus.config.NimbusConfig
import dev.nimbus.config.ServerSoftware
import dev.nimbus.event.EventBus
import dev.nimbus.event.NimbusEvent
import dev.nimbus.group.GroupManager
import dev.nimbus.template.ConfigPatcher
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

class ServiceManager(
    private val config: NimbusConfig,
    private val registry: ServiceRegistry,
    private val portAllocator: PortAllocator,
    private val templateManager: TemplateManager,
    private val groupManager: GroupManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope
) {

    private val logger = LoggerFactory.getLogger(ServiceManager::class.java)

    private val processHandles = ConcurrentHashMap<String, ProcessHandle>()
    private val serviceCounters = ConcurrentHashMap<String, AtomicInteger>()
    private val configPatcher = ConfigPatcher()
    private val velocityConfigGen = VelocityConfigGen(registry, groupManager)
    private val softwareResolver = SoftwareResolver()

    suspend fun startService(groupName: String): Service? {
        val group = groupManager.getGroup(groupName)
        if (group == null) {
            logger.warn("Cannot start service: group '{}' not found", groupName)
            return null
        }

        val currentCount = registry.countByGroup(groupName)
        if (currentCount >= group.maxInstances) {
            logger.warn("Cannot start service: group '{}' already at max instances ({}/{})", groupName, currentCount, group.maxInstances)
            return null
        }

        val software = group.config.group.software
        val counter = serviceCounters.computeIfAbsent(groupName) { AtomicInteger(0) }
        val instanceNumber = counter.incrementAndGet()
        val serviceName = "$groupName-$instanceNumber"

        val port = if (software == ServerSoftware.VELOCITY) {
            portAllocator.allocateProxyPort()
        } else {
            portAllocator.allocateBackendPort()
        }

        val templatesDir = Path(config.paths.templates)
        val runningDir = Path(config.paths.running)

        val service = Service(
            name = serviceName,
            groupName = groupName,
            port = port,
            state = ServiceState.PREPARING,
            workingDirectory = runningDir.resolve(serviceName)
        )

        // Ensure template directory exists and JAR is available (auto-download if missing)
        val jarName = softwareResolver.jarFileName(software)
        val templateDir = templatesDir.resolve(group.config.group.template)
        if (!templateDir.exists()) {
            templateDir.createDirectories()
        }

        val jarAvailable = softwareResolver.ensureJarAvailable(software, group.config.group.version, templateDir)
        if (!jarAvailable) {
            logger.error("Cannot start service '{}': failed to obtain server JAR for {} {}", serviceName, software, group.config.group.version)
            portAllocator.release(port)
            return null
        }

        // Auto-create eula.txt for Paper/Purpur servers
        if (software != ServerSoftware.VELOCITY) {
            val eulaFile = templateDir.resolve("eula.txt")
            if (!eulaFile.exists()) {
                eulaFile.writeText("eula=true\n")
                logger.info("Created eula.txt in template '{}'", group.config.group.template)
            }
        }

        // Initialize Velocity template if velocity.toml doesn't exist yet
        if (software == ServerSoftware.VELOCITY && !templateDir.resolve("velocity.toml").exists()) {
            logger.info("Initializing Velocity template (first run generates config files)...")
            initializeVelocityTemplate(templateDir, jarName)
        }

        registry.register(service)
        eventBus.emit(NimbusEvent.ServiceStarting(serviceName, groupName, port))
        logger.info("Starting service '{}' on port {}", serviceName, port)

        return try {
            val workDir = templateManager.prepareService(
                templateName = group.config.group.template,
                serviceName = serviceName,
                templatesDir = templatesDir,
                runningDir = runningDir
            )

            when (software) {
                ServerSoftware.VELOCITY -> configPatcher.patchVelocityConfig(workDir, port)
                else -> {
                    configPatcher.patchServerProperties(workDir, port)
                    // Configure Paper/Purpur for Velocity modern forwarding (1.13+ only)
                    val minor = group.config.group.version.split(".").getOrNull(1)?.toIntOrNull() ?: 99
                    if (minor >= 13) {
                        val velocityTemplateDir = templatesDir.resolve("proxy")
                        if (velocityTemplateDir.resolve("forwarding.secret").exists()) {
                            configPatcher.patchPaperForVelocity(workDir, velocityTemplateDir)
                        }
                    }
                }
            }

            val memory = group.config.group.resources.memory
            val jvmArgs = group.config.group.jvm.args
            val command = mutableListOf("java", "-Xmx$memory")
            command.addAll(jvmArgs)
            command.add("-jar")
            command.add(jarName)
            if (software != ServerSoftware.VELOCITY) {
                // --nogui exists since ~1.13, older versions only have --noconsole
                val version = group.config.group.version
                val minor = version.split(".").getOrNull(1)?.toIntOrNull() ?: 99
                if (minor >= 13) {
                    command.add("--nogui")
                }
            }

            val processHandle = ProcessHandle()
            processHandle.start(workDir, command)
            processHandles[serviceName] = processHandle

            service.state = ServiceState.STARTING
            service.pid = processHandle.pid()
            service.startedAt = Instant.now()

            // Wait for server to become ready
            scope.launch {
                try {
                    val ready = processHandle.waitForReady(60.seconds)
                    if (ready) {
                        service.state = ServiceState.READY
                        eventBus.emit(NimbusEvent.ServiceReady(serviceName, groupName))
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
            scope.launch {
                try {
                    monitorProcess(serviceName, groupName, group.config.group.lifecycle.restartOnCrash, group.config.group.lifecycle.maxRestarts)
                } catch (e: Exception) {
                    logger.error("Error monitoring service '{}'", serviceName, e)
                }
            }

            service
        } catch (e: Exception) {
            logger.error("Failed to start service '{}'", serviceName, e)
            portAllocator.release(port)
            registry.unregister(serviceName)
            null
        }
    }

    private suspend fun monitorProcess(serviceName: String, groupName: String, restartOnCrash: Boolean, maxRestarts: Int) {
        val handle = processHandles[serviceName] ?: return
        // Poll until the process is no longer alive
        withContext(Dispatchers.IO) {
            while (handle.isAlive()) {
                Thread.sleep(1000)
            }
        }

        val service = registry.get(serviceName) ?: return

        // If we intentionally stopped it, do nothing
        if (service.state == ServiceState.STOPPING || service.state == ServiceState.STOPPED) {
            return
        }

        val exitCode = handle.exitCode() ?: -1

        // Clean up the instance
        processHandles.remove(serviceName)
        portAllocator.release(service.port)
        registry.unregister(serviceName)
        cleanupWorkingDirectory(service.workingDirectory)

        // Exit code 0 = clean shutdown, not a crash
        if (exitCode == 0) {
            service.state = ServiceState.STOPPED
            logger.info("Service '{}' exited cleanly (code 0)", serviceName)
            eventBus.emit(NimbusEvent.ServiceStopped(serviceName))
            return
        }

        // Non-zero exit = actual crash
        service.state = ServiceState.CRASHED
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
            service.state = ServiceState.STOPPING
            logger.info("Stopping service '{}'", name)

            val handle = processHandles[name]
            if (handle != null) {
                handle.stopGracefully(30.seconds)
                handle.destroy()
            }

            portAllocator.release(service.port)
            service.state = ServiceState.STOPPED
            eventBus.emit(NimbusEvent.ServiceStopped(name))

            registry.unregister(name)
            processHandles.remove(name)

            cleanupWorkingDirectory(service.workingDirectory)

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
        return startService(groupName)
    }

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
        logger.info("Stopping all services")
        val allServices = registry.getAll()

        // Stop game servers first, then lobbies, then proxies
        val gameServers = allServices.filter {
            val group = groupManager.getGroup(it.groupName)
            group != null && group.config.group.software != ServerSoftware.VELOCITY
        }
        val proxies = allServices.filter {
            val group = groupManager.getGroup(it.groupName)
            group != null && group.config.group.software == ServerSoftware.VELOCITY
        }

        logger.info("Stopping {} game server(s)", gameServers.size)
        for (service in gameServers) {
            stopService(service.name)
        }

        logger.info("Stopping {} proxy/proxies", proxies.size)
        for (service in proxies) {
            stopService(service.name)
        }

        logger.info("All services stopped")
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

    /**
     * Runs Velocity once in the template dir to generate velocity.toml, forwarding.secret, etc.
     * Velocity exits after generating configs — we wait for it to finish.
     */
    private suspend fun initializeVelocityTemplate(templateDir: Path, jarName: String) {
        try {
            val process = withContext(Dispatchers.IO) {
                ProcessBuilder("java", "-jar", jarName)
                    .directory(templateDir.toFile())
                    .redirectErrorStream(true)
                    .start()
            }
            // Wait up to 15 seconds for Velocity to generate its config and exit
            withContext(Dispatchers.IO) {
                process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
                if (process.isAlive) process.destroyForcibly()
            }

            if (templateDir.resolve("velocity.toml").exists()) {
                logger.info("Velocity template initialized successfully")
            } else {
                logger.warn("Velocity config was not generated — proxy may fail to start")
            }
        } catch (e: Exception) {
            logger.error("Failed to initialize Velocity template: {}", e.message, e)
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
