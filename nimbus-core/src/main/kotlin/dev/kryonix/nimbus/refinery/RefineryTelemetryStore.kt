package dev.kryonix.nimbus.refinery

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory store for per-service Refinery telemetry data.
 * Holds the latest snapshot from each service that has the Refinery-Nimbus addon.
 */
class RefineryTelemetryStore {

    data class ServiceTelemetry(
        val serviceName: String,
        val groupName: String,
        val tps: Double,
        val mspt: Double,
        val msptP99: Double,
        val tpsLevel: String,
        val memoryUsed: Long,
        val memoryMax: Long,
        val memoryPct: Int,
        val entityCount: Int,
        val chunksLoaded: Int,
        val players: Int,
        val interventionMode: String,
        val activeModules: List<String>,
        val moduleTimings: Map<String, Double>,
        val worldBreakdown: String,
        val lastUpdate: Instant
    )

    data class ScalingHint(
        val serviceName: String,
        val groupName: String,
        val status: String, // OVERLOADED, UNDERLOADED, NORMAL
        val tps: Double,
        val players: Int,
        val reason: String,
        val consecutiveReports: Int,
        val timestamp: Instant
    )

    data class CrashReport(
        val serviceName: String,
        val groupName: String,
        val level: String,
        val actions: List<String>,
        val tps: Double,
        val players: Int,
        val dumpSummary: String,
        val timestamp: Instant
    )

    data class ServiceAnnouncement(
        val serviceName: String,
        val groupName: String,
        val refineryVersion: String,
        val addonVersion: String,
        val modules: Map<String, Boolean>,
        val interventionMode: String,
        val timestamp: Instant
    )

    private val telemetry = ConcurrentHashMap<String, ServiceTelemetry>()
    private val scalingHints = ConcurrentHashMap<String, ScalingHint>()
    private val crashReports = mutableListOf<CrashReport>()
    private val announcements = ConcurrentHashMap<String, ServiceAnnouncement>()
    private val templateDetections = ConcurrentHashMap<String, RefineryIntegration.TemplateDetection>()

    fun updateTelemetry(serviceName: String, data: Map<String, String>) {
        val groupName = announcements[serviceName]?.groupName ?: "unknown"

        telemetry[serviceName] = ServiceTelemetry(
            serviceName = serviceName,
            groupName = groupName,
            tps = data["tps"]?.toDoubleOrNull() ?: 0.0,
            mspt = data["mspt"]?.toDoubleOrNull() ?: 0.0,
            msptP99 = data["mspt_p99"]?.toDoubleOrNull() ?: 0.0,
            tpsLevel = data["tps_level"] ?: "UNKNOWN",
            memoryUsed = data["memory_used"]?.toLongOrNull() ?: 0,
            memoryMax = data["memory_max"]?.toLongOrNull() ?: 0,
            memoryPct = data["memory_pct"]?.toIntOrNull() ?: 0,
            entityCount = data["entities_total"]?.toIntOrNull() ?: 0,
            chunksLoaded = data["chunks_loaded"]?.toIntOrNull() ?: 0,
            players = data["players"]?.toIntOrNull() ?: 0,
            interventionMode = data["intervention_mode"] ?: "UNKNOWN",
            activeModules = data["active_modules"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
            moduleTimings = parseModuleTimings(data["module_timings"]),
            worldBreakdown = data["world_breakdown"] ?: "",
            lastUpdate = Instant.now()
        )
    }

    fun updateScalingHint(data: Map<String, String>) {
        val serviceName = data["service"] ?: return
        val groupName = data["group"] ?: "unknown"

        scalingHints[serviceName] = ScalingHint(
            serviceName = serviceName,
            groupName = groupName,
            status = data["status"] ?: "UNKNOWN",
            tps = data["tps"]?.toDoubleOrNull() ?: 0.0,
            players = data["players"]?.toIntOrNull() ?: 0,
            reason = data["reason"] ?: "",
            consecutiveReports = data["consecutive_reports"]?.toIntOrNull() ?: 0,
            timestamp = Instant.now()
        )
    }

    fun addCrashReport(data: Map<String, String>) {
        val report = CrashReport(
            serviceName = data["service"] ?: "unknown",
            groupName = data["group"] ?: "unknown",
            level = data["level"] ?: "UNKNOWN",
            actions = data["actions"]?.split(",") ?: emptyList(),
            tps = data["tps"]?.toDoubleOrNull() ?: 0.0,
            players = data["players"]?.toIntOrNull() ?: 0,
            dumpSummary = data["dump_summary"] ?: "",
            timestamp = Instant.now()
        )

        synchronized(crashReports) {
            crashReports.add(report)
            // Keep last 50 reports
            if (crashReports.size > 50) crashReports.removeAt(0)
        }
    }

    fun registerAnnouncement(data: Map<String, String>) {
        val serviceName = data["service"] ?: return
        val groupName = data["group"] ?: "unknown"

        val modules = data["modules"]?.split(",")?.associate { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) parts[0] to (parts[1] == "true") else entry to false
        } ?: emptyMap()

        announcements[serviceName] = ServiceAnnouncement(
            serviceName = serviceName,
            groupName = groupName,
            refineryVersion = data["refinery_version"] ?: "unknown",
            addonVersion = data["addon_version"] ?: "unknown",
            modules = modules,
            interventionMode = data["intervention_mode"] ?: "UNKNOWN",
            timestamp = Instant.now()
        )
    }

    fun removeService(serviceName: String) {
        telemetry.remove(serviceName)
        scalingHints.remove(serviceName)
        announcements.remove(serviceName)
    }

    // -- Query methods --

    fun getAllTelemetry(): List<ServiceTelemetry> =
        telemetry.values.sortedBy { it.serviceName }

    fun getTelemetry(serviceName: String): ServiceTelemetry? =
        telemetry[serviceName]

    fun getOverloadedServices(): List<ScalingHint> =
        scalingHints.values.filter { it.status == "OVERLOADED" }.sortedByDescending { it.consecutiveReports }

    fun getRecentCrashReports(limit: Int = 10): List<CrashReport> =
        synchronized(crashReports) { crashReports.takeLast(limit).reversed() }

    fun getAnnouncements(): List<ServiceAnnouncement> =
        announcements.values.sortedBy { it.serviceName }

    fun getRefineryServiceCount(): Int = announcements.size

    // -- Template detection --

    fun updateTemplateDetections(detections: Map<String, RefineryIntegration.TemplateDetection>) {
        templateDetections.clear()
        templateDetections.putAll(detections)
    }

    fun getTemplateDetections(): Map<String, RefineryIntegration.TemplateDetection> =
        templateDetections.toMap()

    fun isGroupRefineryEnabled(groupName: String): Boolean =
        templateDetections[groupName]?.hasCoreJar == true

    fun isGroupAddonEnabled(groupName: String): Boolean =
        templateDetections[groupName]?.hasAddonJar == true

    private fun parseModuleTimings(raw: String?): Map<String, Double> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) parts[0] to (parts[1].toDoubleOrNull() ?: 0.0) else null
        }.toMap()
    }
}
