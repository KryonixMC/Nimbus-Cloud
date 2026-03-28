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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
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
    private val javaResolver = JavaResolver(config.java.toMap(), Path(config.paths.templates).toAbsolutePath().parent ?: Path("."))

    /**
     * Determines forwarding mode based on all configured groups.
     * If ANY backend group uses a version < 1.13, legacy (BungeeCord) forwarding is required.
     * Forge servers also default to legacy forwarding unless they have a Velocity forwarding mod.
     * Otherwise, modern (Velocity) forwarding is used for better security.
     */
    /**
     * Checks if an MC version is pre-1.13 (needs legacy forwarding).
     * Supports both old (1.x.x) and new (26.x) versioning schemes.
     */
    private fun isLegacyVersion(mcVersion: String): Boolean {
        val parts = mcVersion.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        if (major >= 2) return false // New scheme (26.x+) is always modern
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return minor < 13
    }

    /**
     * Returns the nogui flag for a given MC version.
     * 1.14+ uses "--nogui", 1.7-1.13 uses "nogui", pre-1.7 uses nothing.
     * New scheme (26.x+) always uses "--nogui".
     */
    private fun noguiFlag(mcVersion: String): String? {
        val parts = mcVersion.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return "--nogui"
        if (major >= 2) return "--nogui"
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return "--nogui"
        return when {
            minor >= 14 -> "--nogui"
            minor >= 7 -> "nogui"
            else -> null
        }
    }

    fun determineForwardingMode(): String {
        val hasLegacyServer = groupManager.getAllGroups().any { group ->
            group.config.group.software != ServerSoftware.VELOCITY &&
                isLegacyVersion(group.config.group.version)
        }
        return if (hasLegacyServer) "legacy" else "modern"
    }

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
        val servicesDir = Path(config.paths.services)
        val isStatic = group.isStatic

        val workingDirectory = if (isStatic) {
            servicesDir.resolve("static").resolve(serviceName)
        } else {
            val shortUuid = UUID.randomUUID().toString().replace("-", "").take(8)
            servicesDir.resolve("temp").resolve("${serviceName}_$shortUuid")
        }

        val service = Service(
            name = serviceName,
            groupName = groupName,
            port = port,
            state = ServiceState.PREPARING,
            workingDirectory = workingDirectory
        )

        // Ensure template directory exists and JAR is available (auto-download/install if missing)
        val templateDir = templatesDir.resolve(group.config.group.template)
        if (!templateDir.exists()) {
            templateDir.createDirectories()
        }

        val jarAvailable = softwareResolver.ensureJarAvailable(
            software,
            group.config.group.version,
            templateDir,
            modloaderVersion = group.config.group.modloaderVersion,
            customJarName = group.config.group.jarName
        )
        if (!jarAvailable) {
            logger.error("Cannot start service '{}': failed to obtain server JAR for {} {}", serviceName, software, group.config.group.version)
            portAllocator.release(port)
            return null
        }

        // Auto-deploy proxy forwarding mods for modded servers
        if (software in listOf(ServerSoftware.FORGE, ServerSoftware.NEOFORGE)) {
            softwareResolver.ensureForwardingMod(software, group.config.group.version, templateDir)
        } else if (software == ServerSoftware.FABRIC) {
            softwareResolver.ensureFabricProxyMod(templateDir, group.config.group.version)
        }

        // Auto-create eula.txt for all game servers
        if (software != ServerSoftware.VELOCITY) {
            val eulaFile = templateDir.resolve("eula.txt")
            if (!eulaFile.exists()) {
                eulaFile.writeText("eula=true\n")
                logger.info("Created eula.txt in template '{}'", group.config.group.template)
            }
        }

        // Pre-initialize Fabric template: run launcher once to download vanilla server
        if (software == ServerSoftware.FABRIC && !templateDir.resolve(".fabric").exists()) {
            logger.info("Initializing Fabric template (downloading vanilla server)...")
            initializeFabricTemplate(templateDir)
        }

        // Initialize Velocity template if velocity.toml doesn't exist yet
        val jarName = softwareResolver.jarFileName(software)
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
                targetDir = workingDirectory,
                templatesDir = templatesDir,
                preserveExisting = isStatic
            )

            // Apply global templates (always overwrite, even for static services)
            val isVanillaBased = software in listOf(ServerSoftware.PAPER, ServerSoftware.PURPUR, ServerSoftware.VELOCITY)
            if (isVanillaBased) {
                templateManager.applyGlobalTemplate(templatesDir.resolve("global"), workDir)
            }
            if (software == ServerSoftware.VELOCITY) {
                templateManager.applyGlobalTemplate(templatesDir.resolve("global_proxy"), workDir)
            }

            val forwardingMode = determineForwardingMode()
            when (software) {
                ServerSoftware.VELOCITY -> configPatcher.patchVelocityConfig(workDir, port, forwardingMode)
                else -> {
                    configPatcher.patchServerProperties(workDir, port)

                    val isPaperBased = software in listOf(ServerSoftware.PAPER, ServerSoftware.PURPUR)
                    val velocityTemplateDir = templatesDir.resolve("proxy")

                    if (isPaperBased) {
                        if (forwardingMode == "modern") {
                            val minor = group.config.group.version.split(".").getOrNull(1)?.toIntOrNull() ?: 99
                            if (minor >= 13 && velocityTemplateDir.resolve("forwarding.secret").exists()) {
                                configPatcher.patchPaperForVelocity(workDir, velocityTemplateDir)
                            }
                        } else {
                            configPatcher.patchSpigotForBungeeCord(workDir)
                            logger.info("Using legacy (BungeeCord) forwarding for '{}' (pre-1.13 servers detected)", serviceName)
                        }
                    } else if (software == ServerSoftware.FABRIC) {
                        configPatcher.patchFabricProxyLite(workDir, velocityTemplateDir, forwardingMode)
                    } else if (software in listOf(ServerSoftware.FORGE, ServerSoftware.NEOFORGE)) {
                        configPatcher.patchForgeProxy(workDir, velocityTemplateDir, forwardingMode)
                    }
                }
            }

            val memory = group.config.group.resources.memory
            val jvmArgs = group.config.group.jvm.args
            val javaBin = javaResolver.resolve(group.config.group.version, software, group.config.group.javaPath)
            val requiredJava = javaResolver.requiredJavaVersion(group.config.group.version, software)
            logger.info("Service '{}' using Java {} ({})", serviceName, requiredJava, javaBin)
            val command = mutableListOf(javaBin, "-Xmx$memory")
            command.addAll(jvmArgs)

            // Build startup command based on software type
            val isModded = software in listOf(ServerSoftware.FORGE, ServerSoftware.NEOFORGE, ServerSoftware.FABRIC, ServerSoftware.CUSTOM)
            if (isModded) {
                // Resolve args from the working dir (where the process actually runs)
                val startArgs = softwareResolver.getModdedStartCommand(software, workDir, group.config.group.jarName)
                command.addAll(startArgs)
            } else {
                command.add("-jar")
                command.add(jarName)
            }

            // Add nogui flag
            if (software != ServerSoftware.VELOCITY) {
                if (isModded) {
                    command.add("nogui")
                } else {
                    val flag = noguiFlag(group.config.group.version)
                    if (flag != null) command.add(flag)
                }
            }

            val processHandle = ProcessHandle()

            // Set custom readiness pattern for modded servers
            val customPattern = group.config.group.readyPattern
            if (customPattern.isNotEmpty()) {
                processHandle.setReadyPattern(Regex(customPattern))
            } else if (software == ServerSoftware.FORGE || software == ServerSoftware.NEOFORGE) {
                processHandle.setReadyPattern(Regex("""Done \(|For help, type"""))
            }

            processHandle.start(workDir, command)
            processHandles[serviceName] = processHandle

            service.state = ServiceState.STARTING
            service.pid = processHandle.pid()
            service.startedAt = Instant.now()

            // Wait for server to become ready (modded servers need more time)
            val readyTimeout = if (isModded) 180.seconds else 60.seconds
            scope.launch {
                try {
                    val ready = processHandle.waitForReady(readyTimeout)
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
        val group = groupManager.getGroup(groupName)
        if (group == null || !group.isStatic) {
            cleanupWorkingDirectory(service.workingDirectory)
        }

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

            val group = groupManager.getGroup(service.groupName)
            if (group == null || !group.isStatic) {
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
        return startService(groupName)
    }

    /**
     * Checks for compatibility issues between configured groups.
     * Returns a list of warning messages to display in the console.
     */
    fun checkCompatibility(): List<CompatWarning> {
        val warnings = mutableListOf<CompatWarning>()
        val allGroups = groupManager.getAllGroups()
        val forwardingMode = determineForwardingMode()

        val legacyGroups = allGroups.filter { group ->
            group.config.group.software != ServerSoftware.VELOCITY &&
                isLegacyVersion(group.config.group.version)
        }

        val modernOnlyGroups = allGroups.filter { group ->
            group.config.group.software in listOf(ServerSoftware.FABRIC, ServerSoftware.NEOFORGE)
        }

        // Legacy + Fabric/NeoForge conflict
        if (legacyGroups.isNotEmpty() && modernOnlyGroups.isNotEmpty()) {
            val legacy = legacyGroups.joinToString(", ") { "${it.name} (${it.config.group.software} ${it.config.group.version})" }
            val modern = modernOnlyGroups.joinToString(", ") { "${it.name} (${it.config.group.software} ${it.config.group.version})" }
            warnings.add(CompatWarning(
                CompatWarning.Level.ERROR,
                "Forwarding mode conflict!",
                "Pre-1.13 servers force legacy forwarding: $legacy\n" +
                "These require modern forwarding and WILL NOT WORK: $modern\n" +
                "Fix: Upgrade pre-1.13 servers to 1.13+ or remove them."
            ))
        }

        // Mixed version info
        val mcVersions = allGroups
            .filter { it.config.group.software != ServerSoftware.VELOCITY }
            .map { it.config.group.version }
            .distinct()
        if (mcVersions.size > 1) {
            val versions = mcVersions.joinToString(", ")
            warnings.add(CompatWarning(
                CompatWarning.Level.INFO,
                "Multiple MC versions: $versions (forwarding: $forwardingMode)",
                if (forwardingMode == "legacy") "Via plugins (ViaVersion/ViaBackwards) recommended for cross-version support." else ""
            ))
        }

        // Forge/NeoForge without proxy mod
        for (g in allGroups.filter { it.config.group.software in listOf(ServerSoftware.FORGE, ServerSoftware.NEOFORGE) }) {
            val templateDir = java.nio.file.Path.of(config.paths.templates).resolve(g.config.group.template)
            val modsDir = templateDir.resolve("mods")
            val hasProxyMod = modsDir.toFile().listFiles()?.any {
                val name = it.name.lowercase()
                name.contains("proxy-compatible") || name.contains("bungeeforge") || name.contains("neovelocity")
            } ?: false
            if (!hasProxyMod) {
                warnings.add(CompatWarning(
                    CompatWarning.Level.WARN,
                    "Group '${g.name}' (${g.config.group.software}) has no proxy forwarding mod",
                    "Players cannot connect via Velocity. Nimbus will try to auto-install on next start."
                ))
            }
        }

        // Fabric without FabricProxy-Lite
        for (g in allGroups.filter { it.config.group.software == ServerSoftware.FABRIC }) {
            val templateDir = java.nio.file.Path.of(config.paths.templates).resolve(g.config.group.template)
            val modsDir = templateDir.resolve("mods")
            val hasProxyMod = modsDir.toFile().listFiles()?.any {
                val name = it.name.lowercase()
                name.contains("fabricproxy") || name.contains("proxy-lite")
            } ?: false
            if (!hasProxyMod) {
                warnings.add(CompatWarning(
                    CompatWarning.Level.WARN,
                    "Group '${g.name}' (FABRIC) has no FabricProxy-Lite mod",
                    "Players cannot connect via Velocity. Nimbus will try to auto-install on next start."
                ))
            }
        }

        // Java version checks
        val detected = javaResolver.getDetectedVersions()
        val backendGroups = allGroups.filter { it.config.group.software != ServerSoftware.VELOCITY }

        val missingJavas = backendGroups.mapNotNull { g ->
            val min = javaResolver.requiredJavaVersion(g.config.group.version, g.config.group.software)
            val max = javaResolver.maxJavaVersion(g.config.group.version, g.config.group.software)
            val hasCompatible = detected.keys.any { it >= min && (max == null || it <= max) }
            if (!hasCompatible) {
                val range = if (max != null) "Java $min-$max" else "Java $min+"
                "${g.name} ($range needed)"
            } else null
        }

        if (missingJavas.isNotEmpty()) {
            warnings.add(CompatWarning(
                CompatWarning.Level.WARN,
                "Missing Java versions — will auto-download on first start",
                "No compatible Java found locally for: ${missingJavas.joinToString(", ")}\n" +
                "Detected: ${if (detected.isEmpty()) "none" else detected.keys.sorted().joinToString(", ") { "Java $it" }}\n" +
                "Nimbus will download the correct JDK automatically from Adoptium."
            ))
        }

        if (detected.isNotEmpty()) {
            val javaInfo = detected.entries.sortedBy { it.key }.joinToString(", ") { "Java ${it.key}" }
            warnings.add(CompatWarning(
                CompatWarning.Level.INFO,
                "Java: $javaInfo",
                ""
            ))
        }

        return warnings
    }

    data class CompatWarning(val level: Level, val title: String, val detail: String) {
        enum class Level { INFO, WARN, ERROR }
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
     * Runs Fabric server launcher once in the template dir to download vanilla server JAR
     * and create .fabric/ cache directory. This prevents each instance from re-downloading.
     */
    private suspend fun initializeFabricTemplate(templateDir: Path) {
        try {
            logger.info("Running Fabric launcher to download vanilla server...")
            val process = withContext(Dispatchers.IO) {
                ProcessBuilder("java", "-jar", "server.jar", "nogui")
                    .directory(templateDir.toFile())
                    .redirectErrorStream(true)
                    .start()
            }
            // Wait for the Fabric launcher to download vanilla server and start up
            // Once we see "Done" or eula prompt, kill it — we just needed the download
            withContext(Dispatchers.IO) {
                val reader = process.inputStream.bufferedReader()
                val startTime = System.currentTimeMillis()
                val timeout = 120_000L // 2 minutes max
                while (process.isAlive && System.currentTimeMillis() - startTime < timeout) {
                    if (reader.ready()) {
                        val line = reader.readLine() ?: break
                        // Stop once Fabric has downloaded what it needs
                        if (line.contains("You need to agree to the EULA") || line.contains("Done (") || line.contains("Stopping server")) {
                            break
                        }
                    } else {
                        Thread.sleep(200)
                    }
                }
                if (process.isAlive) process.destroyForcibly()
            }

            if (templateDir.resolve(".fabric").exists()) {
                logger.info("Fabric template initialized — vanilla server downloaded")
            } else {
                logger.warn("Fabric initialization may not have completed — .fabric/ directory not found")
            }
        } catch (e: Exception) {
            logger.error("Failed to initialize Fabric template: {}", e.message, e)
        }
    }

    /**
     * Runs Velocity once in the template dir to generate velocity.toml, forwarding.secret, etc.
     * Velocity exits after generating configs -- we wait for it to finish.
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
                // Remove Velocity's default server entries (lobby, factions, minigames, etc.)
                // Nimbus manages the [servers] section dynamically via VelocityConfigGen
                cleanDefaultVelocityServers(templateDir)
            } else {
                logger.warn("Velocity config was not generated -- proxy may fail to start")
            }
        } catch (e: Exception) {
            logger.error("Failed to initialize Velocity template: {}", e.message, e)
        }
    }

    /**
     * Removes Velocity's default server entries from velocity.toml.
     * Velocity generates default servers (lobby, factions, minigames) on first run.
     * Nimbus manages servers dynamically -- these defaults cause ghost entries and confuse the hub plugin.
     */
    private fun cleanDefaultVelocityServers(templateDir: Path) {
        val configFile = templateDir.resolve("velocity.toml")
        if (!configFile.exists()) return

        val content = configFile.readText()

        // Replace [servers] with an empty section (Nimbus fills this at runtime)
        val cleanServers = "[servers]\ntry = []\n"
        val cleanForcedHosts = "[forced-hosts]\n"

        var result = velocityConfigGen.replaceTOMLSection(content, "servers", cleanServers)
        result = velocityConfigGen.replaceTOMLSection(result, "forced-hosts", cleanForcedHosts)

        configFile.writeText(result)
        logger.info("Cleaned default server entries from Velocity template")
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
