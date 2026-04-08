package dev.nimbuspowered.nimbus.module.players

import dev.nimbuspowered.nimbus.database.DatabaseManager
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class TrackedPlayer(
    val uuid: String,
    val name: String,
    val currentService: String,
    val currentGroup: String,
    val connectedAt: Instant,
    val sessionId: Int
)

class PlayerTracker(private val db: DatabaseManager) {
    private val logger = LoggerFactory.getLogger(PlayerTracker::class.java)

    /** In-memory map of currently online players, keyed by UUID. */
    private val onlinePlayers = ConcurrentHashMap<String, TrackedPlayer>()

    fun getOnlinePlayers(): Collection<TrackedPlayer> = onlinePlayers.values

    fun getOnlineCount(): Int = onlinePlayers.size

    fun getPlayer(uuid: String): TrackedPlayer? = onlinePlayers[uuid]

    fun getPlayerByName(name: String): TrackedPlayer? =
        onlinePlayers.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun getPlayersOnService(serviceName: String): List<TrackedPlayer> =
        onlinePlayers.values.filter { it.currentService == serviceName }

    suspend fun onPlayerConnect(uuid: String, name: String, service: String, group: String) {
        val now = Instant.now().toString()
        val sessionId = db.query {
            // Upsert player meta
            val existing = PlayerMeta.selectAll().where { PlayerMeta.uuid eq uuid }.firstOrNull()
            if (existing != null) {
                PlayerMeta.update({ PlayerMeta.uuid eq uuid }) {
                    it[PlayerMeta.name] = name
                    it[lastSeen] = now
                }
            } else {
                PlayerMeta.insert {
                    it[PlayerMeta.uuid] = uuid
                    it[PlayerMeta.name] = name
                    it[firstSeen] = now
                    it[lastSeen] = now
                }
            }

            // Insert session
            PlayerSessions.insert {
                it[PlayerSessions.uuid] = uuid
                it[PlayerSessions.name] = name
                it[PlayerSessions.service] = service
                it[PlayerSessions.group] = group
                it[connectedAt] = now
            } get PlayerSessions.id
        }

        onlinePlayers[uuid] = TrackedPlayer(uuid, name, service, group, Instant.now(), sessionId)
        logger.debug("Player connected: {} ({}) on {}", name, uuid, service)
    }

    suspend fun onPlayerDisconnect(uuid: String, name: String, service: String) {
        val now = Instant.now().toString()
        val tracked = onlinePlayers.remove(uuid)
        if (tracked != null) {
            val durationSeconds = java.time.Duration.between(tracked.connectedAt, Instant.now()).seconds
            db.query {
                // Close session
                PlayerSessions.update({ PlayerSessions.id eq tracked.sessionId }) {
                    it[disconnectedAt] = now
                }
                // Update total playtime
                PlayerMeta.update({ PlayerMeta.uuid eq uuid }) {
                    with(SqlExpressionBuilder) {
                        it[totalPlaytimeSeconds] = totalPlaytimeSeconds + durationSeconds
                    }
                    it[lastSeen] = now
                }
            }
        }
        logger.debug("Player disconnected: {} ({}) from {}", name, uuid, service)
    }

    suspend fun onPlayerServerSwitch(uuid: String, name: String, fromService: String, toService: String, toGroup: String) {
        val now = Instant.now().toString()
        val tracked = onlinePlayers[uuid]

        // Close old session and open new one
        if (tracked != null) {
            val durationSeconds = java.time.Duration.between(tracked.connectedAt, Instant.now()).seconds
            val newSessionId = db.query {
                // Close old session
                PlayerSessions.update({ PlayerSessions.id eq tracked.sessionId }) {
                    it[disconnectedAt] = now
                }
                // Update playtime
                PlayerMeta.update({ PlayerMeta.uuid eq uuid }) {
                    with(SqlExpressionBuilder) {
                        it[totalPlaytimeSeconds] = totalPlaytimeSeconds + durationSeconds
                    }
                    it[lastSeen] = now
                }
                // Open new session
                PlayerSessions.insert {
                    it[PlayerSessions.uuid] = uuid
                    it[PlayerSessions.name] = name
                    it[service] = toService
                    it[group] = toGroup
                    it[connectedAt] = now
                } get PlayerSessions.id
            }
            onlinePlayers[uuid] = TrackedPlayer(uuid, name, toService, toGroup, Instant.now(), newSessionId)
        }
        logger.debug("Player switched: {} ({}) {} -> {}", name, uuid, fromService, toService)
    }

