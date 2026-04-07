package dev.nimbuspowered.nimbus.module.players

import org.jetbrains.exposed.sql.Table

/** Tracks each player session (connect → disconnect). */
object PlayerSessions : Table("player_sessions") {
    val id = integer("id").autoIncrement()
    val uuid = varchar("uuid", 36)
    val name = varchar("name", 16)
    val service = varchar("service", 128)
    val group = varchar("group_name", 128)
    val connectedAt = varchar("connected_at", 30)
    val disconnectedAt = varchar("disconnected_at", 30).nullable()

    override val primaryKey = PrimaryKey(id)
    init {
        index(false, uuid, connectedAt)
        index(false, service)
    }
}

/** Aggregated player metadata (first seen, last seen, total playtime). */
object PlayerMeta : Table("player_meta") {
    val uuid = varchar("uuid", 36)
    val name = varchar("name", 16)
    val firstSeen = varchar("first_seen", 30)
    val lastSeen = varchar("last_seen", 30)
    val totalPlaytimeSeconds = long("total_playtime_seconds").default(0)

    override val primaryKey = PrimaryKey(uuid)
}
