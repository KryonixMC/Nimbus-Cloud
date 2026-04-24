package dev.nimbuspowered.nimbus.module.punishments.migrations

import dev.nimbuspowered.nimbus.module.api.Migration
import dev.nimbuspowered.nimbus.module.punishments.Punishments
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction

/**
 * Baseline migration for punishments module.
 * Version range 5000+ reserved for punishments.
 */
object PunishmentsV1_Baseline : Migration {
    override val version = 5000
    override val description = "Punishments table: network-wide bans, tempbans, ipbans, mutes, tempmutes, kicks, warns"
    override val baseline = false

    override fun Transaction.migrate() {
        SchemaUtils.createMissingTablesAndColumns(Punishments)
    }
}
