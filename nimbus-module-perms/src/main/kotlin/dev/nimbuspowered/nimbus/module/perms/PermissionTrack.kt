package dev.nimbuspowered.nimbus.module.perms

/**
 * A promotion/demotion track — an ordered list of groups from lowest to highest rank.
 */
data class PermissionTrack(
    val name: String,
    val groups: List<String> // ordered: lowest rank first, highest last
)
