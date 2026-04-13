package dev.nimbuspowered.nimbus.module.anomaly

import dev.nimbuspowered.nimbus.database.DatabaseManager
import dev.nimbuspowered.nimbus.database.ServiceMetricSamples
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceState
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

// ── Data Classes ────────────────────────────────────────────

/** Metric identifier constants. */
object Metrics {
    const val MEMORY_USED_MB = "memory_used_mb"
    const val PLAYER_COUNT = "player_count"
}

/** In-memory state for a currently-active anomaly (not yet resolved). */
data class AnomalyState(
    val dbId: Long,
    val serviceName: String,
    val groupName: String?,
    val metric: String,
    val anomalyType: String,
    val value: Double,
    val baseline: Double,
    val zscore: Double,
    val severity: String,
    val detectedAt: String
)

/** DTO for API / command output, mirrors the DB row. */
data class AnomalyEntry(
    val id: Long,
    val detectedAt: String,
    val serviceName: String,
    val groupName: String?,
    val metric: String,
    val anomalyType: String,
    val value: Double,
    val baseline: Double,
    val zscore: Double,
    val severity: String,
    val resolved: Boolean,
    val resolvedAt: String?
)

data class AnomalyStats(
    val totalDetected: Long,
    val currentActive: Int,
    val criticalCount: Int,
    val warningCount: Int,
    val mostAffectedGroup: String?
)

// ── Manager ─────────────────────────────────────────────────

