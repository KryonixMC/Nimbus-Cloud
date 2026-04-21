package dev.nimbuspowered.nimbus.module.api

import java.util.UUID

/**
 * Represents the effective identity that made an API call.
 *
 * Introduced in v0.11 to support user-level (Minecraft-account) dashboard sessions
 * in parallel with the pre-existing API-token model. API tokens keep behaving as
 * admin-superuser for full backwards compatibility.
 */
sealed class AuthPrincipal {

    /** Unauthenticated request (e.g. public endpoints). */
    data object Anonymous : AuthPrincipal()

    /** Machine-level API token — implicit admin. */
    data class ApiToken(val subject: String = "nimbus-admin") : AuthPrincipal()

    /** Dashboard user session tied to a Minecraft account. */
    data class UserSession(
        val uuid: UUID,
        val name: String,
        val permissions: PermissionSet,
        val sessionId: String,
        val expiresAt: Long
    ) : AuthPrincipal()
}

/**
 * Snapshot of the permission nodes resolved for a user at login time.
 *
 * Wildcard semantics mirror the existing Perms Module (`*` and `.`-segment
 * expansion). Phase 1 ships a minimal matcher; Phase 2 wires this through the
 * real Perms module with inheritance + tracks.
 */
class PermissionSet(private val nodes: Set<String>) {

    fun asSet(): Set<String> = nodes

    /**
     * Returns `true` if the node is granted either directly, via a wildcard
     * ancestor, or via `nimbus.dashboard.admin` (the short-circuit superuser
     * node).
     */
    fun has(node: String): Boolean {
        if (nodes.isEmpty()) return false
        if (node in nodes) return true
        if (ADMIN_NODE in nodes) return true
        if (WILDCARD in nodes) return true

        // Check wildcard ancestors: a.b.c matched by a.b.* and a.*
        val parts = node.split('.')
        for (i in parts.size - 1 downTo 1) {
            val prefix = parts.subList(0, i).joinToString(".")
            if ("$prefix.*" in nodes) return true
        }
        return false
    }

    companion object {
        const val ADMIN_NODE = "nimbus.dashboard.admin"
        const val WILDCARD = "*"
        val EMPTY = PermissionSet(emptySet())
        fun of(vararg nodes: String) = PermissionSet(nodes.toSet())
    }
}

/**
 * Central permission gate.
 *
 * - [AuthPrincipal.ApiToken] always passes (backwards compat — machine tokens are admin).
 * - [AuthPrincipal.UserSession] delegates to [PermissionSet.has].
 * - [AuthPrincipal.Anonymous] always fails.
 */
fun AuthPrincipal.hasPermission(node: String): Boolean = when (this) {
    is AuthPrincipal.ApiToken -> true
    is AuthPrincipal.UserSession -> permissions.has(node)
    AuthPrincipal.Anonymous -> false
}
