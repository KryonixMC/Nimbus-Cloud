package dev.kryonix.nimbus.permissions

/**
 * Tracks which permission groups a player belongs to.
 */
data class PlayerEntry(
    val name: String,
    val groups: MutableList<String> = mutableListOf(),
    val meta: MutableMap<String, String> = mutableMapOf()
)