    /**
     * Resolves a player name to UUID. Checks online players first, then DB.
     */
    suspend fun resolveUuid(name: String): String? {
        // Check online players first
        val online = getPlayerByName(name)
        if (online != null) return online.uuid

        // Fall back to DB lookup
        return db.query {
            PlayerMeta.selectAll()
                .where { PlayerMeta.name eq name }
                .firstOrNull()
                ?.get(PlayerMeta.uuid)
        }
    }

    suspend fun getSessionHistory(uuid: String, limit: Int = 20): List<Map<String, String?>> {
        return db.query {
            PlayerSessions.selectAll()
                .where { PlayerSessions.uuid eq uuid }
                .orderBy(PlayerSessions.connectedAt, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    mapOf(
                        "service" to row[PlayerSessions.service],
                        "group" to row[PlayerSessions.group],
                        "connectedAt" to row[PlayerSessions.connectedAt],
                        "disconnectedAt" to row[PlayerSessions.disconnectedAt]
                    )
                }
        }
    }

    suspend fun getPlayerMeta(uuid: String): Map<String, String>? {
        return db.query {
            PlayerMeta.selectAll().where { PlayerMeta.uuid eq uuid }.firstOrNull()?.let { row ->
                mapOf(
                    "uuid" to row[PlayerMeta.uuid],
                    "name" to row[PlayerMeta.name],
                    "firstSeen" to row[PlayerMeta.firstSeen],
                    "lastSeen" to row[PlayerMeta.lastSeen],
                    "totalPlaytimeSeconds" to row[PlayerMeta.totalPlaytimeSeconds].toString()
                )
            }
        }
    }

    suspend fun searchPlayers(query: String, limit: Int = 50): List<Map<String, String>> {
        return db.query {
            val q = "%${query.lowercase()}%"
            PlayerMeta.selectAll()
                .where { PlayerMeta.name.lowerCase() like q }
                .orderBy(PlayerMeta.lastSeen, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    mapOf(
                        "uuid" to row[PlayerMeta.uuid],
                        "name" to row[PlayerMeta.name],
                        "firstSeen" to row[PlayerMeta.firstSeen],
                        "lastSeen" to row[PlayerMeta.lastSeen],
                        "totalPlaytimeSeconds" to row[PlayerMeta.totalPlaytimeSeconds].toString()
                    )
                }
        }
    }

    suspend fun getRecentPlayers(limit: Int = 50): List<Map<String, String>> {
        return db.query {
            PlayerMeta.selectAll()
                .orderBy(PlayerMeta.lastSeen, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    mapOf(
                        "uuid" to row[PlayerMeta.uuid],
                        "name" to row[PlayerMeta.name],
                        "firstSeen" to row[PlayerMeta.firstSeen],
                        "lastSeen" to row[PlayerMeta.lastSeen],
                        "totalPlaytimeSeconds" to row[PlayerMeta.totalPlaytimeSeconds].toString()
                    )
                }
        }
    }

    suspend fun getStats(): Map<String, Any> {
        val online = onlinePlayers.size
        val total = db.query {
            PlayerMeta.selectAll().count()
        }
        return mapOf(
            "online" to online,
            "totalUnique" to total,
            "perService" to onlinePlayers.values.groupBy { it.currentService }.mapValues { it.value.size }
        )
    }
}
