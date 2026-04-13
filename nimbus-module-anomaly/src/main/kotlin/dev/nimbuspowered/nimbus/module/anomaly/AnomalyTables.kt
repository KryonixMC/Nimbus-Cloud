package dev.nimbuspowered.nimbus.module.anomaly

import org.jetbrains.exposed.sql.Table

/** Persisted record of every anomaly detection event. */
object AnomalyEvents : Table("anomaly_events") {
    val id = long("id").autoIncrement()
    val detectedAt = varchar("detected_at", 40)
    val serviceName = varchar("service_name", 128)
    val groupName = varchar("group_name", 64).nullable()

    /** One of: "memory_used_mb", "player_count" */
    val metric = varchar("metric", 32)

    /** One of: "zscore", "peer_outlier" */
    val anomalyType = varchar("anomaly_type", 32)

    val value = double("value")
    val baseline = double("baseline")
    val zscore = double("zscore")

    /** One of: "warning", "critical" */
    val severity = varchar("severity", 16)

    val resolved = bool("resolved").default(false)
    val resolvedAt = varchar("resolved_at", 40).nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, detectedAt)
        index(false, serviceName, detectedAt)
        index(false, resolved)
    }
}
