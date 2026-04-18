package dev.nimbuspowered.nimbus.module.players.routes

import dev.nimbuspowered.nimbus.api.requirePermission
import dev.nimbuspowered.nimbus.module.players.PlayerTracker
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class OnlinePlayer(
    val uuid: String,
    val name: String,
    val service: String,
    val group: String,
    val connectedAt: String
)

@Serializable
data class PlayerHistoryEntry(
    val service: String,
    val group: String,
    val connectedAt: String,
    val disconnectedAt: String?
)

@Serializable
data class PlayerMetaResponse(
    val uuid: String,
    val name: String,
    val firstSeen: String,
    val lastSeen: String,
    val totalPlaytimeSeconds: Long,
    val online: Boolean,
    val currentService: String? = null
)

@Serializable
data class PlayerStatsResponse(
    val online: Int,
    val totalUnique: Long,
    val perService: Map<String, Int>
)

fun Route.playerRoutes(tracker: PlayerTracker) {

    route("/api/players") {

        // GET /api/players/online — All online players
        get("online") {
            if (!call.requirePermission("nimbus.dashboard.players.view")) return@get
            val players = tracker.getOnlinePlayers().map {
                OnlinePlayer(it.uuid, it.name, it.currentService, it.currentGroup, it.connectedAt.toString())
            }
            call.respond(players)
        }

        // GET /api/players/online/{uuid} — Single online player
        get("online/{uuid}") {
            if (!call.requirePermission("nimbus.dashboard.players.view")) return@get
            val uuid = call.parameters["uuid"]!!
            val player = tracker.getPlayer(uuid)
            if (player != null) {
                call.respond(OnlinePlayer(player.uuid, player.name, player.currentService, player.currentGroup, player.connectedAt.toString()))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Player not online"))
            }
        }

        // GET /api/players/history/{uuid} — Session history
        get("history/{uuid}") {
            if (!call.requirePermission("nimbus.dashboard.players.history")) return@get
            val uuid = call.parameters["uuid"]!!
            val limit = call.queryParameters["limit"]?.toIntOrNull() ?: 20
            val history = tracker.getSessionHistory(uuid, limit).map {
                PlayerHistoryEntry(
                    service = it["service"] ?: "",
                    group = it["group"] ?: "",
                    connectedAt = it["connectedAt"] ?: "",
                    disconnectedAt = it["disconnectedAt"]
                )
            }
            call.respond(history)
        }

        // GET /api/players/info/{uuid} — Player meta + online status
        get("info/{uuid}") {
            if (!call.requirePermission("nimbus.dashboard.players.view")) return@get
            val uuid = call.parameters["uuid"]!!
            val meta = tracker.getPlayerMeta(uuid)
            if (meta != null) {
                val online = tracker.getPlayer(uuid)
                call.respond(PlayerMetaResponse(
                    uuid = meta["uuid"]!!,
                    name = meta["name"]!!,
                    firstSeen = meta["firstSeen"]!!,
                    lastSeen = meta["lastSeen"]!!,
                    totalPlaytimeSeconds = meta["totalPlaytimeSeconds"]!!.toLong(),
                    online = online != null,
                    currentService = online?.currentService
                ))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Player not found"))
            }
        }

        // GET /api/players/all — All known players (search + paginate)
        get("all") {
            if (!call.requirePermission("nimbus.dashboard.players.view")) return@get
            val query = call.queryParameters["q"] ?: ""
            val limit = call.queryParameters["limit"]?.toIntOrNull() ?: 50

            val players = if (query.isNotBlank()) {
                tracker.searchPlayers(query, limit)
            } else {
                tracker.getRecentPlayers(limit)
            }

            val onlineUuids = tracker.getOnlinePlayers().map { it.uuid }.toSet()
            val result = players.map {
                mapOf(
                    "uuid" to it["uuid"]!!,
                    "name" to it["name"]!!,
                    "firstSeen" to it["firstSeen"]!!,
                    "lastSeen" to it["lastSeen"]!!,
                    "totalPlaytimeSeconds" to it["totalPlaytimeSeconds"]!!,
                    "online" to onlineUuids.contains(it["uuid"]).toString()
                )
            }
            call.respond(result)
        }

        // GET /api/players/stats — Aggregate stats
        get("stats") {
            if (!call.requirePermission("nimbus.dashboard.players.view")) return@get
            val stats = tracker.getStats()
            @Suppress("UNCHECKED_CAST")
            call.respond(PlayerStatsResponse(
                online = stats["online"] as Int,
                totalUnique = stats["totalUnique"] as Long,
                perService = stats["perService"] as Map<String, Int>
            ))
        }
    }
}
