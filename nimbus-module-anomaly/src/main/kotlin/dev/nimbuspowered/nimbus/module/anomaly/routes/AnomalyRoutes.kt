package dev.nimbuspowered.nimbus.module.anomaly.routes

import dev.nimbuspowered.nimbus.module.anomaly.AnomalyManager
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

fun Route.anomalyRoutes(manager: AnomalyManager) {

    route("/api/anomaly") {

        // GET /api/anomaly/current — all unresolved anomalies
        get("current") {
            val anomalies = manager.getCurrentAnomalies().map { it.toResponse() }
            call.respond(AnomalyListResponse(anomalies))
        }

        // GET /api/anomaly/history?serviceName=<name>&limit=<n>
        get("history") {
            val serviceName = call.request.queryParameters["serviceName"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
            val history = manager.getHistory(limit.coerceIn(1, 500), serviceName)
            call.respond(AnomalyListResponse(history.map { it.toResponse() }))
        }

        // GET /api/anomaly/stats — summary counts
        get("stats") {
            val stats = manager.getStats()
            call.respond(
                AnomalyStatsResponse(
                    totalDetected = stats.totalDetected,
                    currentActive = stats.currentActive,
                    criticalCount = stats.criticalCount,
                    warningCount = stats.warningCount,
                    mostAffectedGroup = stats.mostAffectedGroup
                )
            )
        }
    }
}

// ── Response DTOs ───────────────────────────────────────────

@Serializable
data class AnomalyListResponse(val anomalies: List<AnomalyEntryResponse>)

@Serializable
data class AnomalyEntryResponse(
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

@Serializable
data class AnomalyStatsResponse(
    val totalDetected: Long,
    val currentActive: Int,
    val criticalCount: Int,
    val warningCount: Int,
    val mostAffectedGroup: String?
)

private fun dev.nimbuspowered.nimbus.module.anomaly.AnomalyEntry.toResponse() = AnomalyEntryResponse(
    id = id,
    detectedAt = detectedAt,
    serviceName = serviceName,
    groupName = groupName,
    metric = metric,
    anomalyType = anomalyType,
    value = value,
    baseline = baseline,
    zscore = zscore,
    severity = severity,
    resolved = resolved,
    resolvedAt = resolvedAt
)
