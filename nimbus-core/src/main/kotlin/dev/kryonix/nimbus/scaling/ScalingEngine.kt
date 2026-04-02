package dev.kryonix.nimbus.scaling

import dev.kryonix.nimbus.event.EventBus
import dev.kryonix.nimbus.event.NimbusEvent
import dev.kryonix.nimbus.group.GroupManager
import dev.kryonix.nimbus.service.ServerListPing
import dev.kryonix.nimbus.service.ServiceManager
import dev.kryonix.nimbus.service.ServiceRegistry
import dev.kryonix.nimbus.service.ServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import dev.kryonix.nimbus.stress.StressTestManager
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ScalingEngine(
    private val registry: ServiceRegistry,
    private val serviceManager: ServiceManager,
    private val groupManager: GroupManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val checkIntervalMs: Long = 5000,
    private val globalMaxServices: Int = 0
) {
    private val logger = LoggerFactory.getLogger(ScalingEngine::class.java)

    /** Set to true during shutdown to prevent the engine from starting new services. */
    private val shuttingDown = AtomicBoolean(false)

    /** Optional stress test manager — when set, services with active overrides skip pinging. */
    var stressTestManager: StressTestManager? = null

    /** Tracks when each service became empty (for idle timeout). Keyed by service name. */
    private val idleSince = ConcurrentHashMap<String, Instant>()

    /** Tracks consecutive zero-player readings per service to avoid acting on transient empties. */
    private val consecutiveZeroReadings = ConcurrentHashMap<String, Int>()

    /** Cooldown tracking: last scale-up time per group to prevent thrashing. */
    private val lastScaleUp = ConcurrentHashMap<String, Instant>()

    /** Cooldown tracking: last scale-down time per group. */
    private val lastScaleDown = ConcurrentHashMap<String, Instant>()

    companion object {
        private const val SCALE_UP_COOLDOWN_SECONDS = 30L
        private const val SCALE_DOWN_COOLDOWN_SECONDS = 120L
    }

    /**
     * Signals the engine to stop evaluating scaling rules.
     * Must be called before stopping services to prevent the engine from
     * starting new instances to satisfy minInstances during shutdown.
     */
    fun shutdown() {
        shuttingDown.set(true)
    }

    /**
     * Starts the scaling loop. Runs periodically every [checkIntervalMs].
     * @return a [Job] that can be cancelled to stop the engine.
     */
    fun start(): Job = scope.launch {
        logger.info("Scaling engine started with check interval of ${checkIntervalMs}ms")
        while (isActive) {
            try {
                evaluate()
            } catch (e: Exception) {
                logger.error("Error during scaling evaluation", e)
            }
            delay(checkIntervalMs)
        }
    }

    /**
     * Single evaluation cycle. Updates player counts via ping and checks scaling rules
     * for every dynamic group.
     */
    private suspend fun evaluate() {
        try {
            updatePlayerCounts()
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            logger.warn("Player count update timed out (>15s), proceeding with stale data")
        }

        // Skip all scaling decisions during shutdown to prevent starting new services
        if (shuttingDown.get()) return

        // Skip scaling decisions entirely during active stress tests to avoid reacting to simulated players
        if (stressTestManager?.isActive() == true) {
            logger.debug("Stress test active — skipping scaling evaluation")
            return
        }

        for (group in groupManager.getAllGroups()) {
            if (group.isStatic) continue

            val config = group.config
            val playersPerInstance = config.group.scaling.playersPerInstance
            val scaleThreshold = config.group.scaling.scaleThreshold
            val idleTimeout = config.group.scaling.idleTimeout
            val minInstances = config.group.scaling.minInstances
            val maxInstances = config.group.scaling.maxInstances

            val services = registry.getByGroup(group.name)
            val readyServices = services.filter { it.state == ServiceState.READY }

            // Count services that are starting up — don't start more while these are pending
            val pendingCount = services.count { it.state in listOf(ServiceState.PREPARING, ServiceState.STARTING) }

            // Services with a customState (e.g. INGAME, ENDING) are not routable —
            // they can't accept new players, so exclude them from capacity calculations.
            val routableServices = readyServices.filter { it.customState == null }
            val routableCount = routableServices.size
            val totalPlayers = routableServices.sumOf { it.playerCount }

            // --- Scale Up ---
            // Don't scale up if we already have services starting
            if (pendingCount > 0) continue

            // Global hard cap: never exceed controller.max_services across all groups
            if (globalMaxServices > 0 && registry.getAll().size >= globalMaxServices) {
                logger.warn("Global service limit reached ({}) — skipping scale-up for group '{}'", globalMaxServices, group.name)
                continue
            }

            // Cooldown: skip if we recently scaled up this group
            val lastUp = lastScaleUp[group.name]
            if (lastUp != null && Duration.between(lastUp, Instant.now()).seconds < SCALE_UP_COOLDOWN_SECONDS) continue

            val scaleUpReason = ScalingRule.shouldScaleUp(
                totalPlayers = totalPlayers,
                readyInstances = routableCount,
                maxInstances = maxInstances,
                playersPerInstance = playersPerInstance,
                scaleThreshold = scaleThreshold,
                minInstances = minInstances
            )

            if (scaleUpReason != null) {
                val targetInstances = routableCount + 1
                logger.info("Scaling up group '${group.name}': $scaleUpReason")
                eventBus.emit(
                    NimbusEvent.ScaleUp(
                        groupName = group.name,
                        currentInstances = routableCount,
                        targetInstances = targetInstances,
                        reason = scaleUpReason
                    )
                )
                serviceManager.startService(group.name)
                lastScaleUp[group.name] = Instant.now()
            }

            // --- Scale Down ---
            // Cooldown: skip if we recently scaled down this group
            val lastDown = lastScaleDown[group.name]
            if (lastDown != null && Duration.between(lastDown, Instant.now()).seconds < SCALE_DOWN_COOLDOWN_SECONDS) continue
            var currentRoutableCount = routableCount
            for (service in readyServices) {
                // Never scale down a service with an active custom state (e.g. mid-game)
                if (service.customState != null) continue

                // Only consider idle if we have a recent confirmed player count (SDK report or SLP ping)
                val lastUpdate = service.lastPlayerCountUpdate
                if (lastUpdate == null || Duration.between(lastUpdate, Instant.now()).seconds > 30) {
                    idleSince.remove(service.name)
                    consecutiveZeroReadings.remove(service.name)
                    continue
                }

                if (service.playerCount > 0) {
                    // Service has players; remove from idle tracking if present
                    idleSince.remove(service.name)
                    consecutiveZeroReadings.remove(service.name)
                    continue
                }

                // Require consecutive zero readings before tracking as idle
                val zeroCount = consecutiveZeroReadings.merge(service.name, 1) { old, _ -> old + 1 } ?: 0
                if (zeroCount < 2) continue  // Skip first zero reading

                // Service is confirmed empty — track idle start
                val idleStart = idleSince.computeIfAbsent(service.name) { Instant.now() }

                val scaleDownReason = ScalingRule.shouldScaleDown(
                    servicePlayers = service.playerCount,
                    idleTimeout = idleTimeout,
                    serviceIdleSince = idleStart,
                    currentInstances = currentRoutableCount,
                    minInstances = minInstances
                )

                if (scaleDownReason != null) {
                    logger.info("Scaling down service '${service.name}' in group '${group.name}': $scaleDownReason")
                    eventBus.emit(
                        NimbusEvent.ScaleDown(
                            groupName = group.name,
                            serviceName = service.name,
                            reason = scaleDownReason
                        )
                    )
                    serviceManager.stopService(service.name)
                    idleSince.remove(service.name)
                    lastScaleDown[group.name] = Instant.now()
                    currentRoutableCount--
                }
            }
        }

        // Clean up tracking entries for services/groups that no longer exist
        val activeServiceNames = registry.getAll().map { it.name }.toSet()
        idleSince.keys.removeAll { it !in activeServiceNames }
        consecutiveZeroReadings.keys.removeAll { it !in activeServiceNames }

        val activeGroupNames = groupManager.getAllGroups().map { it.name }.toSet()
        lastScaleUp.keys.removeAll { it !in activeGroupNames }
        lastScaleDown.keys.removeAll { it !in activeGroupNames }
    }

    /**
     * Updates player counts for all READY services via [ServerListPing].
     * Pings run in parallel to avoid stalling the evaluation loop when many services exist.
     */
    private suspend fun updatePlayerCounts() {
        val readyServices = registry.getAll().filter { it.state == ServiceState.READY }
        if (readyServices.isEmpty()) return

        // Only ping local services; remote services report via heartbeat
        val localServices = readyServices.filter { it.nodeId == "local" }

        withTimeout(15_000L) {
            localServices.map { service ->
                async(Dispatchers.IO) {
                    // Skip services with simulated player counts from stress testing
                    if (stressTestManager?.isOverridden(service.name) == true) return@async

                    // Skip services that report player counts via SDK (more reliable than SLP)
                    val lastUpdate = service.lastPlayerCountUpdate
                    if (lastUpdate != null && Duration.between(lastUpdate, Instant.now()).seconds < 30) {
                        return@async
                    }

                    val result = ServerListPing.ping(service.host, service.port, timeout = 3000)
                    if (result != null) {
                        service.playerCount = result.onlinePlayers
                        service.lastPlayerCountUpdate = Instant.now()
                        logger.debug("Pinged '${service.name}': ${result.onlinePlayers}/${result.maxPlayers} players")
                    } else {
                        logger.debug("Ping failed for '${service.name}' on port ${service.port}")
                    }
                }
            }.awaitAll()
        }
    }
}
