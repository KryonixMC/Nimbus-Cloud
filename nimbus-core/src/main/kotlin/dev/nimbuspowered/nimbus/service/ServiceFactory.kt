package dev.nimbuspowered.nimbus.service

import dev.nimbuspowered.nimbus.api.NimbusApi
import dev.nimbuspowered.nimbus.api.auth.ApiScope
import dev.nimbuspowered.nimbus.api.auth.JwtTokenManager
import dev.nimbuspowered.nimbus.config.DedicatedDefinition
import dev.nimbuspowered.nimbus.config.NimbusConfig
import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.template.ConfigPatcher
import dev.nimbuspowered.nimbus.template.GeyserConfigGen
import dev.nimbuspowered.nimbus.module.ModuleContextImpl
import dev.nimbuspowered.nimbus.template.PerformanceOptimizer
import dev.nimbuspowered.nimbus.template.SoftwareResolver
import dev.nimbuspowered.nimbus.template.TemplateManager
import dev.nimbuspowered.nimbus.velocity.VelocityConfigGen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.Path
import kotlinx.coroutines.sync.withLock
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

class ServiceFactory(
    private val config: NimbusConfig,
    private val registry: ServiceRegistry,
    private val portAllocator: PortAllocator,
    private val templateManager: TemplateManager,
    private val groupManager: GroupManager,
    private val softwareResolver: SoftwareResolver,
    private val compatibilityChecker: CompatibilityChecker,
    private val eventBus: EventBus,
    private val velocityConfigGen: VelocityConfigGen,
    private val moduleContext: ModuleContextImpl? = null,
    private val jwtTokenManager: JwtTokenManager? = null
) {

    /** Late-set by Nimbus.kt after construction (circular dependency with ServiceManager). */
    var dedicatedServiceManager: DedicatedServiceManager? = null

    private val logger = LoggerFactory.getLogger(ServiceFactory::class.java)
    private val configPatcher = ConfigPatcher()
    private val performanceOptimizer = PerformanceOptimizer()

    /**
     * Serializes slot allocation across concurrent [prepare] calls. Without this, two
     * simultaneous scale-ups could both see `Lobby-1` in CRASHED state and one would
     * reuse the slot while the other allocates a fresh `Lobby-2`, breaking name
     * stability. Held only during name resolution + placeholder registration — the
     * expensive prepare work happens outside the critical section.
     */
    private val slotAllocationMutex = kotlinx.coroutines.sync.Mutex()

    private companion object {
        val MODDED_SOFTWARE = setOf(ServerSoftware.FORGE, ServerSoftware.NEOFORGE, ServerSoftware.FABRIC)
    }

    /** Checks if any configured group uses modded server software. */
    private fun hasModdedBackends(): Boolean {
        return groupManager.getAllGroups().any { it.config.group.software in MODDED_SOFTWARE }
    }
    private val geyserConfigGen = GeyserConfigGen()
    private val javaResolver = JavaResolver(config.java.toMap(), Path(config.paths.templates).toAbsolutePath().parent ?: Path("."))

    data class PreparedService(
        val service: Service,
        val workDir: Path,
        val command: List<String>,
        val readyPattern: Regex?,
        val isModded: Boolean,
        val readyTimeout: kotlin.time.Duration,
        val env: Map<String, String> = emptyMap()
    )

    suspend fun prepare(groupName: String): PreparedService? {
        val group = groupManager.getGroup(groupName)
        if (group == null) {
            logger.warn("Cannot start service: group '{}' not found", groupName)
            return null
        }

        // Early check (non-atomic) for fast rejection — atomic check happens at register time
        val currentCount = registry.countByGroup(groupName)
        if (currentCount >= group.maxInstances) {
            logger.warn("Cannot start service: group '{}' already at max instances ({}/{})", groupName, currentCount, group.maxInstances)
            return null
        }

        val software = group.config.group.software

        // Atomic slot allocation: reuse the lowest-numbered terminal slot if one exists,
        // else allocate a fresh number. The lock prevents two concurrent prepare() calls
        // (scaling engine + migration, or two overlapping scale-ups) from both picking
        // the same name or both reusing the same slot.
        val isStatic = group.isStatic
        val port = if (software == ServerSoftware.VELOCITY) {
            portAllocator.allocateProxyPort()
        } else {
            portAllocator.allocateBackendPort()
        }
        val templatesDir = Path(config.paths.templates)
        val servicesDir = Path(config.paths.services)

        val allocation = slotAllocationMutex.withLock {
            // Atomic max-instances check inside the lock (registry.countByGroup counts
            // active + terminal, but we exclude terminal since those will be reused).
            val active = registry.getByGroup(groupName)
                .count { it.state != ServiceState.CRASHED && it.state != ServiceState.STOPPED }
            if (active >= group.maxInstances) {
                logger.warn("Cannot start service: group '{}' reached max instances ({}/{})",
                    groupName, active, group.maxInstances)
                null
            } else {
                val reusable = registry.getByGroup(groupName)
                    .filter { it.state == ServiceState.CRASHED || it.state == ServiceState.STOPPED }
                    .minByOrNull { it.name.substringAfterLast('-').toIntOrNull() ?: Int.MAX_VALUE }

                val name: String
                if (reusable != null) {
                    logger.info("Reusing service slot '{}' (was {})", reusable.name, reusable.state)
                    registry.unregister(reusable.name)
                    name = reusable.name
                } else {
                    // No reusable slot — allocate the lowest fresh instance number
                    val existing = registry.getByGroup(groupName).map { it.name }.toSet()
                    var instanceNumber = 1
                    while ("$groupName-$instanceNumber" in existing) instanceNumber++
                    name = "$groupName-$instanceNumber"
                }

                val workingDirectory = if (isStatic) {
                    servicesDir.resolve("static").resolve(name)
                } else {
                    val shortUuid = UUID.randomUUID().toString().replace("-", "").take(8)
                    servicesDir.resolve("temp").resolve("${name}_$shortUuid")
                }

                val svc = Service(
                    name = name,
                    groupName = groupName,
                    port = port,
                    initialState = ServiceState.PREPARING,
                    workingDirectory = workingDirectory,
                    isStatic = isStatic
                )
                // Register immediately inside the lock so the next concurrent prepare()
                // sees this slot as taken and picks a different one.
                registry.register(svc)
                name to svc
            }
        }
        if (allocation == null) {
            portAllocator.release(port)
            return null
        }
        val (serviceName, service) = allocation

        // Ensure template directories exist and JAR is available (auto-download/install if missing)
        val resolvedTemplates = group.config.group.resolvedTemplates
        val primaryTemplate = resolvedTemplates.firstOrNull() ?: group.name.lowercase()
        val templateDir = templatesDir.resolve(primaryTemplate)
        if (!templateDir.exists()) {
            templateDir.createDirectories()
        }
        // Ensure overlay template directories exist
        for (tmpl in resolvedTemplates.drop(1)) {
            val overlayDir = templatesDir.resolve(tmpl)
            if (!overlayDir.exists()) overlayDir.createDirectories()
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

        // Sync proxy forwarding mods on the template (installs the correct mod for
        // the current software, removes any stale mods from other modloaders in case
        // the group's software was changed). Groups always run behind the proxy.
        syncProxyForwardingMods(templateDir, software, group.config.group.version, proxyEnabled = true)

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

        // Registration already done inside the slot-allocation lock above — nothing
        // else to do here. Concurrent callers that lost the race were rejected there.
        logger.info("Preparing service '{}' on port {}", serviceName, port)

        return try {
            val workDir = templateManager.prepareServiceFromStack(
                templateNames = resolvedTemplates,
                targetDir = service.workingDirectory,
                templatesDir = templatesDir,
                preserveExisting = isStatic
            )

            // Apply global templates (always overwrite, even for static services)
            val isPaperBased = software in listOf(ServerSoftware.PAPER, ServerSoftware.PUFFERFISH, ServerSoftware.PURPUR, ServerSoftware.LEAF, ServerSoftware.FOLIA)
            if (isPaperBased) {
                templateManager.applyGlobalTemplate(templatesDir.resolve("global"), workDir)
            }
            if (software == ServerSoftware.VELOCITY) {
                templateManager.applyGlobalTemplate(templatesDir.resolve("global_proxy"), workDir)
            }

            // Deploy module-dependent plugins based on active modules and software compatibility
            resolveModulePlugins(software, group.config.group.version, workDir, serviceName)

            val forwardingMode = compatibilityChecker.determineForwardingMode()
            when (software) {
                ServerSoftware.VELOCITY -> {
                    configPatcher.patchVelocityConfig(workDir, port, forwardingMode, bedrockEnabled = config.bedrock.enabled)

                    // Generate Geyser config for Bedrock support
                    if (config.bedrock.enabled) {
                        val bedrockPort = portAllocator.allocateBedrockPort()
                        service.bedrockPort = bedrockPort
                        geyserConfigGen.generateGeyserConfig(workDir, bedrockPort, port)
                        logger.info("Bedrock enabled for '{}' on UDP port {}", serviceName, bedrockPort)
                    }
                }
                else -> {
                    configPatcher.patchServerProperties(workDir, port, bedrockEnabled = config.bedrock.enabled)

                    val isPaperBased = software in listOf(ServerSoftware.PAPER, ServerSoftware.PUFFERFISH, ServerSoftware.PURPUR, ServerSoftware.LEAF, ServerSoftware.FOLIA)
                    val velocityTemplateDir = templatesDir.resolve("proxy")

                    if (isPaperBased) {
                        if (forwardingMode == "modern") {
                            val minor = group.config.group.version.split(".").getOrNull(1)?.toIntOrNull() ?: 99
                            if (minor >= 13 && velocityTemplateDir.resolve("forwarding.secret").exists()) {
                                configPatcher.patchPaperForVelocity(workDir, velocityTemplateDir)
                            } else if (minor >= 13) {
                                logger.warn("Velocity forwarding.secret not found — '{}' will not have modern forwarding configured. Start the proxy group first.", serviceName)
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

            // Apply performance optimizations to server configs (spigot.yml, paper-world-defaults.yml)
            if (group.config.group.jvm.optimize) {
                performanceOptimizer.optimizeServerConfigs(workDir, software)
            }

            val memory = group.config.group.resources.memory
            val jvmConfig = group.config.group.jvm
            val javaBin = javaResolver.resolve(group.config.group.version, software, group.config.group.javaPath)
            val requiredJava = javaResolver.requiredJavaVersion(group.config.group.version, software)
            logger.info("Service '{}' using Java {} ({})", serviceName, requiredJava, javaBin)
            val command = mutableListOf(javaBin, "-Xmx$memory")

            // Apply Aikar's optimized JVM flags or user-specified args
            if (jvmConfig.optimize && jvmConfig.args.isEmpty()) {
                command.addAll(performanceOptimizer.aikarsFlags(memory))
                logger.debug("Applied Aikar's JVM flags for '{}'", serviceName)
            } else {
                command.addAll(jvmConfig.args)
            }

            // Inject Nimbus identity so plugins using the SDK can auto-discover their service
            command.add("-Dnimbus.service.name=$serviceName")
            command.add("-Dnimbus.service.group=$groupName")
            command.add("-Dnimbus.service.port=$port")
            // Pass API URL as system property (non-sensitive), token via env var (hidden from ps)
            val processEnv = mutableMapOf<String, String>()
            if (config.api.enabled) {
                command.add("-Dnimbus.api.url=http://127.0.0.1:${config.api.port}")
                if (config.api.token.isNotBlank()) {
                    // Proxy/bridge gets the full admin token (needs broad API access for /cloud commands).
                    // Game servers get a derived service token with restricted API access
                    // (no config changes, file access, stress tests, or cluster management).
                    val token = if (software == ServerSoftware.VELOCITY) {
                        if (jwtTokenManager != null) {
                            jwtTokenManager.generateToken(serviceName, ApiScope.PROXY_SCOPES, expiresInSeconds = 86400 * 7)
                        } else {
                            config.api.token
                        }
                    } else {
                        if (jwtTokenManager != null) {
                            jwtTokenManager.generateToken(serviceName, ApiScope.SERVICE_SCOPES, expiresInSeconds = 86400 * 7)
                        } else {
                            NimbusApi.deriveServiceToken(config.api.token)
                        }
                    }
                    processEnv["NIMBUS_API_TOKEN"] = token
                }
            }

            // Tell proxy services whether the load balancer is active so they can block direct connections
            if (software == ServerSoftware.VELOCITY && config.loadbalancer.enabled) {
                command.add("-Dnimbus.loadbalancer.enabled=true")
            }

            // Auto-set max-known-packs for Velocity when modded backends exist (default 64 is too low for modpacks)
            if (software == ServerSoftware.VELOCITY && hasModdedBackends()) {
                command.add("-Dvelocity.max-known-packs=512")
                logger.info("Set velocity.max-known-packs=512 for modded backend support")
            }

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
                    val flag = compatibilityChecker.noguiFlag(group.config.group.version)
                    if (flag != null) command.add(flag)
                }
            }

            // Determine ready pattern
            val customPattern = group.config.group.readyPattern
            val readyPattern = when {
                customPattern.isNotEmpty() -> Regex(customPattern)
                software == ServerSoftware.FORGE || software == ServerSoftware.NEOFORGE -> Regex("""Done \(|For help, type""")
                else -> null
            }

            val readyTimeout = if (isModded) 240.seconds else 180.seconds

            PreparedService(
                service = service,
                workDir = workDir,
                command = command,
                readyPattern = readyPattern,
                isModded = isModded,
                readyTimeout = readyTimeout,
                env = processEnv
            )
        } catch (e: Exception) {
            logger.error("Failed to prepare service '{}'", serviceName, e)
            portAllocator.release(port)
            service.bedrockPort?.let { portAllocator.releaseBedrockPort(it) }
            registry.unregister(serviceName)
            null
        }
    }

    /**
     * Installs the correct proxy forwarding mod for the given software and removes
     * any mods belonging to OTHER modloaders (handles software changes, e.g. Forge→Fabric).
     * Operates on whatever directory is passed — template dir for groups, service dir
     * for dedicated services.
     *
     * No-op for non-modded software. If [proxyEnabled] is false, all proxy forwarding
     * mods are removed regardless of software.
     */
    suspend fun syncProxyForwardingMods(
        dir: Path,
        software: ServerSoftware,
        version: String,
        proxyEnabled: Boolean
    ) {
        // Cleanup stale mods from other modloaders (e.g. group software changed)
        if (software != ServerSoftware.FABRIC) {
            softwareResolver.removeFabricProxyMod(dir)
        }
        if (software !in setOf(ServerSoftware.FORGE, ServerSoftware.NEOFORGE)) {
            softwareResolver.removeForwardingMod(dir)
        }

        if (!proxyEnabled) {
            // Ensure everything is gone if proxy is disabled
            softwareResolver.removeFabricProxyMod(dir)
            softwareResolver.removeForwardingMod(dir)
            return
        }

        // Install the correct forwarding mod for the current software
        when (software) {
            ServerSoftware.FABRIC -> softwareResolver.ensureFabricProxyMod(dir, version)
            ServerSoftware.FORGE, ServerSoftware.NEOFORGE -> softwareResolver.ensureForwardingMod(software, version, dir)
            else -> {} // paper/velocity/etc. don't need forwarding mods
        }
    }

    /**
     * Dedicated-specific: syncs both the forwarding mods AND the server-side forwarding
     * config file (neoforwarding-server.toml / proxy-compatible-forge-server.toml /
     * FabricProxy-Lite.toml) with the current Velocity secret.
     * Used by both [prepareDedicated] (start-time sync) and the edit REST endpoint
     * (immediate sync when the proxyEnabled flag is toggled).
     */
    suspend fun syncDedicatedProxyForwarding(
        workDir: Path,
        software: ServerSoftware,
        version: String,
        proxyEnabled: Boolean
    ) {
        syncProxyForwardingMods(workDir, software, version, proxyEnabled)

        if (proxyEnabled && software in setOf(ServerSoftware.FORGE, ServerSoftware.NEOFORGE, ServerSoftware.FABRIC)) {
            val velocityTemplateDir = Path(config.paths.templates).resolve("proxy")
            val forwardingMode = compatibilityChecker.determineForwardingMode()
            when (software) {
                ServerSoftware.FABRIC -> configPatcher.patchFabricProxyLite(workDir, velocityTemplateDir, forwardingMode)
                ServerSoftware.FORGE, ServerSoftware.NEOFORGE -> configPatcher.patchForgeProxy(workDir, velocityTemplateDir, forwardingMode)
                else -> {}
            }
        }
    }

    suspend fun prepareDedicated(config: DedicatedDefinition): PreparedService? {
        val serviceName = config.name
        val port = config.port
        val software = config.software

        val manager = dedicatedServiceManager
        if (manager == null) {
            logger.error("Cannot start dedicated service '{}': DedicatedServiceManager not wired", serviceName)
            return null
        }

        val workDir = manager.ensureServiceDirectory(serviceName, software)

        if (!portAllocator.reserveIfAvailable(port)) {
            logger.error("Cannot start dedicated service '{}': port {} is already in use", serviceName, port)
            return null
        }

        // Auto-download server JAR if missing
        val jarAvailable = softwareResolver.ensureJarAvailable(software, config.version, workDir, customJarName = config.jarName)
        if (!jarAvailable) {
            logger.error("Cannot start dedicated service '{}': server JAR could not be prepared", serviceName)
            portAllocator.release(port)
            return null
        }

        // Ensure proxy forwarding mods + config match proxyEnabled for modded servers
        syncDedicatedProxyForwarding(workDir, software, config.version, config.proxyEnabled)

        val service = Service(
            name = serviceName,
            groupName = serviceName,
            port = port,
            initialState = ServiceState.PREPARING,
            workingDirectory = workDir,
            isStatic = true,
            isDedicated = true,
            proxyEnabled = config.proxyEnabled
        )

        registry.register(service)
        logger.info("Preparing dedicated service '{}' on port {}", serviceName, port)

        return try {
            val memory = config.memory
            val jvmConfig = config.jvm
            val javaBin = javaResolver.resolve(config.version, software, config.javaPath)
            val requiredJava = javaResolver.requiredJavaVersion(config.version, software)
            logger.info("Dedicated service '{}' using Java {} ({})", serviceName, requiredJava, javaBin)
            val command = mutableListOf(javaBin, "-Xmx$memory")

            if (jvmConfig.optimize && jvmConfig.args.isEmpty()) {
                command.addAll(performanceOptimizer.aikarsFlags(memory))
                logger.debug("Applied Aikar's JVM flags for '{}'", serviceName)
            } else {
                command.addAll(jvmConfig.args)
            }

            command.add("-Dnimbus.service.name=$serviceName")
            command.add("-Dnimbus.service.group=$serviceName")
            command.add("-Dnimbus.service.port=$port")
            command.add("-Dnimbus.service.dedicated=true")

            val processEnv = mutableMapOf<String, String>()
            if (this.config.api.enabled) {
                command.add("-Dnimbus.api.url=http://127.0.0.1:${this.config.api.port}")
                if (this.config.api.token.isNotBlank()) {
                    val token = if (jwtTokenManager != null) {
                        jwtTokenManager.generateToken(serviceName, ApiScope.SERVICE_SCOPES, expiresInSeconds = 86400 * 7)
                    } else {
                        NimbusApi.deriveServiceToken(this.config.api.token)
                    }
                    processEnv["NIMBUS_API_TOKEN"] = token
                }
            }

            val isModded = software in listOf(ServerSoftware.FORGE, ServerSoftware.NEOFORGE, ServerSoftware.FABRIC, ServerSoftware.CUSTOM)
            if (isModded) {
                val startArgs = softwareResolver.getModdedStartCommand(software, workDir, config.jarName)
                command.addAll(startArgs)
            } else {
                command.add("-jar")
                val jarName = if (config.jarName.isNotEmpty()) config.jarName else softwareResolver.jarFileName(software)
                command.add(jarName)
            }

            if (software != ServerSoftware.VELOCITY) {
                if (isModded) {
                    command.add("nogui")
                } else {
                    val flag = compatibilityChecker.noguiFlag(config.version)
                    if (flag != null) command.add(flag)
                }
            }

            val customPattern = config.readyPattern
            val readyPattern = when {
                customPattern.isNotEmpty() -> Regex(customPattern)
                software == ServerSoftware.FORGE || software == ServerSoftware.NEOFORGE -> Regex("""Done \(|For help, type""")
                else -> null
            }

            val readyTimeout = if (isModded) 240.seconds else 180.seconds

            PreparedService(
                service = service,
                workDir = workDir,
                command = command,
                readyPattern = readyPattern,
                isModded = isModded,
                readyTimeout = readyTimeout,
                env = processEnv
            )
        } catch (e: Exception) {
            logger.error("Failed to prepare dedicated service '{}'", serviceName, e)
            portAllocator.release(port)
            registry.unregister(serviceName)
            null
        }
    }

    /**
     * Deploys module-registered plugins to a service's working directory based on
     * registered [PluginDeployment]s and the service's software compatibility.
     */
    private suspend fun resolveModulePlugins(software: ServerSoftware, version: String, workDir: Path, serviceName: String) {
        val deployments = moduleContext?.pluginDeployments
        if (deployments.isNullOrEmpty() || software == ServerSoftware.VELOCITY) return

        val isPaperBased = software in listOf(ServerSoftware.PAPER, ServerSoftware.PURPUR, ServerSoftware.PUFFERFISH, ServerSoftware.LEAF, ServerSoftware.FOLIA)
        if (!isPaperBased) return

        val pluginsDir = workDir.resolve("plugins")
        if (!pluginsDir.exists()) pluginsDir.createDirectories()

        val minor = version.split(".").getOrNull(1)?.toIntOrNull() ?: 0
        var needsPacketEvents = false

        for (deployment in deployments) {
            // Skip plugins that require a newer Minecraft version
            val minVersion = deployment.minMinecraftVersion
            if (minVersion != null && minor < minVersion) {
                logger.info("Service '{}': {} skipped (requires 1.{}+, got {})", serviceName, deployment.displayName, minVersion, version)
                continue
            }

            deployResourcePlugin(pluginsDir, deployment.fileName, deployment.resourcePath)

            // Track if any deployed plugin needs PacketEvents on Folia
            if (deployment.foliaRequiresPacketEvents && software == ServerSoftware.FOLIA) {
                needsPacketEvents = true
            }
        }

        if (needsPacketEvents) {
            softwareResolver.ensurePacketEventsPlugin(pluginsDir, version)
        }
    }

    private fun deployResourcePlugin(pluginsDir: Path, fileName: String, resourcePath: String) {
        val target = pluginsDir.resolve(fileName)
        val resource = javaClass.classLoader.getResourceAsStream(resourcePath) ?: return
        resource.use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
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

}
