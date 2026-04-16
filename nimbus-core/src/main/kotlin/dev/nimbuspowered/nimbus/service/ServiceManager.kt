package dev.nimbuspowered.nimbus.service

import dev.nimbuspowered.nimbus.api.auth.JwtTokenManager
import dev.nimbuspowered.nimbus.cluster.NodeManager
import dev.nimbuspowered.nimbus.cluster.RemoteServiceHandle
import dev.nimbuspowered.nimbus.config.DedicatedDefinition
import dev.nimbuspowered.nimbus.config.GroupDefinition
import dev.nimbuspowered.nimbus.config.GroupType
import dev.nimbuspowered.nimbus.config.NimbusConfig
import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.module.ModuleContextImpl
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.protocol.ClusterMessage
import dev.nimbuspowered.nimbus.template.PerformanceOptimizer
import dev.nimbuspowered.nimbus.template.SoftwareResolver
import dev.nimbuspowered.nimbus.template.TemplateManager
import dev.nimbuspowered.nimbus.velocity.VelocityConfigGen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private val moduleContext: ModuleContextImpl? = null,
    private val stateStore: ControllerStateStore? = null,
    private val jwtTokenManager: JwtTokenManager? = null
) {

    private val logger = LoggerFactory.getLogger(ServiceManager::class.java)

    /** Warm pool manager, set after construction in Nimbus.kt. */
    var warmPoolManager: WarmPoolManager? = null

    /** Dedicated service manager, set after construction in Nimbus.kt. */
    var dedicatedServiceManager: DedicatedServiceManager? = null

    /** Groups currently being restarted — ScalingEngine should skip these. */
    val restartingGroups: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val processHandles = ConcurrentHashMap<String, ServiceHandle>()

    /** Dedupes PlacementBlocked events per (group, reason) so scaling retries don't spam. */
    private val lastPlacementBlockedReason = ConcurrentHashMap<String, String>()
    private val velocityConfigGen = VelocityConfigGen(registry, groupManager)
    private val javaResolver = JavaResolver(config.java.toMap(), Path(config.paths.templates).toAbsolutePath().parent ?: Path("."))
    private val compatibilityChecker = CompatibilityChecker(groupManager, config, javaResolver)
    private val performanceOptimizer = PerformanceOptimizer()
    private val serviceDeployer = dev.nimbuspowered.nimbus.template.ServiceDeployer()

    internal val serviceFactory = ServiceFactory(
        config = config,
        registry = registry,
        portAllocator = portAllocator,
        templateManager = templateManager,
        groupManager = groupManager,
        softwareResolver = softwareResolver,
        compatibilityChecker = compatibilityChecker,
        eventBus = eventBus,
        velocityConfigGen = velocityConfigGen,
        moduleContext = moduleContext,
        jwtTokenManager = jwtTokenManager
    )

    suspend fun startService(groupName: String): Service? {
        val group = groupManager.getGroup(groupName)
        val memory = group?.config?.group?.resources?.memory ?: "1G"
        val placement = group?.config?.group?.placement
        val syncEnabled = group?.config?.group?.sync?.enabled == true

        if (syncEnabled && !placement?.node.isNullOrBlank() && placement.node != "local") {
            logger.warn(
                "Group '{}' has both sync.enabled=true and placement.node='{}' — these are mutually exclusive. " +
                "Sync wins (service will float between nodes, canonical data on controller).",
                groupName, placement.node
            )
        }

        // Resolve placement: sync services always float (any-node), otherwise
        // explicit pin > any-node (dynamic only) > local (static fallback).
        // Pinned placement applies to BOTH static and dynamic groups.
        val remoteNode: dev.nimbuspowered.nimbus.cluster.NodeConnection? = when {
            syncEnabled -> {
                val node = nodeManager?.selectNode(memory)
                if (node == null && nodeManager != null) {
                    // Sync service wants a node but none available — block instead of
                    // silently falling back to controller-local (would diverge data).
                    emitPlacementBlocked(groupName, "sync service needs an agent node, none available")
                    return null
                }
                node
            }
            placement?.node == "local" -> null
            !placement?.node.isNullOrBlank() -> {
                val pinned = nodeManager?.getNode(placement.node)
                if (pinned != null && pinned.isConnected) {
                    pinned
                } else {
                    when (placement.fallback.lowercase()) {
                        "local" -> {
                            emitPlacementBlocked(groupName, "pinned node '${placement.node}' offline — falling back to local")
                            null
                        }
                        "fail" -> {
                            emitPlacementBlocked(groupName, "pinned node '${placement.node}' offline, fallback=fail — refusing to start")
                            return null
                        }
                        else -> {
                            // "wait" or unknown → refuse to start, scaling engine will retry later
                            emitPlacementBlocked(groupName, "pinned node '${placement.node}' offline — waiting for node to reconnect")
                            return null
                        }
                    }
                }
            }
            // No explicit pin: static → local, dynamic → any available node
            else -> if (group?.isStatic == true) null else nodeManager?.selectNode(memory)
        }

        // Try warm pool first for instant start
        val prepared = warmPoolManager?.take(groupName)
            ?: serviceFactory.prepare(groupName)
            ?: return null
        val (service, workDir, command, readyPattern, isModded, readyTimeout) = prepared
        val serviceName = service.name

        // Successful placement — clear any stale PlacementBlocked dedupe so the next
        // block for this group will be emitted fresh.
        clearPlacementBlocked(groupName)

        if (remoteNode != null) {
            return startRemoteService(service, prepared, remoteNode, group)
        }

        // Local start (single-node or controller-local)
        return startLocalService(service, prepared)
    }

    suspend fun startDedicatedService(config: DedicatedDefinition): Service? {
        val existing = registry.get(config.name)
        if (existing != null) {
            if (existing.state == ServiceState.STOPPED || existing.state == ServiceState.CRASHED) {
                registry.unregister(config.name)
            } else {
                logger.warn("Dedicated service '{}' is already running (state: {})", config.name, existing.state)
                return null
            }
        }

        // Resolve placement: dedicated services can be pinned to a node via
        // placement.node. Remote placement uses the state sync infrastructure —
        // the controller's canonical data in `dedicated/<name>/` gets pulled to
        // the agent on start, and pushed back on graceful stop.
        val placement = config.placement
        val remoteNode: dev.nimbuspowered.nimbus.cluster.NodeConnection? = when {
            placement.node.isBlank() || placement.node == "local" -> null
            else -> {
                val pinned = nodeManager?.getNode(placement.node)
                if (pinned != null && pinned.isConnected) {
                    pinned
                } else {
                    when (placement.fallback.lowercase()) {
                        "local" -> {
                            emitPlacementBlocked(config.name, "pinned node '${placement.node}' offline — running dedicated locally")
                            null
                        }
                        "fail" -> {
                            emitPlacementBlocked(config.name, "pinned node '${placement.node}' offline, fallback=fail — refusing to start")
                            return null
                        }
                        else -> {
                            emitPlacementBlocked(config.name, "pinned node '${placement.node}' offline — waiting for dedicated node")
                            return null
                        }
                    }
                }
            }
        }

        val prepared = serviceFactory.prepareDedicated(config) ?: return null
        clearPlacementBlocked(config.name)

        if (remoteNode != null) {
            return startRemoteService(prepared.service, prepared, remoteNode, null, dedicatedConfig = config)
        }
        return startLocalService(prepared.service, prepared)
    }

    /**
     * Explicit migration: stop a service on its current node, wait for the graceful
     * stop (which triggers a state-sync push for sync-enabled services) to complete,
     * then start it again. When [targetNode] is non-null, the service is pinned to
     * that node for this start; otherwise the normal placement rules run.
     *
     * The operator is responsible for this being a sync-enabled service — for
     * non-sync services, the data on the source node is lost.
     *
     * Returns the new [Service] on success, null on failure (service not found,
     * target node offline, etc.).
     */
    suspend fun migrateService(serviceName: String, targetNode: String?): Service? {
        val current = registry.get(serviceName)
        if (current == null) {
            logger.warn("Cannot migrate '{}': service not found", serviceName)
            return null
        }
        val groupName = current.groupName

        // Validate target node if specified
        if (targetNode != null && targetNode != "local") {
            val node = nodeManager?.getNode(targetNode)
            if (node == null || !node.isConnected) {
                logger.error("Cannot migrate '{}': target node '{}' is not online", serviceName, targetNode)
                return null
            }
        }

        val sourceNodeId = current.nodeId
        logger.info("Migrating '{}' from {} → {} (group={})",
            serviceName, sourceNodeId, targetNode ?: "auto-placement", groupName)

        // Stop the service. For sync-enabled services this triggers a push of the
        // current state to the controller's canonical store — so the new start
        // (on any node) pulls fresh data.
        val stopped = stopService(serviceName, forceful = false)
        if (!stopped) {
            logger.warn("Migration stop for '{}' returned false — service may not have been running", serviceName)
        }

        // Wait for the stop + push to actually complete. Poll until the service is
        // out of the registry or in a terminal state.
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val s = registry.get(serviceName)
            if (s == null || s.state == ServiceState.STOPPED || s.state == ServiceState.CRASHED) break
            delay(500)
        }

        // Temporarily pin placement via a one-shot override. Simplest path: if the
        // target is a specific node, we inject the preference by patching the
        // group's placement.node just for this start. Since groups are shared
        // state, we instead use a new one-shot placement override that
        // startServiceOnNode supports.
        val started = if (targetNode != null && targetNode != "local") {
            startServiceOnNode(groupName, targetNode)
        } else {
            startService(groupName)
        }

        if (started == null) {
            logger.error("Migration start for '{}' failed", serviceName)
            return null
        }
        // Wait until the service is actually READY on the new node before reporting
        // success — previously this logged "complete" ~40s before the service could
        // accept connections, which was confusing for operators.
        val readyDeadline = System.currentTimeMillis() + 240_000
        while (System.currentTimeMillis() < readyDeadline) {
            val live = registry.get(started.name) ?: break
            if (live.state == ServiceState.READY) break
            if (live.state == ServiceState.CRASHED || live.state == ServiceState.STOPPED) {
                logger.error("Migration start for '{}' ended in state {}", started.name, live.state)
                return null
            }
            delay(1000)
        }
        // Tell the source node to drop its cached sync workdir if the service
        // actually moved — otherwise stale copies accumulate after every migration.
        if (sourceNodeId != "local" && sourceNodeId != started.nodeId) {
            try {
                val src = nodeManager?.getNode(sourceNodeId)
                if (src != null && src.isConnected) {
                    src.send(ClusterMessage.DiscardSyncWorkdir(serviceName))
                }
            } catch (e: Exception) {
                logger.debug("Failed to send DiscardSyncWorkdir to '{}': {}", sourceNodeId, e.message)
            }
        }
        logger.info("Migration of '{}' complete: now on node {}", started.name, started.nodeId)
        return started
    }

    /**
     * One-shot placement: start a service on a specific node regardless of the
     * group's configured placement.node. Used by [migrateService].
     */
    private suspend fun startServiceOnNode(groupName: String, nodeId: String): Service? {
        val group = groupManager.getGroup(groupName) ?: return null
        val node = nodeManager?.getNode(nodeId) ?: return null
        if (!node.isConnected) return null

        val prepared = serviceFactory.prepare(groupName) ?: return null
        clearPlacementBlocked(groupName)
        return startRemoteService(prepared.service, prepared, node, group)
    }

    private suspend fun startLocalService(service: Service, prepared: ServiceFactory.PreparedService): Service? {
        val workDir = prepared.workDir
        val command = prepared.command
        val readyPattern = prepared.readyPattern
        val readyTimeout = prepared.readyTimeout
        val env = prepared.env
        val serviceName = service.name

        // If the group/dedicated config opted into Docker AND a LocalServiceHandleFactory
        // is registered and available, start the service as a container. Otherwise fall
        // back to a bare process — the default.
        val dockerFactory = if (prepared.dockerConfig != null) {
            moduleContext?.getService(LocalServiceHandleFactory::class.java)?.takeIf { it.isAvailable() }
        } else null

        var processHandle: ServiceHandle? = null
        return try {
            processHandle = if (dockerFactory != null && prepared.dockerConfig != null) {
                try {
                    dockerFactory.create(service, workDir, command, env, prepared.dockerConfig, readyPattern)
                } catch (e: Exception) {
                    logger.error("Docker-backed start failed for '{}' — falling back to process: {}", serviceName, e.message, e)
                    val fallback = ProcessHandle()
                    if (readyPattern != null) fallback.setReadyPattern(readyPattern)
                    fallback.start(workDir, command, env)
                    fallback
                }
            } else {
                val plain = ProcessHandle()
                if (readyPattern != null) plain.setReadyPattern(readyPattern)
                plain.start(workDir, command, env)
                plain
            }

            // Store handle immediately after start so cleanupFailedStart can find it
            processHandles[serviceName] = processHandle

            // Write a pid marker in the workdir so the orphan sweep can find this
            // process after an unclean controller restart on Windows, where
            // java.lang.ProcessHandle.info().commandLine() often returns empty.
            try {
                val pid = processHandle.pid() ?: 0L
                if (pid > 0) {
                    java.nio.file.Files.writeString(
                        workDir.resolve(".nimbus-owner"),
                        "pid=$pid\nservice=$serviceName\nowner=controller\nstartedAt=${System.currentTimeMillis()}\n"
                    )
                }
            } catch (_: Exception) {}

            // Persist state for crash recovery
            stateStore?.addService(PersistedLocalService(
                serviceName = serviceName,
                groupName = service.groupName,
                port = service.port,
                pid = processHandle.pid() ?: 0,
                workDir = workDir.toAbsolutePath().toString(),
                isStatic = service.isStatic,
                bedrockPort = service.bedrockPort ?: 0,
                startedAtEpochMs = System.currentTimeMillis(),
                isDedicated = service.isDedicated
            ))

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
            processHandle?.let { processHandles[serviceName] = it }
            cleanupFailedStart(service)
            null
        }
    }

    private suspend fun startRemoteService(
        service: Service,
        prepared: ServiceFactory.PreparedService,
        node: dev.nimbuspowered.nimbus.cluster.NodeConnection,
        group: dev.nimbuspowered.nimbus.group.ServerGroup?,
        dedicatedConfig: DedicatedDefinition? = null
    ): Service? {
        val serviceName = service.name

        return try {
            // Update service with remote node info
            service.host = node.host
            service.nodeId = node.nodeId

            val startMsg = if (dedicatedConfig != null) {
                buildDedicatedStartServiceMessage(service, dedicatedConfig)
            } else {
                val groupConfig = group?.config?.group ?: return null
                buildStartServiceMessage(service, groupConfig)
            }

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

            // prepare() created a controller-side workDir (services/static/<name> or
            // services/temp/<name>_xxxx) even though the service runs on the agent.
            // The canonical state lives in services/state/<name>/ for sync groups and
            // dedicated/<name>/ for dedicated, so the local prepared dir is dead weight.
            // Clean it up so the controller doesn't hoard stale copies of every remote
            // service's files (full world, plugins, etc.).
            try {
                val leftover = prepared.workDir
                if (leftover.exists()) {
                    Files.walk(leftover).sorted(Comparator.reverseOrder()).forEach(Files::delete)
                }
            } catch (e: Exception) {
                logger.debug("Failed to remove controller-side placeholder dir for remote service '{}': {}", serviceName, e.message)
            }

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
            apiToken = if (groupConfig.software == dev.nimbuspowered.nimbus.config.ServerSoftware.VELOCITY) {
                config.api.token
            } else {
                dev.nimbuspowered.nimbus.api.NimbusApi.deriveServiceToken(config.api.token)
            },
            javaVersion = javaResolver.requiredJavaVersion(groupConfig.version, groupConfig.software),
            bedrockPort = service.bedrockPort ?: 0,
            bedrockEnabled = config.bedrock.enabled && groupConfig.software == dev.nimbuspowered.nimbus.config.ServerSoftware.VELOCITY,
            // sync is only meaningful for STATIC groups — ConfigLoader warns on any
            // misconfigured non-static group and we force-disable it here.
            syncEnabled = groupConfig.sync.enabled && groupConfig.type == GroupType.STATIC,
            syncExcludes = groupConfig.sync.excludes
        )
    }

    /**
     * Builds a StartService message for a dedicated service being placed on a remote
     * node. Dedicated services have no template — the canonical data lives in
     * `dedicated/<name>/` on the controller and is transferred to the agent via the
     * state sync infrastructure. The agent must always pull (never template-copy).
     */
    private fun buildDedicatedStartServiceMessage(
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
            groupName = service.name,                // dedicated uses name as group
            port = service.port,
            templateName = "",                       // no template for dedicated
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
            syncEnabled = true,                      // dedicated ALWAYS uses sync for remote placement
            syncExcludes = dedicated.sync.excludes,
            isDedicated = true
        )
    }

    private fun resolveDedicatedJvmArgs(dedicated: DedicatedDefinition): List<String> {
        val jvm = dedicated.jvm
        if (jvm.optimize && jvm.args.isEmpty()) {
            return performanceOptimizer.aikarsFlags(dedicated.memory)
        }
        return jvm.args
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
                // If the exit monitor already handled this (process died, service
                // unregistered) skip the relabel — avoids double CRASHED logs 3 min
                // after a bind-failure startup crash.
                if (registry.get(serviceName) !== service) return@launch
                if (ready) {
                    service.transitionTo(ServiceState.READY)
                    eventBus.emit(NimbusEvent.ServiceReady(serviceName, service.groupName))
                    logger.info("Service '{}' is ready", serviceName)
                    velocityConfigGen.updateProxyServerList()
                    reloadVelocity()
                } else {
                    logger.warn("Service '{}' did not become ready within timeout — marking as CRASHED", serviceName)
                    if (service.transitionTo(ServiceState.CRASHED)) {
                        eventBus.emit(NimbusEvent.ServiceCrashed(serviceName, -1, service.restartCount))
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Normal during shutdown — don't log as error.
            } catch (e: Exception) {
                if (registry.get(serviceName) !== service) return@launch
                logger.error("Error waiting for service '{}' to become ready — marking as CRASHED", serviceName, e)
                if (service.transitionTo(ServiceState.CRASHED)) {
                    eventBus.emit(NimbusEvent.ServiceCrashed(serviceName, -1, service.restartCount))
                }
            }
        }
    }

    private fun launchExitMonitor(service: Service, handle: ServiceHandle) {
        val serviceName = service.name
        val restartOnCrash: Boolean
        val maxRestarts: Int
        if (service.isDedicated) {
            val dedicatedConfig = dedicatedServiceManager?.getConfig(serviceName)?.dedicated
            restartOnCrash = dedicatedConfig?.restartOnCrash ?: false
            maxRestarts = dedicatedConfig?.maxRestarts ?: 0
        } else {
            val group = groupManager.getGroup(service.groupName)
            restartOnCrash = group?.config?.group?.lifecycle?.restartOnCrash ?: false
            maxRestarts = group?.config?.group?.lifecycle?.maxRestarts ?: 0
        }
        scope.launch {
            try {
                monitorProcess(service, handle, service.groupName, restartOnCrash, maxRestarts)
            } catch (e: Exception) {
                logger.error("Error monitoring service '{}'", serviceName, e)
                // Clean up to prevent resource leaks when monitoring itself fails
                handle.destroy()
                processHandles.remove(serviceName)
                stateStore?.removeService(serviceName)
                portAllocator.release(service.port)
                service.bedrockPort?.let { portAllocator.releaseBedrockPort(it) }
                registry.unregister(serviceName)
                if (!service.isStatic) {
                    cleanupWorkingDirectory(service.workingDirectory)
                }
                if (service.transitionTo(ServiceState.CRASHED)) {
                    eventBus.emit(NimbusEvent.ServiceCrashed(serviceName, -1, service.restartCount))
                }
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

    /**
     * Periodically checks READY services for stale health reports.
     * If a service has not reported player counts for [serviceStaleTimeout] seconds,
     * it is marked as CRASHED. Only affects services that have ever reported health
     * (i.e., services running the SDK or on agent nodes pushing heartbeats).
     */
    fun startHealthMonitor() = scope.launch {
        val timeoutSeconds = config.controller.serviceStaleTimeout
        if (timeoutSeconds <= 0) return@launch  // disabled
        while (isActive) {
            delay(60_000) // check every 60 seconds
            val threshold = Instant.now().minusSeconds(timeoutSeconds)
            for (service in registry.getAll()) {
                if (service.state != ServiceState.READY) continue
                // Only check services that have ever reported health
                val lastReport = service.lastPlayerCountUpdate ?: continue
                if (lastReport.isBefore(threshold)) {
                    val staleSecs = java.time.Duration.between(lastReport, Instant.now()).seconds
                    logger.warn("Service '{}' has not reported health for {}s — marking as CRASHED", service.name, staleSecs)
                    if (service.transitionTo(ServiceState.CRASHED)) {
                        eventBus.emit(NimbusEvent.ServiceCrashed(service.name, -1, service.restartCount))
                    }
                }
            }
        }
    }

    private fun computeTemplateHash(templateDir: Path, software: ServerSoftware, templateStack: List<String> = emptyList()): String {
        if (!templateDir.exists()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        val templatesDir = Path(config.paths.templates)

        // Hash global templates first (must match TemplateRoutes order)
        val vanillaBased = software in listOf(ServerSoftware.PAPER, ServerSoftware.PUFFERFISH, ServerSoftware.PURPUR, ServerSoftware.LEAF, ServerSoftware.FOLIA, ServerSoftware.VELOCITY)
        if (vanillaBased) {
            hashDir(digest, templatesDir.resolve("global"))
        }
        if (software == ServerSoftware.VELOCITY) {
            hashDir(digest, templatesDir.resolve("global_proxy"))
        }

        // Hash all templates in the stack (or just the primary template)
        if (templateStack.size > 1) {
            for (tmpl in templateStack) {
                hashDir(digest, templatesDir.resolve(tmpl))
            }
        } else {
            hashDir(digest, templateDir)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hashDir(digest: MessageDigest, dir: Path) {
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
        // A service that dies before reaching READY is a crash regardless of exit code —
        // Paper/Velocity catch startup exceptions (e.g. BindException) and then System.exit(0),
        // so exit 0 alone doesn't mean "ran cleanly". Only READY → exit 0 is truly clean.
        val wasReady = service.state == ServiceState.READY
        val treatAsCrash = exitCode != 0 || !wasReady

        handle.destroy()
        processHandles.remove(serviceName)
        stateStore?.removeService(serviceName)
        portAllocator.release(service.port)
        service.bedrockPort?.let { portAllocator.releaseBedrockPort(it) }
        registry.unregister(serviceName)
        if (!service.isStatic) {
            cleanupWorkingDirectory(service.workingDirectory)
        }

        if (!treatAsCrash) {
            service.transitionTo(ServiceState.STOPPED)
            logger.info("Service '{}' exited cleanly (code 0)", serviceName)
            eventBus.emit(NimbusEvent.ServiceStopped(serviceName))
            return
        }

        // Startup crash OR non-zero exit. Guard on transitionTo so we only emit once
        // per distinct crash (it returns false if the service was already CRASHED via
        // another path — e.g. node-failure handler ran first).
        val justCrashed = service.transitionTo(ServiceState.CRASHED)
        if (justCrashed) {
            if (exitCode == 0 && !wasReady) {
                logger.warn("Service '{}' exited during startup (code 0) — treating as crash", serviceName)
            } else {
                logger.warn("Service '{}' crashed with exit code {}", serviceName, exitCode)
            }
            eventBus.emit(NimbusEvent.ServiceCrashed(serviceName, exitCode, service.restartCount))
        }

        if (restartOnCrash && service.restartCount < maxRestarts) {
            logger.info("Restarting service '{}' (attempt {}/{})", serviceName, service.restartCount + 1, maxRestarts)
            val newService = if (service.isDedicated) {
                val dedicatedConfig = dedicatedServiceManager?.getConfig(serviceName)?.dedicated
                if (dedicatedConfig != null) startDedicatedService(dedicatedConfig) else null
            } else {
                startService(groupName)
            }
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

        // CRASHED services have no live process — just purge the registry entry and
        // release resources so a subsequent start can succeed. Without this, operators
        // are stuck with a dead slot until the controller is restarted.
        if (service.state == ServiceState.CRASHED) {
            purgeService(name)
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

            // Deploy changed files back to template BEFORE transitioning to STOPPED
            // so the service directory is still intact and registered
            val group = groupManager.getGroup(service.groupName)
            if (group?.config?.group?.lifecycle?.deployOnStop == true) {
                try {
                    val templatesDir = Path(config.paths.templates)
                    val primaryTemplate = group.config.group.resolvedTemplates.firstOrNull()
                    if (primaryTemplate != null) {
                        val templateDir = templatesDir.resolve(primaryTemplate)
                        val excludes = group.config.group.lifecycle.deployExcludes
                        val changed = serviceDeployer.deployBack(service.workingDirectory, templateDir, excludes)
                        if (changed > 0) {
                            eventBus.emit(NimbusEvent.ServiceDeployed(name, service.groupName, changed))
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Deploy-back failed for '{}': {}", name, e.message)
                    service.transitionTo(ServiceState.CRASHED)
                    return false
                }
            }

            service.transitionTo(ServiceState.STOPPED)
            eventBus.emit(NimbusEvent.ServiceStopped(name))

            registry.unregister(name)
            processHandles.remove(name)
            stateStore?.removeService(name)

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

    /**
     * Called by NodeManager after a node is declared failed. For each service that
     * was running on the dead node, purge the CRASHED registry entry and start a
     * fresh instance — on another node if one is available, otherwise the group is
     * left blocked (scaling engine / operator will retry).
     *
     * This is the missing piece that made agent crashes non-recoverable for sync
     * static groups (which the scaling engine skips) and made the CRASHED slot
     * permanently block subsequent starts.
     */
    suspend fun handleNodeFailover(nodeId: String, affectedServiceNames: List<String>) {
        if (affectedServiceNames.isEmpty()) return
        logger.warn("Failover: re-placing {} service(s) from failed node '{}'", affectedServiceNames.size, nodeId)
        for (name in affectedServiceNames) {
            val service = registry.get(name) ?: continue
            val groupName = service.groupName
            val isDedicated = service.isDedicated

            val shouldRestart = if (isDedicated) {
                dedicatedServiceManager?.getConfig(name)?.dedicated?.restartOnCrash ?: true
            } else {
                groupManager.getGroup(groupName)?.config?.group?.lifecycle?.restartOnCrash ?: true
            }
            if (!shouldRestart) {
                logger.info("Failover: '{}' has restart_on_crash=false — leaving CRASHED", name)
                continue
            }

            // Purge the dead entry so the slot frees up.
            try {
                purgeService(name)
            } catch (e: Exception) {
                logger.warn("Failover: purge of '{}' failed: {}", name, e.message)
                continue
            }

            // Start a fresh instance. Dedicated services stay pinned to their config;
            // group services pick a new node via the standard placement logic.
            try {
                val started = if (isDedicated) {
                    val def = dedicatedServiceManager?.getConfig(name)?.dedicated
                    if (def != null) startDedicatedService(def) else null
                } else {
                    startService(groupName)
                }
                if (started != null) {
                    logger.info("Failover: '{}' re-placed on node '{}'", started.name, started.nodeId)
                } else {
                    logger.warn("Failover: re-placement of '{}' (group {}) failed — no capacity or blocked", name, groupName)
                }
            } catch (e: Exception) {
                logger.error("Failover: re-placement of '{}' threw", name, e)
            }
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
        stateStore?.removeService(name)

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

        // Prevent ScalingEngine from scaling up during the stop→start gap
        restartingGroups.add(groupName)
        try {
            stopService(name)
            // Wait for the service to fully stop before starting the new instance
            // to avoid port conflicts (EADDRINUSE) from overlapping processes
            withTimeoutOrNull(60_000L) {
                while (registry.get(name)?.state !in setOf(ServiceState.STOPPED, ServiceState.CRASHED, null)) {
                    delay(500)
                }
            } ?: logger.warn("Service '{}' did not stop within 60s — proceeding with restart anyway", name)
            // Static services reuse the same name automatically (lowest available = the one we just stopped)
            return startService(groupName)
        } finally {
            restartingGroups.remove(groupName)
        }
    }

    fun checkCompatibility() = compatibilityChecker.checkCompatibility()

    fun determineForwardingMode() = compatibilityChecker.determineForwardingMode()

    data class RecoveredLocalService(
        val serviceName: String,
        val groupName: String,
        val port: Int,
        val pid: Long
    )

    fun recoverLocalServices(): Pair<List<RecoveredLocalService>, Set<Path>> {
        val state = stateStore?.load() ?: return emptyList<RecoveredLocalService>() to emptySet()
        if (state.services.isEmpty()) return emptyList<RecoveredLocalService>() to emptySet()

        logger.info("Found {} persisted local service(s), attempting recovery...", state.services.size)
        val recovered = mutableListOf<RecoveredLocalService>()
        val protectedDirs = mutableSetOf<Path>()

        // Ask the Docker module (if loaded) for any containers that survived the
        // controller restart. These take precedence over PID-based adoption — the
        // old PID won't match since the container runs under a new host pid each
        // time the daemon restarts anyway.
        val dockerRecovered = runCatching {
            moduleContext?.getService(LocalServiceHandleFactory::class.java)?.recover() ?: emptyMap()
        }.getOrElse {
            logger.warn("Docker recovery probe failed: {}", it.message)
            emptyMap()
        }
        if (dockerRecovered.isNotEmpty()) {
            logger.info("Docker recovery: {} running container(s) eligible for re-adoption", dockerRecovered.size)
        }

        for (persisted in state.services) {
            val handle: ServiceHandle? = dockerRecovered[persisted.serviceName]
                ?: ProcessHandle.adopt(persisted.pid, persisted.serviceName)
            if (handle != null) {
                // Use the live handle's PID when available — for Docker-reattached
                // services the persisted PID is stale (old host-side pid from a
                // previous container run).
                val livePid = handle.pid() ?: persisted.pid
                val workDir = Path(persisted.workDir)
                val dedicatedConfig = if (persisted.isDedicated) dedicatedServiceManager?.getConfig(persisted.serviceName) else null
                val service = Service(
                    name = persisted.serviceName,
                    groupName = persisted.groupName,
                    port = persisted.port,
                    pid = livePid,
                    workingDirectory = workDir,
                    isStatic = persisted.isStatic,
                    bedrockPort = if (persisted.bedrockPort > 0) persisted.bedrockPort else null,
                    initialState = ServiceState.READY,
                    isDedicated = persisted.isDedicated,
                    proxyEnabled = dedicatedConfig?.dedicated?.proxyEnabled ?: true
                )
                service.startedAt = Instant.ofEpochMilli(persisted.startedAtEpochMs)

                registry.register(service)
                processHandles[persisted.serviceName] = handle
                portAllocator.reserve(persisted.port)
                if (persisted.bedrockPort > 0) portAllocator.reserveBedrockPort(persisted.bedrockPort)
                protectedDirs.add(workDir)

                launchExitMonitor(service, handle)

                recovered.add(RecoveredLocalService(
                    serviceName = persisted.serviceName,
                    groupName = persisted.groupName,
                    port = persisted.port,
                    pid = livePid
                ))
                logger.info("Recovered local service '{}' (PID {})", persisted.serviceName, livePid)
            } else {
                logger.info("Service '{}' (PID {}) is no longer alive — removing from state",
                    persisted.serviceName, persisted.pid)
                stateStore.removeService(persisted.serviceName)
            }
        }

        logger.info("Recovered {}/{} local service(s)", recovered.size, state.services.size)

        // Any java.exe still alive with a -Dnimbus.service.name=<X> marker that we
        // didn't successfully adopt is an orphan from a previous controller run
        // (killed without cleanly stopping its children). These hold Paper's
        // world/session.lock and will make every new spawn fail with a
        // DirectoryLock IOException until they're killed.
        killOrphanLocalBackends()

        return recovered to protectedDirs
    }

    /**
     * Kills any alive java.exe whose command line contains `-Dnimbus.service.name=`
     * and isn't currently tracked in [processHandles]. Matches only processes whose
     * command line also references this controller's services/ directory, so a
     * parallel Nimbus instance on the same host isn't affected. Called from
     * [recoverLocalServices] after adoption.
     */
    private fun killOrphanLocalBackends() {
        val adoptedPids = processHandles.values.mapNotNull { it.pid() }.toSet()
        // Walk known service workdirs for `.nimbus-owner` marker files written at
        // spawn time. Each marker records the PID + ownerNode. This is more reliable
        // than ProcessHandle.info().commandLine() which returns empty on Windows for
        // reparented processes.
        val servicesDir = Path(config.paths.services)
        val candidateRoots = listOf(
            servicesDir.resolve("static"),
            servicesDir.resolve("temp")
        ) + (dedicatedServiceManager?.let { listOf(Path(config.paths.dedicated)) } ?: emptyList())
        var scanned = 0
        var killed = 0
        for (root in candidateRoots) {
            if (!root.exists()) continue
            try {
                java.nio.file.Files.list(root).use { stream ->
                    stream.forEach { wd ->
                        val marker = wd.resolve(".nimbus-owner")
                        if (!java.nio.file.Files.exists(marker)) return@forEach
                        scanned++
                        try {
                            val lines = java.nio.file.Files.readAllLines(marker)
                                .associate { line -> line.substringBefore("=") to line.substringAfter("=", "") }
                            val pid = lines["pid"]?.toLongOrNull() ?: return@forEach
                            val markerService = lines["service"] ?: ""
                            val markerOwner = lines["owner"] ?: ""
                            if (markerOwner != "controller") return@forEach
                            if (pid in adoptedPids) return@forEach
                            val h = java.lang.ProcessHandle.of(pid).orElse(null) ?: return@forEach
                            if (!h.isAlive) {
                                try { java.nio.file.Files.delete(marker) } catch (_: Exception) {}
                                return@forEach
                            }
                            logger.warn("Killing orphan backend PID {} (service={}) from marker",
                                pid, markerService)
                            h.destroyForcibly()
                            killed++
                            try { java.nio.file.Files.delete(marker) } catch (_: Exception) {}
                        } catch (e: Exception) {
                            logger.debug("Marker parse failed for {}: {}", marker, e.message)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        if (scanned > 0 || killed > 0) {
            logger.info("Controller orphan sweep done (scanned {}, killed {})", scanned, killed)
        }
    }

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

        // Phase 3: Start all dedicated services
        val dedicatedConfigs = dedicatedServiceManager?.getAllConfigs() ?: emptyList()
        if (dedicatedConfigs.isNotEmpty()) {
            logger.info("Startup phase 3: Starting {} dedicated service(s)...", dedicatedConfigs.size)
            for (cfg in dedicatedConfigs) {
                startDedicatedService(cfg.dedicated)
            }
        }
    }

    private suspend fun startGroupsAndCollect(groups: List<dev.nimbuspowered.nimbus.group.ServerGroup>): List<Service> {
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

        // H11 fix: use thread-safe set for concurrent event listener access
        val pending: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().also {
            it.addAll(services.map { svc -> svc.name })
        }
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
        logger.info("Stopping all services (ordered: dedicated/game -> lobby -> proxy)")
        val allServices = registry.getAll()

        // Dedicated services stop first alongside game servers
        val dedicated = allServices.filter { it.isDedicated }

        // Categorize services: game servers (stopOnEmpty=true), lobbies (stopOnEmpty=false), proxies
        val proxies = allServices.filter {
            !it.isDedicated &&
                groupManager.getGroup(it.groupName)?.config?.group?.software == ServerSoftware.VELOCITY
        }
        val backends = allServices.filter {
            if (it.isDedicated) return@filter false
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

        if (dedicated.isNotEmpty()) {
            logger.info("Stopping {} dedicated service(s)...", dedicated.size)
            for (service in dedicated) {
                stopService(service.name, forceful = true)
            }
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

    /**
     * Emits a [NimbusEvent.PlacementBlocked] and logs it, deduping against the last
     * reason seen for this group so scaling retry loops don't spam the log.
     * The dedupe entry is cleared as soon as a service for the group starts successfully.
     */
    private fun emitPlacementBlocked(groupName: String, reason: String) {
        val last = lastPlacementBlockedReason[groupName]
        if (last == reason) return
        lastPlacementBlockedReason[groupName] = reason
        logger.warn("Placement blocked for group '{}': {}", groupName, reason)
        scope.launch {
            eventBus.emit(NimbusEvent.PlacementBlocked(groupName, reason))
        }
    }

    /** Clears the dedupe tracker when a group starts successfully — next block is fresh. */
    private fun clearPlacementBlocked(groupName: String) {
        lastPlacementBlockedReason.remove(groupName)
    }
}
