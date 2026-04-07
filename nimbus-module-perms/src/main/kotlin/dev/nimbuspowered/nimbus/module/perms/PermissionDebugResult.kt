package dev.nimbuspowered.nimbus.module.perms

/**
 * Result of a permission debug check — explains WHY a permission was granted or denied.
 */
data class PermissionDebugResult(
    val permission: String,
    val result: Boolean,
    val reason: String,
    val chain: List<DebugStep>
)

/**
 * A single step in the permission resolution chain.
 */
data class DebugStep(
    val source: String,      // group name
    val permission: String,  // the matching permission node
    val type: String,        // "exact", "wildcard", "negated", "inherited"
    val granted: Boolean
)
