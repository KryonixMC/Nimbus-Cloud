package dev.nimbuspowered.nimbus.module.perms

import dev.nimbuspowered.nimbus.event.NimbusEvent

/**
 * Factory for permission module events.
 * All events are emitted as [NimbusEvent.ModuleEvent] with moduleId "perms".
 */
object PermsEvents {
    private const val MODULE_ID = "perms"

    fun groupCreated(groupName: String) = NimbusEvent.ModuleEvent(
        MODULE_ID, "PERMISSION_GROUP_CREATED", mapOf("group" to groupName)
    )

    fun groupUpdated(groupName: String) = NimbusEvent.ModuleEvent(
        MODULE_ID, "PERMISSION_GROUP_UPDATED", mapOf("group" to groupName)
    )

    fun groupDeleted(groupName: String) = NimbusEvent.ModuleEvent(
        MODULE_ID, "PERMISSION_GROUP_DELETED", mapOf("group" to groupName)
    )

    fun playerUpdated(uuid: String, playerName: String) = NimbusEvent.ModuleEvent(
        MODULE_ID, "PLAYER_PERMISSIONS_UPDATED", mapOf("uuid" to uuid, "player" to playerName)
    )

    fun trackCreated(trackName: String) = NimbusEvent.ModuleEvent(
        MODULE_ID, "PERMISSION_TRACK_CREATED", mapOf("track" to trackName)
    )

    fun trackDeleted(trackName: String) = NimbusEvent.ModuleEvent(
        MODULE_ID, "PERMISSION_TRACK_DELETED", mapOf("track" to trackName)
    )

    fun playerPromoted(uuid: String, playerName: String, trackName: String, newGroup: String) = NimbusEvent.ModuleEvent(
        MODULE_ID, "PLAYER_PROMOTED", mapOf("uuid" to uuid, "player" to playerName, "track" to trackName, "newGroup" to newGroup)
    )

    fun playerDemoted(uuid: String, playerName: String, trackName: String, newGroup: String) = NimbusEvent.ModuleEvent(
        MODULE_ID, "PLAYER_DEMOTED", mapOf("uuid" to uuid, "player" to playerName, "track" to trackName, "newGroup" to newGroup)
    )
}
