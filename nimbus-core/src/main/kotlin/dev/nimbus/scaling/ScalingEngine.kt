package dev.nimbus.scaling

import dev.nimbus.event.EventBus
import dev.nimbus.event.NimbusEvent
import dev.nimbus.group.GroupManager
import dev.nimbus.service.ServerListPing
import dev.nimbus.service.ServiceManager
import dev.nimbus.service.ServiceRegistry
import dev.nimbus.service.ServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
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

    /** Tracks when each service became empty (for idle timeout). Keyed by service name. */
    private val idleSince = ConcurrentHashMap<String, Instant>()

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

            // Services with a customState (e.g. INGAME, ENDING) are not routable —
            // they can't accept new players, so exclude them from capacity calculations.
            val routableServices = readyServices.filter { it.customState == null }
            val routableCount = routableServices.size
            val totalPlayers = routableServices.sumOf { it.playerCount }

            // --- Scale Up ---
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
            for (service in readyServices) {
                // Never scale down a service with an active custom state (e.g. mid-game)
                if (service.customState != null) continue

                if (service.playerCount > 0) {
                    // Service has players; remove from idle tracking if present
                    idleSince.remove(service.name)
                    continue
                }

                // Service is empty — track idle start if not already tracked
                val idleStart = idleSince.computeIfAbsent(service.name) { Instant.now() }

                val scaleDownReason = ScalingRule.shouldScaleDown(
                    servicePlayers = service.playerCount,
                    idleTimeout = idleTimeout,
                    serviceIdleSince = idleStart,
                    currentInstances = routableCount,
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
                }
            }
        }
    }

    /**
     * Updates player counts for all READY services via [ServerListPing].
     */
    private fun updatePlayerCounts() {
        for (service in registry.getAll()) {
            if (service.state != ServiceState.READY) continue

            val result = ServerListPing.ping("127.0.0.1", service.port, timeout = 3000)
            if (result != null) {
                service.playerCount = result.onlinePlayers
                logger.debug("Pinged '${service.name}': ${result.onlinePlayers}/${result.maxPlayers} players")
            } else {
                logger.debug("Ping failed for '${service.name}' on port ${service.port}")
            }
        }
    }
}
