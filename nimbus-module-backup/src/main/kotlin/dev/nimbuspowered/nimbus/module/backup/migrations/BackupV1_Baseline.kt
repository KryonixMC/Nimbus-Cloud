package dev.nimbuspowered.nimbus.module.backup.migrations

import dev.nimbuspowered.nimbus.module.Migration
import dev.nimbuspowered.nimbus.module.backup.BackupEntries
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction

/** Baseline migration: backup tables. Version range 5000+ reserved for backup module. */
object BackupV1_Baseline : Migration {
    override val version = 5000
    override val description = "Backup tables: backup_entries"
    override val baseline = true

    override fun Transaction.migrate() {
        SchemaUtils.createMissingTablesAndColumns(BackupEntries)
    }
}
