package dev.nimbuspowered.nimbus.database.migrations

import dev.nimbuspowered.nimbus.module.api.Migration
import org.jetbrains.exposed.sql.Transaction

/**
 * Adds missing indexes for query performance, widens the actor column
 * to VARCHAR(255), and changes high-volume id columns from INT to BIGINT
 * to prevent integer overflow on long-running deployments.
 *
 * SQLite uses dynamic typing (INTEGER is always 64-bit) and has no
 * ALTER COLUMN support, so column-type changes are skipped there.
 */
object V6_IndexesAndColumnFixes : Migration {
    override val version = 6
    override val description = "Add metrics indexes, widen actor column, upgrade id columns to BIGINT"

    private data class IdColumn(val table: String, val column: String)

    private val idColumns = listOf(
        IdColumn("audit_log", "id"),
        IdColumn("service_events", "id"),
        IdColumn("service_metric_samples", "id"),
        IdColumn("scaling_events", "id"),
    )

    override fun Transaction.migrate() {
        val vendor = db.vendor
        val isSqlite = vendor.contains("sqlite", ignoreCase = true)
        val isPostgres = vendor.contains("postgre", ignoreCase = true)

        // ── Indexes (all vendors) ──────────────────────────────────

        // Composite index on service_metric_samples for group-based time queries
        if (isPostgres || isSqlite) {
            exec("CREATE INDEX IF NOT EXISTS idx_sms_group_ts ON service_metric_samples (group_name, timestamp)")
        } else {
            // MySQL: IF NOT EXISTS supported since 8.0, use safe wrapper
            safeCreateIndex("idx_sms_group_ts", "service_metric_samples", "group_name, timestamp")
        }

        if (isSqlite) return

        // ── Widen actor column to VARCHAR(255) ─────────────────────

        if (isPostgres) {
            exec("ALTER TABLE audit_log ALTER COLUMN actor TYPE VARCHAR(255)")
        } else {
            exec("ALTER TABLE audit_log MODIFY COLUMN actor VARCHAR(255)")
        }

        // ── Upgrade id columns from INT to BIGINT ──────────────────

        for (col in idColumns) {
            if (isPostgres) {
                exec("ALTER TABLE ${col.table} ALTER COLUMN ${col.column} TYPE BIGINT")
            } else {
                exec("ALTER TABLE ${col.table} MODIFY COLUMN ${col.column} BIGINT AUTO_INCREMENT")
            }
        }
    }

    /** MySQL-safe index creation that ignores "duplicate key name" errors. */
    private fun Transaction.safeCreateIndex(name: String, table: String, columns: String) {
        try {
            exec("CREATE INDEX $name ON $table ($columns)")
        } catch (_: Exception) {
            // Index already exists — ignore
        }
    }
}
