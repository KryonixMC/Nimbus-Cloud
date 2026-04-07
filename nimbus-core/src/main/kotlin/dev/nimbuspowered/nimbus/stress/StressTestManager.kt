package dev.nimbuspowered.nimbus.stress

import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.proxy.ProxySyncManager
import dev.nimbuspowered.nimbus.service.Service
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class StressTestManager(
    private val registry: ServiceRegistry,
    private val groupManager: GroupManager,
    private val eventBus: EventBus,
    private val proxySyncManager: ProxySyncManager?,
    private val scope: CoroutineScope
) {
    private val logger = LoggerFactory.getLogger(StressTestManager::class.java)

    /** Per-service simulated player count. */
    private val overrides = ConcurrentHashMap<String, Int>()

    /** The currently running ramp/hold coroutine. */
    private var activeJob: Job? = null

    /** Current stress test profile (null when idle). */
    var profile: StressProfile? = null
        private set

    /** Last emitted player count — avoids spamming events/MOTD on every tick. */
    private var lastEmittedCount = -1

    data class StressProfile(
        val groupName: String?,
        val targetPlayers: Int,
        val rampDurationMs: Long,
        val startedAt: Instant = Instant.now(),
        var currentPlayers: Int = 0,
        var overflow: Int = 0
    )

    data class StressStatus(
        val profile: StressProfile,
        val perService: Map<String, Int>,
        val proxyServices: Map<String, Int>,
        val elapsedMs: Long,
        val totalCapacity: Int
    )

    /**
     * Returns true if this service has simulated player overrides.
     * Called by ScalingEngine to skip pinging overridden services.
     */
    fun isOverridden(serviceName: String): Boolean = overrides.containsKey(serviceName)

    fun isActive(): Boolean = profile != null

    /**
     * Starts a stress test, distributing [targetPlayers] across READY backend services
     * (non-proxy) in the given group (or all groups if null).
     *
     * - Proxy groups (VELOCITY) are excluded from player distribution.
     *   Instead, proxy services get the SUM of all backend players.
     * - Each backend service is capped at its group's maxPlayers.
     * - If total capacity is exceeded, overflow is tracked.
     * - The Bridge proxy display is updated via STRESS_TEST_UPDATED event.
     */
    fun start(groupName: String?, targetPlayers: Int, rampDurationMs: Long): Boolean {
        if (profile != null) return false

        // Validate: don't allow targeting a proxy group directly
        if (groupName != null) {
            val group = groupManager.getGroup(groupName)
            if (group != null && group.config.group.software == ServerSoftware.VELOCITY) {
                logger.warn("Cannot stress test proxy group '$groupName' directly — players are distributed to backend groups")
                return false
            }
        }

        lastEmittedCount = -1

        profile = StressProfile(
            groupName = groupName,
            targetPlayers = targetPlayers,
            rampDurationMs = rampDurationMs
        )

        activeJob = scope.launch { runDistributionLoop(0) }

        logger.info("Stress test started: $targetPlayers players on ${groupName ?: "all backend groups"}")
        return true
    }

    /**
     * Adjusts the target player count mid-test, ramping over [durationMs].
     */
    fun ramp(newTarget: Int, durationMs: Long): Boolean {
        val current = profile ?: return false
        activeJob?.cancel()

        val startCount = current.currentPlayers
        profile = StressProfile(
            groupName = current.groupName,
            targetPlayers = newTarget,
            rampDurationMs = durationMs,
            currentPlayers = startCount
        )

        activeJob = scope.launch { runDistributionLoop(startCount) }
        return true
    }

    /**
     * Main distribution loop. Ramps from [startCount] to [profile.targetPlayers]
     * over the configured duration, then holds and redistributes as services scale.
     */
    private suspend fun runDistributionLoop(startCount: Int) {
        val loopStartTime = System.currentTimeMillis()

        while (currentCoroutineContext().isActive) {
            val currentProfile = profile ?: break

            // Calculate interpolated target based on ramp progress
            val elapsed = System.currentTimeMillis() - loopStartTime
            val progress = if (currentProfile.rampDurationMs <= 0) 1.0
            else (elapsed.toDouble() / currentProfile.rampDurationMs).coerceAtMost(1.0)

            val currentTarget = startCount + ((currentProfile.targetPlayers - startCount) * progress).toInt()

            // Get backend services (exclude proxy groups)
            val backendServices = getBackendServices(currentProfile.groupName)

            if (backendServices.isEmpty()) {
                logger.debug("Stress: no READY backend services found, waiting...")
                delay(1000)
                continue
            }

            // Build capacity map: service -> maxPlayers for its group
            val capacityMap = backendServices.associate { service ->
                val group = groupManager.getGroup(service.groupName)
                val maxPlayers = group?.config?.group?.resources?.maxPlayers ?: 50
                service to maxPlayers
            }

            // Distribute players respecting maxPlayers per service
            val totalCapacity = capacityMap.values.sum()
            val assignable = currentTarget.coerceAtMost(totalCapacity)
            val overflow = (currentTarget - totalCapacity).coerceAtLeast(0)

            val assignments = distributeWithCapacity(backendServices, capacityMap, assignable)

            // Apply assignments to each service
            var totalAssigned = 0
            for ((service, targetCount) in assignments) {
                val previousCount = overrides[service.name] ?: 0
                overrides[service.name] = targetCount

                if (targetCount != previousCount) {
                    val diff = targetCount - previousCount
                    if (diff > 0) {
                        logger.debug("Stress: +$diff simulated players on ${service.name} (now $targetCount)")
                    } else {
                        logger.debug("Stress: $diff simulated players on ${service.name} (now $targetCount)")
                    }
                }

                service.playerCount = targetCount
                totalAssigned += targetCount
            }

            // Clean up overrides for services that are no longer targeted
            val activeNames = assignments.keys.map { it.name }.toSet()
            val stale = overrides.keys.filter { it !in activeNames && !isProxyServiceByName(it) }
            for (name in stale) {
                overrides.remove(name)
                val service = registry.get(name)
                if (service != null) {
                    service.playerCount = 0
                }
            }

            // Update proxy services: each proxy gets the total backend player count
            updateProxyServices(totalAssigned)

            // Only emit events when the count actually changes (avoid spamming)
            if (totalAssigned != lastEmittedCount) {
                lastEmittedCount = totalAssigned
                eventBus.emit(NimbusEvent.StressTestUpdated(
                    totalAssigned, currentTarget,
                    currentProfile.groupName,
                    assignments.map { (svc, count) -> svc.name to count }.toMap()
                ))
                logger.debug("Stress: distributed $totalAssigned players across ${assignments.size} services (capacity: $totalCapacity)")
            }

            currentProfile.currentPlayers = totalAssigned
            currentProfile.overflow = overflow

            delay(if (progress >= 1.0) 2000 else 1000)
        }
    }

    /**
     * Distributes [totalPlayers] across services, respecting each service's max capacity.
     * Fills services proportionally, then spills over to services with remaining capacity.
     */
    private fun distributeWithCapacity(
        services: List<Service>,
        capacityMap: Map<Service, Int>,
        totalPlayers: Int
    ): Map<Service, Int> {
        if (services.isEmpty()) return emptyMap()

        val assignments = mutableMapOf<Service, Int>()
        var remaining = totalPlayers

        // First pass: distribute evenly, capped at maxPlayers
        val perService = remaining / services.size
        var leftover = remaining % services.size

        for (service in services) {
            val cap = capacityMap[service] ?: 50
            val base = perService + if (leftover > 0) { leftover--; 1 } else 0
            val assigned = base.coerceAtMost(cap)
            assignments[service] = assigned
            remaining -= assigned
        }

        // Second pass: distribute remaining to services with capacity left
        if (remaining > 0) {
            for (service in services) {
                if (remaining <= 0) break
                val cap = capacityMap[service] ?: 50
                val current = assignments[service] ?: 0
                val canTake = (cap - current).coerceAtLeast(0)
                val extra = remaining.coerceAtMost(canTake)
                assignments[service] = current + extra
                remaining -= extra
            }
        }

        return assignments
    }

    /**
     * Updates proxy service playerCount to reflect total backend players.
     * ALL players connect through the proxy, so proxy count = total backend players.
     */
    private fun updateProxyServices(totalBackendPlayers: Int) {
        val proxyGroups = groupManager.getAllGroups()
            .filter { it.config.group.software == ServerSoftware.VELOCITY }

        for (group in proxyGroups) {
            val proxyServices = registry.getByGroup(group.name)
                .filter { it.state == ServiceState.READY }

            if (proxyServices.isEmpty()) continue

            if (proxyServices.size == 1) {
                // Single proxy: all players
                val service = proxyServices.first()
                service.playerCount = totalBackendPlayers
                overrides[service.name] = totalBackendPlayers
            } else {
                // Multiple proxies: split evenly
                val perProxy = totalBackendPlayers / proxyServices.size
                val remainder = totalBackendPlayers % proxyServices.size
                for ((index, service) in proxyServices.withIndex()) {
                    val count = perProxy + if (index < remainder) 1 else 0
                    service.playerCount = count
                    overrides[service.name] = count
                }
            }
        }
    }

    /**
     * Stops the stress test and cleans up all simulated player counts.
     */
    suspend fun stop() {
        activeJob?.cancel()
        activeJob = null

        // Reset all overridden services
        for ((serviceName, count) in overrides) {
            val service = registry.get(serviceName) ?: continue
            service.playerCount = 0
            if (!isProxyService(service)) {
                logger.info("Stress: removed $count simulated players from $serviceName")
            }
        }

        overrides.clear()
        val wasActive = profile != null
        profile = null
        lastEmittedCount = -1

        // Tell Bridge to clear stress data
        if (wasActive) {
            eventBus.emit(NimbusEvent.StressTestUpdated(0, 0, null, emptyMap()))
            logger.info("Stress test stopped, all simulated players removed")
        }
    }

    fun getStatus(): StressStatus? {
        val p = profile ?: return null

        val backendOverrides = overrides.filter { (name, _) ->
            val service = registry.get(name)
            service != null && !isProxyService(service)
        }

        val proxyOverrides = overrides.filter { (name, _) ->
            val service = registry.get(name)
            service != null && isProxyService(service)
        }

        val totalCapacity = getBackendServices(p.groupName).sumOf { service ->
            val group = groupManager.getGroup(service.groupName)
            group?.config?.group?.resources?.maxPlayers ?: 50
        }

        val elapsed = java.time.Duration.between(p.startedAt, Instant.now()).toMillis()
        return StressStatus(p, backendOverrides, proxyOverrides, elapsed, totalCapacity)
    }

    private fun getBackendServices(groupName: String?): List<Service> {
        val services = if (groupName != null) {
            registry.getByGroup(groupName)
        } else {
            registry.getAll()
        }
        return services.filter { it.state == ServiceState.READY && !isProxyService(it) }
    }

    private fun isProxyService(service: Service): Boolean {
        val group = groupManager.getGroup(service.groupName)
        return group?.config?.group?.software == ServerSoftware.VELOCITY
    }

    private fun isProxyServiceByName(serviceName: String): Boolean {
        val service = registry.get(serviceName) ?: return false
        return isProxyService(service)
    }
}
