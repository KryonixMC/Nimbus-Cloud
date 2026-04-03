package dev.kryonix.nimbus.module.perms

/**
 * Represents a permission group with a name, permissions, display info, and optional inheritance.
 */
data class PermissionGroup(
    val name: String,
    val default: Boolean = false,
    val prefix: String = "",
    val suffix: String = "",
    val priority: Int = 0,
    val weight: Int = 0,
    val permissions: MutableList<String> = mutableListOf(),
    val contextualPermissions: MutableMap<String, MutableList<PermissionContext>> = mutableMapOf(),
    val parents: MutableList<String> = mutableListOf(),
    val meta: MutableMap<String, String> = mutableMapOf()
)
