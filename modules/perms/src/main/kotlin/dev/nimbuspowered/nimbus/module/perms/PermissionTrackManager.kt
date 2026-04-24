package dev.nimbuspowered.nimbus.module.perms

import dev.nimbuspowered.nimbus.database.DatabaseManager
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase

internal class PermissionTrackManager(
    private val tracks: MutableMap<String, PermissionTrack>,
    private val db: DatabaseManager,
    private val groups: Map<String, PermissionGroup>,
    private val players: Map<String, PlayerEntry>,
    private val setPlayerGroup: suspend (uuid: String, name: String, groupName: String) -> Unit,
    private val removePlayerGroup: suspend (uuid: String, groupName: String) -> Unit
) {

    fun getAllTracks(): List<PermissionTrack> = tracks.values.toList()

    fun getTrack(name: String): PermissionTrack? =
        tracks.values.find { it.name.equals(name, ignoreCase = true) }

    suspend fun createTrack(name: String, trackGroups: List<String>): PermissionTrack {
        require(getTrack(name) == null) { "Track '$name' already exists" }
        require(trackGroups.size >= 2) { "Track must have at least 2 groups" }
        for (g in trackGroups) {
            getGroup(g) ?: throw IllegalArgumentException("Group '$g' not found")
        }

        val track = PermissionTrack(name = name, groups = trackGroups)
        tracks[name.lowercase()] = track

        db.query {
            PermissionTracks.insert {
                it[PermissionTracks.name] = name
                it[groups] = Json.encodeToString(trackGroups)
            }
        }

        return track
    }

    suspend fun deleteTrack(name: String) {
        val track = getTrack(name) ?: throw IllegalArgumentException("Track '$name' not found")
        tracks.remove(track.name.lowercase())

        db.query {
            PermissionTracks.deleteWhere { PermissionTracks.name.lowerCase() eq name.lowercase() }
        }
    }

    suspend fun promote(uuid: String, trackName: String): String? {
        val track = getTrack(trackName) ?: throw IllegalArgumentException("Track '$trackName' not found")
        val entry = players[uuid] ?: throw IllegalArgumentException("Player not found")

        var currentIndex = -1
        for ((i, groupName) in track.groups.withIndex()) {
            if (entry.groups.any { it.equals(groupName, ignoreCase = true) }) {
                currentIndex = i
            }
        }

        if (currentIndex >= track.groups.size - 1) return null

        val newGroup = track.groups[currentIndex + 1]

        if (currentIndex >= 0) {
            removePlayerGroup(uuid, track.groups[currentIndex])
        }

        setPlayerGroup(uuid, entry.name, newGroup)
        return newGroup
    }

    suspend fun demote(uuid: String, trackName: String): String? {
        val track = getTrack(trackName) ?: throw IllegalArgumentException("Track '$trackName' not found")
        val entry = players[uuid] ?: throw IllegalArgumentException("Player not found")

        var currentIndex = -1
        for ((i, groupName) in track.groups.withIndex()) {
            if (entry.groups.any { it.equals(groupName, ignoreCase = true) }) {
                currentIndex = i
            }
        }

        if (currentIndex <= 0) return null

        val newGroup = track.groups[currentIndex - 1]

        removePlayerGroup(uuid, track.groups[currentIndex])

        setPlayerGroup(uuid, entry.name, newGroup)
        return newGroup
    }

    private fun getGroup(name: String): PermissionGroup? =
        groups.values.find { it.name.equals(name, ignoreCase = true) }
}
