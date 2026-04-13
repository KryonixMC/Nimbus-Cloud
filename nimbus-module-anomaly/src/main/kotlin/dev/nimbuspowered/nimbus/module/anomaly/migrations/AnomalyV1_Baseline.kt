package dev.nimbuspowered.nimbus.module.anomaly.migrations

import dev.nimbuspowered.nimbus.module.Migration
import dev.nimbuspowered.nimbus.module.anomaly.AnomalyEvents
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction

/**
 * Baseline migration: anomaly detection tables.
 * Version range 6000+ reserved for the anomaly module.
 */
object AnomalyV1_Baseline : Migration {
    override val version = 6000
    override val description = "Anomaly detection tables: anomaly_events"
    override val baseline = true

    override fun Transaction.migrate() {
        SchemaUtils.createMissingTablesAndColumns(AnomalyEvents)
    }
}
