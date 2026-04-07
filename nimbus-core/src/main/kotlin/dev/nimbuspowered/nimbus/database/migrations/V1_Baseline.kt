package dev.nimbuspowered.nimbus.database.migrations

import dev.nimbuspowered.nimbus.database.PlayerSessions
import dev.nimbuspowered.nimbus.database.ScalingEvents
import dev.nimbuspowered.nimbus.database.ServiceEvents
import dev.nimbuspowered.nimbus.module.Migration
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction

/** Baseline migration: core tables (ServiceEvents, ScalingEvents, PlayerSessions). */
object V1_Baseline : Migration {
    override val version = 1
    override val description = "Core tables: service_events, scaling_events, player_sessions"
    override val baseline = true

    override fun Transaction.migrate() {
        SchemaUtils.createMissingTablesAndColumns(ServiceEvents, ScalingEvents, PlayerSessions)
    }
}
