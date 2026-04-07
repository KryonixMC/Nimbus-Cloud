package dev.nimbuspowered.nimbus.service

import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.group.GroupManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Maintains a pool of pre-prepared services per group. When a service needs to start,
 * the warm pool provides a ready-to-launch [ServiceFactory.PreparedService] instantly
 * instead of waiting for template copying and config patching.
 *
 * Configured per group via `scaling.warm_pool_size` (default 0 = disabled).
 */
class WarmPoolManager(
    private val serviceFactory: ServiceFactory,
    private val registry: ServiceRegistry,
    private val groupManager: GroupManager,
    private val portAllocator: PortAllocator,
    private val eventBus: EventBus,
    private val scope: CoroutineScope
) {
    private val logger = LoggerFactory.getLogger(WarmPoolManager::class.java)

    /** Pool of prepared services per group, ready to be taken and started. */
    private val pools = ConcurrentHashMap<String, ConcurrentLinkedDeque<ServiceFactory.PreparedService>>()

    /**
     * Takes a prepared service from the warm pool for the given group.
     * Returns null if no prepared service is available.
     */
    fun take(groupName: String): ServiceFactory.PreparedService? {
        val pool = pools[groupName] ?: return null
        val prepared = pool.pollFirst() ?: return null
        logger.info("Took prepared service '{}' from warm pool (group '{}')", prepared.service.name, groupName)
        return prepared
    }

    /**
     * Returns the number of prepared services available for a group.
     */
    fun poolSize(groupName: String): Int = pools[groupName]?.size ?: 0

    /**
     * Returns a snapshot of all pool sizes per group.
     */
    fun allPoolSizes(): Map<String, Int> {
        return pools.mapValues { it.value.size }.filter { it.value > 0 }
    }

    /**
     * Starts the background replenishment loop. Runs every 15 seconds and fills pools
     * up to the configured warm_pool_size for each group.
     */
    fun start(): Job = scope.launch {
        logger.info("Warm pool manager started")
        while (isActive) {
            try {
                replenish()
            } catch (e: Exception) {
                logger.error("Error during warm pool replenishment", e)
            }
            delay(15_000)
        }
    }

    /**
     * Manually fills the warm pool for a specific group up to its configured size.
     */
    suspend fun fill(groupName: String): Int {
        val group = groupManager.getGroup(groupName) ?: return 0
        val targetSize = group.config.group.scaling.warmPoolSize
        if (targetSize <= 0) return 0

        val pool = pools.getOrPut(groupName) { ConcurrentLinkedDeque() }
        var filled = 0
        while (pool.size < targetSize) {
            val prepared = prepareForPool(groupName) ?: break
            pool.add(prepared)
            filled++
        }
        if (filled > 0) {
            logger.info("Filled warm pool for group '{}': {} service(s) prepared", groupName, filled)
        }
        return filled
    }

    /**
     * Drains (discards) all prepared services from the warm pool for a group.
     */
    fun drain(groupName: String): Int {
        val pool = pools.remove(groupName) ?: return 0
        var count = 0
        while (true) {
            val prepared = pool.pollFirst() ?: break
            cleanupPrepared(prepared)
            count++
        }
        if (count > 0) {
            logger.info("Drained warm pool for group '{}': {} service(s) discarded", groupName, count)
        }
        return count
    }

    /**
     * Cleans up all warm pool services. Called during shutdown.
     */
    fun shutdown() {
        for ((groupName, pool) in pools) {
            while (true) {
                val prepared = pool.pollFirst() ?: break
                cleanupPrepared(prepared)
            }
        }
        pools.clear()
        logger.info("Warm pool shut down")
    }

    private suspend fun replenish() {
        for (group in groupManager.getAllGroups()) {
            if (group.isStatic) continue
            val targetSize = group.config.group.scaling.warmPoolSize
            if (targetSize <= 0) continue

            val pool = pools.getOrPut(group.name) { ConcurrentLinkedDeque() }
            val currentSize = pool.size

            if (currentSize >= targetSize) continue

            val needed = targetSize - currentSize
            var filled = 0
            repeat(needed) {
                val prepared = prepareForPool(group.name) ?: return@repeat
                pool.add(prepared)
                filled++
            }

            if (filled > 0) {
                logger.info("Warm pool for '{}': replenished {} service(s) (now {}/{})", group.name, filled, pool.size, targetSize)
                eventBus.emit(NimbusEvent.WarmPoolReplenished(group.name, pool.size))
            }
        }
    }

    private suspend fun prepareForPool(groupName: String): ServiceFactory.PreparedService? {
        val prepared = serviceFactory.prepare(groupName) ?: return null
        // Transition from PREPARING to PREPARED (warm pool state)
        prepared.service.transitionTo(ServiceState.PREPARED)
        eventBus.emit(NimbusEvent.ServicePrepared(prepared.service.name, groupName))
        return prepared
    }

    private fun cleanupPrepared(prepared: ServiceFactory.PreparedService) {
        prepared.service.transitionTo(ServiceState.STOPPED)
        portAllocator.release(prepared.service.port)
        prepared.service.bedrockPort?.let { portAllocator.releaseBedrockPort(it) }
        registry.unregister(prepared.service.name)
        // Clean up working directory
        val workDir = prepared.workDir
        if (workDir.toFile().exists() && !prepared.service.isStatic) {
            try {
                Files.walk(workDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
            } catch (e: Exception) {
                logger.warn("Failed to clean up warm pool workdir: {}", e.message)
            }
        }
    }
}
