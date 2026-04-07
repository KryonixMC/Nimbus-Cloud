package dev.nimbuspowered.nimbus.database

import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
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

/**
 * Subscribes to auditable events on the [EventBus] and batch-writes them
 * to the [AuditLog] table. Modeled after [MetricsCollector].
 */
class AuditCollector(
    private val db: DatabaseManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val retentionDays: Long = 90L
) {
    private val logger = LoggerFactory.getLogger(AuditCollector::class.java)

    private val queue = ConcurrentLinkedQueue<AuditEntry>()
    private var flushJob: Job? = null

    private data class AuditEntry(
        val timestamp: String,
        val actor: String,
        val action: String,
        val target: String,
        val details: String
    )

    fun start(): List<Job> {
        val jobs = mutableListOf<Job>()

        // Service lifecycle
        jobs += eventBus.on<NimbusEvent.ServiceStarting> { e ->
            enqueue(e, "SERVICE_STARTING", e.serviceName, "group=${e.groupName}, port=${e.port}, node=${e.nodeId}")
        }
        jobs += eventBus.on<NimbusEvent.ServiceStopping> { e ->
            enqueue(e, "SERVICE_STOPPING", e.serviceName)
        }
        jobs += eventBus.on<NimbusEvent.ServiceStopped> { e ->
            enqueue(e, "SERVICE_STOPPED", e.serviceName)
        }
        jobs += eventBus.on<NimbusEvent.ServiceCrashed> { e ->
            enqueue(e, "SERVICE_CRASHED", e.serviceName, "exitCode=${e.exitCode}, restart=${e.restartAttempt}")
        }

        // Scaling
        jobs += eventBus.on<NimbusEvent.ScaleUp> { e ->
            enqueue(e, "SCALE_UP", e.groupName, "${e.currentInstances} → ${e.targetInstances}: ${e.reason}")
        }
        jobs += eventBus.on<NimbusEvent.ScaleDown> { e ->
            enqueue(e, "SCALE_DOWN", e.serviceName, e.reason)
        }

        // Group management
        jobs += eventBus.on<NimbusEvent.GroupCreated> { e ->
            enqueue(e, "GROUP_CREATED", e.groupName)
        }
        jobs += eventBus.on<NimbusEvent.GroupUpdated> { e ->
            enqueue(e, "GROUP_UPDATED", e.groupName)
        }
        jobs += eventBus.on<NimbusEvent.GroupDeleted> { e ->
            enqueue(e, "GROUP_DELETED", e.groupName)
        }

        // Maintenance
        jobs += eventBus.on<NimbusEvent.MaintenanceEnabled> { e ->
            enqueue(e, "MAINTENANCE_ENABLED", e.scope, e.reason)
        }
        jobs += eventBus.on<NimbusEvent.MaintenanceDisabled> { e ->
            enqueue(e, "MAINTENANCE_DISABLED", e.scope)
        }

        // Config
        jobs += eventBus.on<NimbusEvent.ConfigReloaded> { e ->
            enqueue(e, "CONFIG_RELOADED", "", "${e.groupsLoaded} groups loaded")
        }

        // Module lifecycle
        jobs += eventBus.on<NimbusEvent.ModuleEnabled> { e ->
            enqueue(e, "MODULE_ENABLED", e.moduleName, "id=${e.moduleId}")
        }
        jobs += eventBus.on<NimbusEvent.ModuleDisabled> { e ->
            enqueue(e, "MODULE_DISABLED", e.moduleName, "id=${e.moduleId}")
        }

        // API lifecycle
        jobs += eventBus.on<NimbusEvent.ApiStarted> { e ->
            enqueue(e, "API_STARTED", "${e.bind}:${e.port}")
        }
        jobs += eventBus.on<NimbusEvent.ApiStopped> { e ->
            enqueue(e, "API_STOPPED", "", e.reason)
        }

        // Flush loop
        flushJob = scope.launch {
            while (isActive) {
                delay(3000L)
                flush()
            }
        }
        jobs += flushJob!!

        logger.info("Audit collector started ({} event subscriptions, retention {} days)", jobs.size - 1, retentionDays)
        return jobs
    }

    private fun enqueue(event: NimbusEvent, action: String, target: String, details: String = "") {
        queue.add(AuditEntry(
            timestamp = event.timestamp.toString(),
            actor = event.actor,
            action = action,
            target = target,
            details = details
        ))
    }

    private suspend fun flush() {
        val entries = mutableListOf<AuditEntry>()
        while (true) {
            entries.add(queue.poll() ?: break)
        }
        if (entries.isEmpty()) return

        try {
            db.query {
                AuditLog.batchInsert(entries) { entry ->
                    this[AuditLog.timestamp] = entry.timestamp
                    this[AuditLog.actor] = entry.actor
                    this[AuditLog.action] = entry.action
                    this[AuditLog.target] = entry.target
                    this[AuditLog.details] = entry.details
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to flush audit batch ({} entries): {}", entries.size, e.message)
        }
    }

    suspend fun shutdown() {
        flushJob?.cancel()
        flush()
        logger.info("Audit collector shut down (final flush complete)")
    }

    fun startRetentionCleanup(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            delay(24 * 60 * 60 * 1000L) // 24 hours
            pruneOldEntries()
        }
    }

    private suspend fun pruneOldEntries() {
        val cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS).toString()
        try {
            db.query {
                val deleted = AuditLog.deleteWhere { timestamp less cutoff }
                if (deleted > 0) {
                    logger.info("Audit retention cleanup: pruned {} entries older than {} days", deleted, retentionDays)
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to prune old audit entries: {}", e.message)
        }
    }
}
