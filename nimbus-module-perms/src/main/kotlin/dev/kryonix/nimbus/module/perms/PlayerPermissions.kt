package dev.kryonix.nimbus.module.perms

/**
 * Tracks which permission groups a player belongs to.
 */
data class PlayerEntry(
    val name: String,
    val groups: MutableList<String> = mutableListOf(),
    val groupContexts: MutableMap<String, MutableList<PermissionContext>> = mutableMapOf(),
    val meta: MutableMap<String, String> = mutableMapOf()
)