class AnomalyManager(
    private val db: DatabaseManager,
    private val configManager: AnomalyConfigManager,
    private val registry: ServiceRegistry,
    private val groupManager: GroupManager,
    private val eventBus: EventBus
) {

    private val logger = LoggerFactory.getLogger(AnomalyManager::class.java)

    /** Active (unresolved) anomalies, keyed by "serviceName:metric". */
    val activeAnomalies = ConcurrentHashMap<String, AnomalyState>()

    // ── Evaluation Loop ─────────────────────────────────────

    suspend fun runEvaluation() {
        val cfg = configManager.getConfig()
        if (!cfg.enabled) return

        val allServices = registry.getAll().filter { it.state == ServiceState.READY }
        if (allServices.isEmpty()) return

        // Build a map of groupName → list of READY services for peer comparison.
        val byGroup: Map<String?, List<dev.nimbuspowered.nimbus.service.Service>> =
            allServices.groupBy { it.groupName.ifBlank { null } }

        // Collect current metric values for every service (used in peer comparison).
        val currentMemory: Map<String, Double> = allServices.associate {
            it.name to it.memoryUsedMb.toDouble()
        }
        val currentPlayers: Map<String, Double> = allServices.associate {
            it.name to it.playerCount.toDouble()
        }

        for (service in allServices) {
            // ── Z-score against own history ──────────────────

            val historySamples = fetchRecentSamples(service.name, cfg.windowSamples)

            for (metric in listOf(Metrics.MEMORY_USED_MB, Metrics.PLAYER_COUNT)) {
                val historicalValues: List<Double> = when (metric) {
                    Metrics.MEMORY_USED_MB -> historySamples.map { it.first.toDouble() }
                    Metrics.PLAYER_COUNT -> historySamples.map { it.second.toDouble() }
                    else -> continue
                }
                val currentValue: Double = when (metric) {
                    Metrics.MEMORY_USED_MB -> service.memoryUsedMb.toDouble()
                    Metrics.PLAYER_COUNT -> service.playerCount.toDouble()
                    else -> continue
                }

                // Append current value at the end so the Z-score is relative to it.
                val windowWithCurrent = historicalValues + currentValue

                val result = AnomalyDetector.computeZScore(windowWithCurrent)
                if (result != null && abs(result.zscore) > cfg.zscoreThreshold) {
                    handleAnomaly(
                        serviceName = service.name,
                        groupName = service.groupName.ifBlank { null },
                        metric = metric,
                        anomalyType = "zscore",
                        value = currentValue,
                        baseline = result.mean,
                        zscore = result.zscore,
                        zscoreThreshold = cfg.zscoreThreshold
                    )
                } else {
                    maybeResolve(service.name, metric)
                }
            }

            // ── Peer comparison ──────────────────────────────

            if (cfg.peerComparisonEnabled) {
                val groupPeers = byGroup[service.groupName.ifBlank { null }]
                    ?.filter { it.name != service.name }
                    ?: emptyList()

                if (groupPeers.isNotEmpty()) {
                    for (metric in listOf(Metrics.MEMORY_USED_MB, Metrics.PLAYER_COUNT)) {
                        val peerValues: List<Double> = when (metric) {
                            Metrics.MEMORY_USED_MB -> groupPeers.map { currentMemory[it.name] ?: 0.0 }
                            Metrics.PLAYER_COUNT -> groupPeers.map { currentPlayers[it.name] ?: 0.0 }
                            else -> continue
                        }
                        val serviceValue: Double = when (metric) {
                            Metrics.MEMORY_USED_MB -> currentMemory[service.name] ?: 0.0
                            Metrics.PLAYER_COUNT -> currentPlayers[service.name] ?: 0.0
                            else -> continue
                        }

                        val peerKey = "${service.name}:peer_$metric"
                        val result = AnomalyDetector.computePeerZScore(serviceValue, peerValues)
                        if (result != null && abs(result.zscore) > cfg.peerZscoreThreshold) {
                            handleAnomaly(
                                serviceName = service.name,
                                groupName = service.groupName.ifBlank { null },
                                metric = metric,
                                anomalyType = "peer_outlier",
                                value = serviceValue,
                                baseline = result.mean,
                                zscore = result.zscore,
                                zscoreThreshold = cfg.peerZscoreThreshold,
                                activeKey = peerKey
                            )
                        } else {
                            maybeResolve(service.name, metric, peerKey)
                        }
                    }
                }
            }
        }

        // Resolve anomalies for services that are no longer READY.
        val readyNames = allServices.map { it.name }.toSet()
        val staleKeys = activeAnomalies.keys.filter { key ->
            val serviceName = key.substringBefore(":")
            serviceName !in readyNames
        }
        for (key in staleKeys) {
            resolveAnomaly(key)
        }
    }

    // ── Anomaly Lifecycle ───────────────────────────────────

    private suspend fun handleAnomaly(
        serviceName: String,
        groupName: String?,
        metric: String,
        anomalyType: String,
        value: Double,
        baseline: Double,
        zscore: Double,
        zscoreThreshold: Double,
        activeKey: String = "$serviceName:$metric"
    ) {
        val severity = if (abs(zscore) > 3.5) "critical" else "warning"

        // If already active, check whether severity changed — if not, skip re-insert.
        val existing = activeAnomalies[activeKey]
        if (existing != null && existing.severity == severity) return

        // If severity escalated, resolve the old entry first.
        if (existing != null) resolveAnomaly(activeKey)

        val now = Instant.now().toString()

        val newId = db.query {
            AnomalyEvents.insert {
                it[detectedAt] = now
                it[AnomalyEvents.serviceName] = serviceName
                it[AnomalyEvents.groupName] = groupName
                it[AnomalyEvents.metric] = metric
                it[AnomalyEvents.anomalyType] = anomalyType
                it[AnomalyEvents.value] = value
                it[AnomalyEvents.baseline] = baseline
                it[AnomalyEvents.zscore] = zscore
                it[AnomalyEvents.severity] = severity
                it[resolved] = false
                it[resolvedAt] = null
            }[AnomalyEvents.id]
        }

        val state = AnomalyState(
            dbId = newId,
            serviceName = serviceName,
            groupName = groupName,
            metric = metric,
            anomalyType = anomalyType,
            value = value,
            baseline = baseline,
            zscore = zscore,
            severity = severity,
            detectedAt = now
        )
        activeAnomalies[activeKey] = state

        val eventType = if (severity == "critical") "ANOMALY_CRITICAL" else "ANOMALY_WARNING"
        eventBus.emit(
            NimbusEvent.ModuleEvent(
                moduleId = "anomaly",
                type = eventType,
                data = mapOf(
                    "service" to serviceName,
                    "group" to (groupName ?: ""),
                    "metric" to metric,
                    "type" to anomalyType,
                    "zscore" to "%.2f".format(zscore),
                    "value" to "%.1f".format(value),
                    "baseline" to "%.1f".format(baseline),
                    "severity" to severity
                )
            )
        )

        logger.info(
            "Anomaly detected: service={} metric={} type={} z={:.2f} severity={}",
            serviceName, metric, anomalyType, zscore, severity
        )
    }

    private suspend fun maybeResolve(
        serviceName: String,
        metric: String,
        activeKey: String = "$serviceName:$metric"
    ) {
        if (activeAnomalies.containsKey(activeKey)) {
            resolveAnomaly(activeKey)
        }
    }

    private suspend fun resolveAnomaly(activeKey: String) {
        val state = activeAnomalies.remove(activeKey) ?: return
        val now = Instant.now().toString()

        db.query {
            AnomalyEvents.update(
                where = { AnomalyEvents.id eq state.dbId }
            ) {
                it[resolved] = true
                it[resolvedAt] = now
            }
        }

        logger.debug("Anomaly resolved: service={} metric={}", state.serviceName, state.metric)
    }

    // ── Queries ─────────────────────────────────────────────

    suspend fun getCurrentAnomalies(): List<AnomalyEntry> = db.query {
        AnomalyEvents
            .selectAll()
            .where { AnomalyEvents.resolved eq false }
            .orderBy(AnomalyEvents.detectedAt, SortOrder.DESC)
            .map { it.toEntry() }
    }

    suspend fun getHistory(limit: Int = 100, serviceName: String? = null): List<AnomalyEntry> = db.query {
        val query = if (serviceName != null) {
            AnomalyEvents
                .selectAll()
                .where { AnomalyEvents.serviceName eq serviceName }
        } else {
            AnomalyEvents.selectAll()
        }
        query
            .orderBy(AnomalyEvents.detectedAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toEntry() }
    }

    suspend fun getStats(): AnomalyStats {
        val active = activeAnomalies.values.toList()
        val total = db.query {
            AnomalyEvents.selectAll().count()
        }
        val criticalCount = active.count { it.severity == "critical" }
        val warningCount = active.count { it.severity == "warning" }
        val mostAffectedGroup = active
            .groupBy { it.groupName }
            .maxByOrNull { (_, entries) -> entries.size }
            ?.key

        return AnomalyStats(
            totalDetected = total,
            currentActive = active.size,
            criticalCount = criticalCount,
            warningCount = warningCount,
            mostAffectedGroup = mostAffectedGroup
        )
    }

    // ── Internal helpers ─────────────────────────────────────

    /**
     * Fetch the [n] most recent metric samples for a given service from
     * [ServiceMetricSamples]. Returns pairs of (memoryUsedMb, playerCount).
     */
    private suspend fun fetchRecentSamples(
        serviceName: String,
        n: Int
    ): List<Pair<Int, Int>> = db.query {
        ServiceMetricSamples
            .selectAll()
            .where { ServiceMetricSamples.serviceName eq serviceName }
            .orderBy(ServiceMetricSamples.timestamp, SortOrder.DESC)
            .limit(n)
            .map { row ->
                row[ServiceMetricSamples.memoryUsedMb] to row[ServiceMetricSamples.playerCount]
            }
            .reversed() // chronological order (oldest first) for Z-score computation
    }

    private fun ResultRow.toEntry() = AnomalyEntry(
        id = this[AnomalyEvents.id],
        detectedAt = this[AnomalyEvents.detectedAt],
        serviceName = this[AnomalyEvents.serviceName],
        groupName = this[AnomalyEvents.groupName],
        metric = this[AnomalyEvents.metric],
        anomalyType = this[AnomalyEvents.anomalyType],
        value = this[AnomalyEvents.value],
        baseline = this[AnomalyEvents.baseline],
        zscore = this[AnomalyEvents.zscore],
        severity = this[AnomalyEvents.severity],
        resolved = this[AnomalyEvents.resolved],
        resolvedAt = this[AnomalyEvents.resolvedAt]
    )
}
