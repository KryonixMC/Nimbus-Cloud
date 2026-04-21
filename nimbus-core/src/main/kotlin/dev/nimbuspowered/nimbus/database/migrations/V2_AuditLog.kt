package dev.nimbuspowered.nimbus.database.migrations

import dev.nimbuspowered.nimbus.database.AuditLog
import dev.nimbuspowered.nimbus.module.api.Migration
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction

/** Adds the core audit_log table. */
object V2_AuditLog : Migration {
    override val version = 2
    override val description = "Audit log table"

    override fun Transaction.migrate() {
        SchemaUtils.createMissingTablesAndColumns(AuditLog)
    }
}
