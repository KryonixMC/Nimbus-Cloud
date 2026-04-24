package dev.nimbuspowered.nimbus.database.migrations

import dev.nimbuspowered.nimbus.database.CliSessions
import dev.nimbuspowered.nimbus.module.api.Migration
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction

/** Adds the cli_sessions table for Remote CLI connection tracking. */
object V3_CliSessions : Migration {
    override val version = 3
    override val description = "CLI sessions table"

    override fun Transaction.migrate() {
        SchemaUtils.createMissingTablesAndColumns(CliSessions)
    }
}
