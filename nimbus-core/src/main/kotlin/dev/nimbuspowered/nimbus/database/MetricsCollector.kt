package dev.nimbuspowered.nimbus.database

import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentLinkedQueue

class MetricsCollector(
    private val db: DatabaseManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope
) {
    private val logger = LoggerFactory.getLogger(MetricsCollector::class.java)

    /** Retention period for metrics data (30 days). */
    private val retentionDays = 30L

    /** Flush interval for batched writes (3 seconds). */
    private val flushIntervalMs = 3000L

    // Queues for batched writes
    private val serviceEventQueue = ConcurrentLinkedQueue<ServiceEventEntry>()
    private val scalingEventQueue = ConcurrentLinkedQueue<ScalingEventEntry>()
    private val playerConnectQueue = ConcurrentLinkedQueue<PlayerConnectEntry>()
    private val playerDisconnectQueue = ConcurrentLinkedQueue<PlayerDisconnectEntry>()

    private var flushJob: Job? = null

    private data class ServiceEventEntry(
        val timestamp: String, val eventType: String, val serviceName: String,
        val groupName: String? = null, val port: Int? = null,
        val exitCode: Int? = null, val restartAttempt: Int? = null
    )

    private data class ScalingEventEntry(
        val timestamp: String, val eventType: String, val groupName: String,
        val serviceName: String? = null, val currentInstances: Int? = null,
        val targetInstances: Int? = null, val reason: String
    )

    private data class PlayerConnectEntry(
        val playerName: String, val serviceName: String, val connectedAt: String
    )

    private data class PlayerDisconnectEntry(
        val playerName: String, val serviceName: String, val disconnectedAt: String
    )

    fun start(): List<Job> {
        val jobs = mutableListOf<Job>()

        // Service lifecycle events — enqueue instead of direct insert
        jobs += eventBus.on<NimbusEvent.ServiceStarting> { event ->
            serviceEventQueue.add(ServiceEventEntry(
                timestamp = event.timestamp.toString(), eventType = "STARTING",
                serviceName = event.serviceName, groupName = event.groupName, port = event.port
            ))
        }

        jobs += eventBus.on<NimbusEvent.ServiceReady> { event ->
            serviceEventQueue.add(ServiceEventEntry(
                timestamp = event.timestamp.toString(), eventType = "READY",
                serviceName = event.serviceName, groupName = event.groupName
            ))
        }

        jobs += eventBus.on<NimbusEvent.ServiceDraining> { event ->
            serviceEventQueue.add(ServiceEventEntry(
                timestamp = event.timestamp.toString(), eventType = "DRAINING",
                serviceName = event.serviceName, groupName = event.groupName
            ))
        }

        jobs += eventBus.on<NimbusEvent.ServiceStopping> { event ->
            serviceEventQueue.add(ServiceEventEntry(
                timestamp = event.timestamp.toString(), eventType = "STOPPING",
                serviceName = event.serviceName
            ))
        }

        jobs += eventBus.on<NimbusEvent.ServiceStopped> { event ->
            serviceEventQueue.add(ServiceEventEntry(
                timestamp = event.timestamp.toString(), eventType = "STOPPED",
                serviceName = event.serviceName
            ))
        }

        jobs += eventBus.on<NimbusEvent.ServiceCrashed> { event ->
            serviceEventQueue.add(ServiceEventEntry(
                timestamp = event.timestamp.toString(), eventType = "CRASHED",
                serviceName = event.serviceName, exitCode = event.exitCode,
                restartAttempt = event.restartAttempt
            ))
        }

        // Scaling events
        jobs += eventBus.on<NimbusEvent.ScaleUp> { event ->
            scalingEventQueue.add(ScalingEventEntry(
                timestamp = event.timestamp.toString(), eventType = "SCALE_UP",
                groupName = event.groupName, currentInstances = event.currentInstances,
                targetInstances = event.targetInstances, reason = event.reason
            ))
        }

        jobs += eventBus.on<NimbusEvent.ScaleDown> { event ->
            scalingEventQueue.add(ScalingEventEntry(
                timestamp = event.timestamp.toString(), eventType = "SCALE_DOWN",
                groupName = event.groupName, serviceName = event.serviceName,
                reason = event.reason
            ))
        }

        // Player sessions
        jobs += eventBus.on<NimbusEvent.PlayerConnected> { event ->
            playerConnectQueue.add(PlayerConnectEntry(
                playerName = event.playerName, serviceName = event.serviceName,
                connectedAt = event.timestamp.toString()
            ))
        }

        jobs += eventBus.on<NimbusEvent.PlayerDisconnected> { event ->
            playerDisconnectQueue.add(PlayerDisconnectEntry(
                playerName = event.playerName, serviceName = event.serviceName,
                disconnectedAt = event.timestamp.toString()
            ))
        }

        // Start periodic flush loop
        flushJob = scope.launch {
            while (isActive) {
                delay(flushIntervalMs)
                flush()
            }
        }
        jobs += flushJob!!

        logger.info("Metrics collector started ({} event subscriptions, batched flush every {}s)",
            jobs.size - 1, flushIntervalMs / 1000)
        return jobs
    }

    private suspend fun flush() {
        val serviceEvents = drainQueue(serviceEventQueue)
        val scalingEvents = drainQueue(scalingEventQueue)
        val connects = drainQueue(playerConnectQueue)
        val disconnects = drainQueue(playerDisconnectQueue)

        if (serviceEvents.isEmpty() && scalingEvents.isEmpty() && connects.isEmpty() && disconnects.isEmpty()) return

        try {
            db.query {
                if (serviceEvents.isNotEmpty()) {
                    ServiceEvents.batchInsert(serviceEvents) { entry ->
                        this[ServiceEvents.timestamp] = entry.timestamp
                        this[ServiceEvents.eventType] = entry.eventType
                        this[ServiceEvents.serviceName] = entry.serviceName
                        this[ServiceEvents.groupName] = entry.groupName
                        this[ServiceEvents.port] = entry.port
                        this[ServiceEvents.exitCode] = entry.exitCode
                        this[ServiceEvents.restartAttempt] = entry.restartAttempt
                    }
                }

                if (scalingEvents.isNotEmpty()) {
                    ScalingEvents.batchInsert(scalingEvents) { entry ->
                        this[ScalingEvents.timestamp] = entry.timestamp
                        this[ScalingEvents.eventType] = entry.eventType
                        this[ScalingEvents.groupName] = entry.groupName
                        this[ScalingEvents.serviceName] = entry.serviceName
                        this[ScalingEvents.currentInstances] = entry.currentInstances
                        this[ScalingEvents.targetInstances] = entry.targetInstances
                        this[ScalingEvents.reason] = entry.reason
                    }
                }

                if (connects.isNotEmpty()) {
                    PlayerSessions.batchInsert(connects) { entry ->
                        this[PlayerSessions.playerName] = entry.playerName
                        this[PlayerSessions.serviceName] = entry.serviceName
                        this[PlayerSessions.connectedAt] = entry.connectedAt
                    }
                }

                // Disconnects need individual UPDATEs (different WHERE per row)
                for (entry in disconnects) {
                    PlayerSessions.update(
                        where = {
                            (PlayerSessions.playerName eq entry.playerName) and
                            (PlayerSessions.serviceName eq entry.serviceName) and
                            (PlayerSessions.disconnectedAt.isNull())
                        }
                    ) {
                        it[disconnectedAt] = entry.disconnectedAt
                    }
                }
            }
        } catch (e: Exception) {
            val total = serviceEvents.size + scalingEvents.size + connects.size + disconnects.size
            logger.warn("Failed to flush metrics batch ({} entries): {}", total, e.message)
        }
    }

    /** Flush remaining events on shutdown. */
    suspend fun shutdown() {
        flushJob?.cancel()
        flush()
        logger.info("Metrics collector shut down (final flush complete)")
    }

    private fun <T> drainQueue(queue: ConcurrentLinkedQueue<T>): List<T> {
        val list = mutableListOf<T>()
        while (true) {
            list.add(queue.poll() ?: break)
        }
        return list
    }

    /**
     * Starts a periodic job that prunes metrics older than [retentionDays].
     * Should be called once during bootstrap with the application's CoroutineScope.
     */
    fun startRetentionCleanup(scope: CoroutineScope): Job = scope.launch {
        // Run daily
        while (isActive) {
            delay(24 * 60 * 60 * 1000L) // 24 hours
            pruneOldMetrics()
        }
    }

    private suspend fun pruneOldMetrics() {
        val cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS).toString()
        try {
            db.query {
                val deletedServices = ServiceEvents.deleteWhere { timestamp less cutoff }
                val deletedScaling = ScalingEvents.deleteWhere { timestamp less cutoff }
                val deletedSessions = PlayerSessions.deleteWhere { connectedAt less cutoff }
                logger.info("Metrics retention cleanup: pruned {} service events, {} scaling events, {} player sessions older than {} days",
                    deletedServices, deletedScaling, deletedSessions, retentionDays)
            }
        } catch (e: Exception) {
            logger.warn("Failed to prune old metrics: {}", e.message)
        }
    }
}
