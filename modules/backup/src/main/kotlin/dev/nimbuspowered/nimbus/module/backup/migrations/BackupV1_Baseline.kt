package dev.nimbuspowered.nimbus.module.backup.migrations

import dev.nimbuspowered.nimbus.module.Migration
import dev.nimbuspowered.nimbus.module.backup.BackupScheduleLog
import dev.nimbuspowered.nimbus.module.backup.Backups
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction

/** Baseline migration for backup module. Version range 7000+ reserved. */
object BackupV1_Baseline : Migration {
    override val version = 7000
    override val description = "Backup records + schedule log"
    override val baseline = false

    override fun Transaction.migrate() {
        SchemaUtils.createMissingTablesAndColumns(Backups, BackupScheduleLog)
    }
}
