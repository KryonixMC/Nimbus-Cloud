package dev.nimbuspowered.nimbus.service

import dev.nimbuspowered.nimbus.config.DedicatedDefinition
import dev.nimbuspowered.nimbus.config.GroupDefinition
import dev.nimbuspowered.nimbus.config.GroupType
import dev.nimbuspowered.nimbus.config.NimbusConfig
import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.protocol.ClusterMessage
import dev.nimbuspowered.nimbus.service.spawn.SandboxMode
import dev.nimbuspowered.nimbus.service.spawn.SandboxResolver
import dev.nimbuspowered.nimbus.service.spawn.SystemdRunSandbox
import dev.nimbuspowered.nimbus.template.PerformanceOptimizer
import dev.nimbuspowered.nimbus.template.SoftwareResolver
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import kotlin.io.path.exists

internal class ServiceStartupHelper(
    private val config: NimbusConfig,
    private val groupManager: GroupManager,
    private val softwareResolver: SoftwareResolver,
    private val compatibilityChecker: CompatibilityChecker,
    private val javaResolver: JavaResolver,
    private val performanceOptimizer: PerformanceOptimizer,
    private val sandboxResolver: SandboxResolver
) {
    private val logger = LoggerFactory.getLogger(ServiceStartupHelper::class.java)

    private val sandboxFallbackWarned = ConcurrentHashMap.newKeySet<String>()

    fun applyManagedSandbox(service: Service, command: List<String>): List<String> {
        val group = groupManager.getGroup(service.groupName) ?: return command
        val groupDef = group.config.group
        val resolved = sandboxResolver.resolve(service.name, groupDef.sandbox, groupDef.resources)
        when (resolved.mode) {
            SandboxMode.BARE -> return command
            SandboxMode.DOCKER -> {
                if (sandboxFallbackWarned.add("${service.groupName}:docker")) {
                    logger.warn(
                        "Group '{}' has [group.sandbox] mode = \"docker\" but the Docker module is not loaded " +
                                "(or [group.docker] enabled = true is missing). Falling back to bare process — no kernel enforcement.",
                        service.groupName
                    )
                }
                return command
            }
            SandboxMode.MANAGED -> {
                val wrapped = SystemdRunSandbox.wrapCommand(service.name, command, resolved.limits)
                if (wrapped === command &&
                    sandboxFallbackWarned.add("${service.groupName}:managed-no-limits")
                ) {
                    logger.warn(
                        "Group '{}' resolved to sandbox mode = \"managed\" but every limit derives to 0 " +
                                "(memory_limit_mb={}, cpu_quota={}, tasks_max={}). Spawning without systemd-run wrapping — " +
                                "no kernel enforcement active. Check [group.resources] memory parses and the global " +
                                "[sandbox] overhead defaults.",
                        service.groupName,
                        resolved.limits.memoryMb,
                        resolved.limits.cpuQuota,
                        resolved.limits.tasksMax
                    )
                }
                return wrapped
            }
        }
    }

    fun buildStartServiceMessage(
        service: Service,
        groupConfig: GroupDefinition
    ): ClusterMessage.StartService {
        val templatesDir = Path(config.paths.templates)
        val resolvedTemplates = groupConfig.resolvedTemplates
        val primaryTemplate = resolvedTemplates.firstOrNull() ?: groupConfig.name.lowercase()
        val templateDir = templatesDir.resolve(primaryTemplate)
        val templateHash = computeTemplateHash(templateDir, groupConfig.software, resolvedTemplates)
        val forwardingMode = compatibilityChecker.determineForwardingMode()
        val forwardingSecret = computeForwardingSecret()
        val isModded = groupConfig.software in listOf(
            ServerSoftware.FORGE, ServerSoftware.NEOFORGE, ServerSoftware.FABRIC
        )

        return ClusterMessage.StartService(
            serviceName = service.name,
            groupName = service.groupName,
            port = service.port,
            templateName = primaryTemplate,
            templateNames = resolvedTemplates,
            templateHash = templateHash,
            software = groupConfig.software.name,
            version = groupConfig.version,
            memory = groupConfig.resources.memory,
            jvmArgs = resolveJvmArgs(groupConfig),
            jvmOptimize = groupConfig.jvm.optimize,
            jarName = softwareResolver.jarFileName(groupConfig.software),
            modloaderVersion = groupConfig.modloaderVersion,
            readyPattern = groupConfig.readyPattern,
            readyTimeoutSeconds = if (isModded) 240 else 180,
            forwardingMode = forwardingMode,
            forwardingSecret = forwardingSecret,
            isStatic = service.isStatic,
            isModded = isModded,
            apiUrl = if (config.api.enabled) "http://${config.api.bind}:${config.api.port}" else "",
            apiToken = if (groupConfig.software == ServerSoftware.VELOCITY) {
                config.api.token
            } else {
                dev.nimbuspowered.nimbus.api.NimbusApi.deriveServiceToken(config.api.token)
            },
            javaVersion = javaResolver.requiredJavaVersion(groupConfig.version, groupConfig.software),
            bedrockPort = service.bedrockPort ?: 0,
            bedrockEnabled = config.bedrock.enabled && groupConfig.software == ServerSoftware.VELOCITY,
            syncEnabled = groupConfig.sync.enabled && groupConfig.type == GroupType.STATIC,
            syncExcludes = groupConfig.sync.excludes
        )
    }

    fun buildDedicatedStartServiceMessage(
        service: Service,
        dedicated: DedicatedDefinition
    ): ClusterMessage.StartService {
        val isModded = dedicated.software in listOf(
            ServerSoftware.FORGE, ServerSoftware.NEOFORGE, ServerSoftware.FABRIC
        )
        val forwardingMode = compatibilityChecker.determineForwardingMode()
        val forwardingSecret = computeForwardingSecret()

        return ClusterMessage.StartService(
            serviceName = service.name,
            groupName = service.name,
            port = service.port,
            templateName = "",
            templateNames = emptyList(),
            templateHash = "",
            software = dedicated.software.name,
            version = dedicated.version,
            memory = dedicated.memory,
            jvmArgs = resolveDedicatedJvmArgs(dedicated),
            jvmOptimize = dedicated.jvm.optimize,
            jarName = dedicated.jarName.ifBlank { softwareResolver.jarFileName(dedicated.software) },
            modloaderVersion = "",
            readyPattern = dedicated.readyPattern,
            readyTimeoutSeconds = if (isModded) 240 else 180,
            forwardingMode = forwardingMode,
            forwardingSecret = forwardingSecret,
            isStatic = true,
            isModded = isModded,
            customJarName = dedicated.jarName,
            apiUrl = if (config.api.enabled) "http://${config.api.bind}:${config.api.port}" else "",
            apiToken = dev.nimbuspowered.nimbus.api.NimbusApi.deriveServiceToken(config.api.token),
            javaVersion = javaResolver.requiredJavaVersion(dedicated.version, dedicated.software),
            bedrockPort = 0,
            bedrockEnabled = false,
            syncEnabled = true,
            syncExcludes = dedicated.sync.excludes,
            isDedicated = true
        )
    }

    fun resolveDedicatedJvmArgs(dedicated: DedicatedDefinition): List<String> {
        val jvm = dedicated.jvm
        if (jvm.optimize && jvm.args.isEmpty()) {
            return performanceOptimizer.aikarsFlags(dedicated.memory)
        }
        return jvm.args
    }

    fun resolveJvmArgs(groupConfig: GroupDefinition): List<String> {
        val jvm = groupConfig.jvm
        if (jvm.optimize && jvm.args.isEmpty()) {
            return performanceOptimizer.aikarsFlags(groupConfig.resources.memory)
        }
        return jvm.args
    }

    fun computeForwardingSecret(): String {
        return try {
            val secretFile = Path(config.paths.templates).resolve("proxy").resolve("forwarding.secret")
            if (secretFile.exists()) secretFile.toFile().readText().trim() else ""
        } catch (_: Exception) { "" }
    }

    fun computeTemplateHash(templateDir: Path, software: ServerSoftware, templateStack: List<String> = emptyList()): String {
        if (!templateDir.exists()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        val templatesDir = Path(config.paths.templates)

        val vanillaBased = software in listOf(ServerSoftware.PAPER, ServerSoftware.PUFFERFISH, ServerSoftware.PURPUR, ServerSoftware.LEAF, ServerSoftware.FOLIA, ServerSoftware.VELOCITY)
        if (vanillaBased) {
            hashDir(digest, templatesDir.resolve("global"))
        }
        if (software == ServerSoftware.VELOCITY) {
            hashDir(digest, templatesDir.resolve("global_proxy"))
        }

        if (templateStack.size > 1) {
            for (tmpl in templateStack) {
                hashDir(digest, templatesDir.resolve(tmpl))
            }
        } else {
            hashDir(digest, templateDir)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun hashDir(digest: MessageDigest, dir: Path) {
        if (!dir.exists()) return
        val buf = ByteArray(64 * 1024)
        Files.walk(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                digest.update(dir.relativize(file).toString().toByteArray())
                Files.newInputStream(file).use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        digest.update(buf, 0, n)
                    }
                }
            }
        }
    }
}
