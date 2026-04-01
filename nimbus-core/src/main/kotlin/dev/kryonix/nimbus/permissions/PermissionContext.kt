package dev.kryonix.nimbus.permissions

/**
 * Context for scoped permissions — limits where a permission or group assignment is active.
 */
data class PermissionContext(
    val server: String? = null,
    val world: String? = null,
    val expiresAt: String? = null // ISO-8601 timestamp, null = permanent
)

/**
 * A permission node with optional context (server/world/expiry).
 */
data class ContextualPermission(
    val permission: String,
    val context: PermissionContext = PermissionContext()
)

/**
 * A player-to-group assignment with optional context.
 */
data class ContextualGroupAssignment(
    val groupName: String,
    val context: PermissionContext = PermissionContext()
)
