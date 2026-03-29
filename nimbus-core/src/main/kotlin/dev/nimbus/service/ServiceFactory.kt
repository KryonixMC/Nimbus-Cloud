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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.Path
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
    private val velocityConfigGen: VelocityConfigGen
) {

    private val logger = LoggerFactory.getLogger(ServiceFactory::class.java)
    private val configPatcher = ConfigPatcher()
    private val javaResolver = JavaResolver(config.java.toMap(), Path(config.paths.templates).toAbsolutePath().parent ?: Path("."))

    data class PreparedService(
        val service: Service,
        val workDir: Path,
        val command: List<String>,
        val readyPattern: Regex?,
        val isModded: Boolean,
        val readyTimeout: kotlin.time.Duration
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

        // Always use the lowest available instance number
        val existing = registry.getByGroup(groupName).map { it.name }.toSet()
        var instanceNumber = 1
        while ("$groupName-$instanceNumber" in existing) instanceNumber++
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
            initialState = ServiceState.PREPARING,
            workingDirectory = workingDirectory,
            isStatic = isStatic
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

        // Atomic check-and-register to prevent exceeding max instances under concurrent starts
        if (!registry.registerIfUnderLimit(service, group.maxInstances)) {
            logger.warn("Cannot start service: group '{}' reached max instances (concurrent start race avoided)", groupName)
            portAllocator.release(port)
            return null
        }
        logger.info("Preparing service '{}' on port {}", serviceName, port)

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

            val forwardingMode = compatibilityChecker.determineForwardingMode()
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

            // Inject Nimbus identity so plugins using the SDK can auto-discover their service
            command.add("-Dnimbus.service.name=$serviceName")
            command.add("-Dnimbus.service.group=$groupName")
            command.add("-Dnimbus.service.port=$port")
            if (config.api.enabled) {
                command.add("-Dnimbus.api.url=http://127.0.0.1:${config.api.port}")
                if (config.api.token.isNotBlank()) {
                    command.add("-Dnimbus.api.token=${config.api.token}")
                }
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

            val readyTimeout = if (isModded) 180.seconds else 60.seconds

            PreparedService(
                service = service,
                workDir = workDir,
                command = command,
                readyPattern = readyPattern,
                isModded = isModded,
                readyTimeout = readyTimeout
            )
        } catch (e: Exception) {
            logger.error("Failed to prepare service '{}'", serviceName, e)
            portAllocator.release(port)
            registry.unregister(serviceName)
            null
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
