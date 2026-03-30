package dev.nimbus.scaling

import dev.nimbus.event.EventBus
import dev.nimbus.event.NimbusEvent
import dev.nimbus.group.GroupManager
import dev.nimbus.service.ServerListPing
import dev.nimbus.service.ServiceManager
import dev.nimbus.service.ServiceRegistry
import dev.nimbus.service.ServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import dev.nimbus.stress.StressTestManager
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class ScalingEngine(
    private val registry: ServiceRegistry,
    private val serviceManager: ServiceManager,
    private val groupManager: GroupManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val checkIntervalMs: Long = 5000
) {
    private val logger = LoggerFactory.getLogger(ScalingEngine::class.java)

    /** Optional stress test manager — when set, services with active overrides skip pinging. */
    var stressTestManager: StressTestManager? = null

    /** Tracks when each service became empty (for idle timeout). Keyed by service name. */
    private val idleSince = ConcurrentHashMap<String, Instant>()

    /** Tracks consecutive zero-player readings per service to avoid acting on transient empties. */
    private val consecutiveZeroReadings = ConcurrentHashMap<String, Int>()

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
        updatePlayerCounts()

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

            val scaleUpReason = ScalingRule.shouldScaleUp(
                totalPlayers = totalPlayers,
                readyInstances = routableCount,
                maxInstances = maxInstances,
                playersPerInstance = playersPerInstance,
                scaleThreshold = scaleThreshold
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
            }

            // --- Scale Down ---
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
                    currentRoutableCount--
                }
            }
        }

        // Clean up idleSince entries for services that no longer exist (manually stopped, crashed, etc.)
        val activeServiceNames = registry.getAll().map { it.name }.toSet()
        idleSince.keys.removeAll { it !in activeServiceNames }
        consecutiveZeroReadings.keys.removeAll { it !in activeServiceNames }
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

        coroutineScope {
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
