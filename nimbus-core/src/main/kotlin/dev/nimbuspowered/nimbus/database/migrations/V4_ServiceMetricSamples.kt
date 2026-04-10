package dev.nimbuspowered.nimbus.database.migrations

import dev.nimbuspowered.nimbus.database.ServiceMetricSamples
import dev.nimbuspowered.nimbus.module.Migration
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction

/**
 * Adds the `service_metric_samples` table so that historical memory and
 * player count data can be rendered in the dashboard charts, instead of
 * starting from blank every time a service detail page is opened.
 */
object V4_ServiceMetricSamples : Migration {
    override val version = 4
    override val description = "Service metric samples (memory + players over time)"

    override fun Transaction.migrate() {
        SchemaUtils.createMissingTablesAndColumns(ServiceMetricSamples)
    }
}
