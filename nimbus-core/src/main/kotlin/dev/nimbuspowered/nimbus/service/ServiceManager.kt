package dev.nimbuspowered.nimbus.service

import dev.nimbuspowered.nimbus.api.auth.JwtTokenManager
import dev.nimbuspowered.nimbus.cluster.NodeManager
import dev.nimbuspowered.nimbus.cluster.RemoteServiceHandle
import dev.nimbuspowered.nimbus.config.DedicatedDefinition
import dev.nimbuspowered.nimbus.config.GroupDefinition
import dev.nimbuspowered.nimbus.config.NimbusConfig
import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.module.ModuleContextImpl
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.protocol.ClusterMessage
import dev.nimbuspowered.nimbus.service.spawn.SandboxResolver
import dev.nimbuspowered.nimbus.template.PerformanceOptimizer
import dev.nimbuspowered.nimbus.template.SoftwareResolver
import dev.nimbuspowered.nimbus.template.TemplateManager
import dev.nimbuspowered.nimbus.velocity.VelocityConfigGen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private val sandboxResolver = SandboxResolver(config.sandbox)

    private val startupHelper = ServiceStartupHelper(
        config = config,
        groupManager = groupManager,
        softwareResolver = softwareResolver,
        compatibilityChecker = compatibilityChecker,
        javaResolver = javaResolver,
        performanceOptimizer = performanceOptimizer,
        sandboxResolver = sandboxResolver
    )

    private val monitor = ServiceLifecycleMonitor(
        config = config,
        registry = registry,
        scope = scope,
        eventBus = eventBus,
        groupManager = groupManager,
        portAllocator = portAllocator,
        stateStore = stateStore,
        processHandles = processHandles,
        velocityConfigGen = velocityConfigGen,
        getDedicatedServiceManager = { dedicatedServiceManager },
        onReloadVelocity = { reloadVelocity() },
        onCleanupWorkingDirectory = { cleanupWorkingDirectory(it) },
        onStartService = { startService(it) },
        onStartDedicatedService = { startDedicatedService(it) }
    )

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

        val remoteNode: dev.nimbuspowered.nimbus.cluster.NodeConnection? = when {
            syncEnabled -> {
                val node = nodeManager?.selectNode(memory)
                if (node == null && nodeManager != null) {
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
                            emitPlacementBlocked(groupName, "pinned node '${placement.node}' offline — waiting for node to reconnect")
                            return null
                        }
                    }
                }
            }
            else -> if (group?.isStatic == true) null else nodeManager?.selectNode(memory)
        }

        val prepared = warmPoolManager?.take(groupName)
            ?: serviceFactory.prepare(groupName)
            ?: return null
        val (service, workDir, command, readyPattern, isModded, readyTimeout) = prepared
        val serviceName = service.name

        clearPlacementBlocked(groupName)

        if (remoteNode != null) {
            return startRemoteService(service, prepared, remoteNode, group)
        }

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

        val stopped = stopService(serviceName, forceful = false)
        if (!stopped) {
            logger.warn("Migration stop for '{}' returned false — service may not have been running", serviceName)
        }

        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val s = registry.get(serviceName)
            if (s == null || s.state == ServiceState.STOPPED || s.state == ServiceState.CRASHED) break
            delay(500)
        }

        val started = if (targetNode != null && targetNode != "local") {
            startServiceOnNode(groupName, targetNode)
        } else {
            startService(groupName)
        }

        if (started == null) {
            logger.error("Migration start for '{}' failed", serviceName)
            return null
        }
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
                val effectiveCommand = startupHelper.applyManagedSandbox(service, command)
                plain.start(workDir, effectiveCommand, env)
                plain
            }

            processHandles[serviceName] = processHandle

            try {
                val pid = processHandle.pid() ?: 0L
                if (pid > 0) {
                    java.nio.file.Files.writeString(
                        workDir.resolve(".nimbus-owner"),
                        "pid=$pid\nservice=$serviceName\nowner=controller\nstartedAt=${System.currentTimeMillis()}\n"
                    )
                }
            } catch (_: Exception) {}

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

            monitor.launchReadyMonitor(service, processHandle, readyTimeout)
            monitor.launchExitMonitor(service, processHandle)

            service
        } catch (e: Exception) {
            logger.error("Failed to start service '{}'", serviceName, e)
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
            service.host = node.host
            service.nodeId = node.nodeId

            val startMsg = if (dedicatedConfig != null) {
                startupHelper.buildDedicatedStartServiceMessage(service, dedicatedConfig)
            } else {
                val groupConfig = group?.config?.group ?: return null
                startupHelper.buildStartServiceMessage(service, groupConfig)
            }

            val remoteHandle = RemoteServiceHandle(serviceName, node)
            val readyPattern = prepared.readyPattern
            if (readyPattern != null) {
                remoteHandle.setReadyPattern(readyPattern)
            }
            node.remoteHandles[serviceName] = remoteHandle
            processHandles[serviceName] = remoteHandle

            node.send(startMsg)

            service.transitionTo(ServiceState.STARTING)
            service.startedAt = Instant.now()
            eventBus.emit(NimbusEvent.ServiceStarting(serviceName, service.groupName, service.port, node.nodeId))

            logger.info("Service '{}' starting on remote node '{}'", serviceName, node.nodeId)

            monitor.launchReadyMonitor(service, remoteHandle, prepared.readyTimeout)
            monitor.launchExitMonitor(service, remoteHandle)

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

    private fun cleanupFailedStart(service: Service) {
        processHandles[service.name]?.destroy()
        processHandles.remove(service.name)
        portAllocator.release(service.port)
        service.bedrockPort?.let { portAllocator.releaseBedrockPort(it) }
        registry.unregister(service.name)
    }

    fun startHealthMonitor() = monitor.startHealthMonitor()

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

        if (service.state == ServiceState.DRAINING || service.state == ServiceState.STOPPING) {
            return true
        }

        if (service.state == ServiceState.CRASHED) {
            purgeService(name)
            return true
        }

        if (forceful || service.playerCount == 0 || service.state != ServiceState.READY) {
            return doStop(service)
        }

        if (!service.transitionTo(ServiceState.DRAINING)) {
            return doStop(service)
        }

        val group = groupManager.getGroup(service.groupName)
        val drainTimeout = group?.config?.group?.lifecycle?.drainTimeout ?: 30L
        logger.info("Service '{}' entering drain state ({} players, {}s timeout)", name, service.playerCount, drainTimeout)
        eventBus.emit(NimbusEvent.ServiceDraining(name, service.groupName))

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

            try {
                purgeService(name)
            } catch (e: Exception) {
                logger.warn("Failover: purge of '{}' failed: {}", name, e.message)
                continue
            }

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

        val handle = processHandles.remove(name)
        handle?.destroy()

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

        restartingGroups.add(groupName)
        try {
            stopService(name)
            withTimeoutOrNull(60_000L) {
                while (registry.get(name)?.state !in setOf(ServiceState.STOPPED, ServiceState.CRASHED, null)) {
                    delay(500)
                }
            } ?: logger.warn("Service '{}' did not stop within 60s — proceeding with restart anyway", name)
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

                monitor.launchExitMonitor(service, handle)

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

        if (backendGroups.isNotEmpty()) {
            logger.info("Startup phase 2: Starting backend group(s)...")
            startGroupsAndCollect(backendGroups)
        }

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

        val pending: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().also {
            it.addAll(services.map { svc -> svc.name })
        }
        val done = CompletableDeferred<Boolean>()

        val readyJob = eventBus.on<NimbusEvent.ServiceReady> { event ->
            pending.remove(event.serviceName)
            if (pending.isEmpty() && !done.isCompleted) done.complete(true)
        }
        val crashedJob = eventBus.on<NimbusEvent.ServiceCrashed> { event ->
            pending.remove(event.serviceName)
            if (pending.isEmpty() && !done.isCompleted) done.complete(true)
        }

        val timeoutJob = scope.launch {
            delay(timeoutMs)
            if (!done.isCompleted) done.complete(false)
        }

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

        val dedicated = allServices.filter { it.isDedicated }

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
