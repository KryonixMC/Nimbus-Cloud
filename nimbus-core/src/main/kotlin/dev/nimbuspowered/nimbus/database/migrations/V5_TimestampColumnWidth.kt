package dev.nimbuspowered.nimbus.database.migrations

import dev.nimbuspowered.nimbus.module.api.Migration
import org.jetbrains.exposed.sql.Transaction

/**
 * Widens all timestamp VARCHAR columns from 30 to 40 characters.
 * ISO-8601 timestamps with nanosecond precision can reach 35 characters,
 * so VARCHAR(30) risks truncation.
 *
 * SQLite ignores column length constraints, so no ALTER is needed there.
 */
object V5_TimestampColumnWidth : Migration {
    override val version = 5
    override val description = "Widen timestamp columns from VARCHAR(30) to VARCHAR(40)"

    private data class ColumnRef(val table: String, val column: String)

    private val columns = listOf(
        ColumnRef("audit_log", "timestamp"),
        ColumnRef("service_events", "timestamp"),
        ColumnRef("cli_sessions", "connected_at"),
        ColumnRef("cli_sessions", "disconnected_at"),
        ColumnRef("service_metric_samples", "timestamp"),
        ColumnRef("scaling_events", "timestamp"),
        ColumnRef("schema_migrations", "applied_at"),
    )

    override fun Transaction.migrate() {
        val vendor = db.vendor

        if (vendor.contains("sqlite", ignoreCase = true)) return

        val isPostgres = vendor.contains("postgre", ignoreCase = true)

        for (col in columns) {
            if (isPostgres) {
                exec("ALTER TABLE ${col.table} ALTER COLUMN ${col.column} TYPE VARCHAR(40)")
            } else {
                // MySQL / MariaDB
                exec("ALTER TABLE ${col.table} MODIFY COLUMN ${col.column} VARCHAR(40)")
            }
        }
    }
}
