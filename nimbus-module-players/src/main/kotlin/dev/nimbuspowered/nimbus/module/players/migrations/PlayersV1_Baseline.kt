package dev.nimbuspowered.nimbus.module.players.migrations

import dev.nimbuspowered.nimbus.module.Migration
import dev.nimbuspowered.nimbus.module.players.PlayerMeta
import dev.nimbuspowered.nimbus.module.players.PlayerSessions
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction

/** Baseline migration: player tracking tables. Version range 3000+ reserved for players module. */
object PlayersV1_Baseline : Migration {
    override val version = 3000
    override val description = "Player tracking tables: sessions, meta"
    override val baseline = false

    override fun Transaction.migrate() {
        SchemaUtils.createMissingTablesAndColumns(PlayerSessions, PlayerMeta)
    }
}
