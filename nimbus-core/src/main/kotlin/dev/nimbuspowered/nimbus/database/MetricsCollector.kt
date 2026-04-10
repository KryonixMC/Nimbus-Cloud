package dev.nimbuspowered.nimbus.database

import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.service.DedicatedServiceManager
import dev.nimbuspowered.nimbus.service.ServiceMemoryResolver
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
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
        if (serviceEvents.isEmpty() && scalingEvents.isEmpty()) return

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

            }
        } catch (e: Exception) {
            val total = serviceEvents.size + scalingEvents.size
            logger.warn("Failed to flush metrics batch ({} entries): {}", total, e.message)
        }
    }

    /** Flush remaining events on shutdown. */
    suspend fun shutdown() {
        flushJob?.cancel()
        flush()
        logger.info("Metrics collector shut down (final flush complete)")
    }

    // ── Periodic memory sampling ──────────────────────────────
    //
    // Writes a row per running service every [samplingIntervalMs] into
    // [ServiceMetricSamples]. The dashboard queries this table to render
    // "memory over the last hour" charts so users don't start from blank
    // every time a service detail page is opened.

    private val samplingIntervalMs = 30_000L

    fun startMemorySampling(
        registry: ServiceRegistry,
        groupManager: GroupManager,
        dedicatedServiceManager: DedicatedServiceManager?,
    ): Job = scope.launch {
        while (isActive) {
            delay(samplingIntervalMs)
            val running = registry.getAll().filter {
                it.state == ServiceState.READY || it.state == ServiceState.STARTING
            }
            if (running.isEmpty()) continue
            val ts = Instant.now().toString()
            val rows = running.map { svc ->
                val mem = ServiceMemoryResolver.resolve(svc, groupManager, dedicatedServiceManager)
                SampleEntry(
                    timestamp = ts,
                    serviceName = svc.name,
                    groupName = svc.groupName,
                    memoryUsedMb = mem.usedMb.toInt(),
                    memoryMaxMb = mem.maxMb.toInt(),
                    playerCount = svc.playerCount,
                )
            }
            try {
                db.query {
                    ServiceMetricSamples.batchInsert(rows) { entry ->
                        this[ServiceMetricSamples.timestamp] = entry.timestamp
                        this[ServiceMetricSamples.serviceName] = entry.serviceName
                        this[ServiceMetricSamples.groupName] = entry.groupName
                        this[ServiceMetricSamples.memoryUsedMb] = entry.memoryUsedMb
                        this[ServiceMetricSamples.memoryMaxMb] = entry.memoryMaxMb
                        this[ServiceMetricSamples.playerCount] = entry.playerCount
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to write {} service metric samples: {}", rows.size, e.message)
            }
        }
    }

    private data class SampleEntry(
        val timestamp: String,
        val serviceName: String,
        val groupName: String?,
        val memoryUsedMb: Int,
        val memoryMaxMb: Int,
        val playerCount: Int,
    )

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
                val deletedSamples = ServiceMetricSamples.deleteWhere { timestamp less cutoff }
                logger.info(
                    "Metrics retention cleanup: pruned {} service events, {} scaling events, {} metric samples older than {} days",
                    deletedServices, deletedScaling, deletedSamples, retentionDays
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to prune old metrics: {}", e.message)
        }
    }
}
