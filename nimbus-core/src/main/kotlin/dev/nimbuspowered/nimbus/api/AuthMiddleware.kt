package dev.nimbuspowered.nimbus.api

import dev.nimbuspowered.nimbus.module.api.AuthPrincipal
import dev.nimbuspowered.nimbus.module.api.hasPermission
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable

/**
 * Auth middleware v0.11 — adds an [AuthPrincipal] layer on top of the existing
 * Ktor bearer authentication so routes can make permission-aware decisions while
 * keeping full backwards compatibility with API tokens.
 *
 * The existing [UserIdPrincipal] stays for any code that already reads it; new
 * code should use [AuthPrincipal] via [call.authPrincipal].
 *
 * Phase 1: dashboard user sessions are validated opportunistically when a
 * session-token lookup is supplied (see [NimbusApi] wiring). Without a session
 * lookup, this file only materialises API-token principals.
 */

val AuthPrincipalKey: AttributeKey<AuthPrincipal> = AttributeKey("NimbusAuthPrincipal")

/** Returns the auth principal for this call, or [AuthPrincipal.Anonymous] if unset. */
val ApplicationCall.authPrincipal: AuthPrincipal
    get() = attributes.getOrNull(AuthPrincipalKey) ?: run {
        // Fallback: if Ktor already resolved a UserIdPrincipal (legacy bearer), treat it as ApiToken.
        val uid = principal<UserIdPrincipal>()
        if (uid != null) AuthPrincipal.ApiToken(uid.name) else AuthPrincipal.Anonymous
    }

fun ApplicationCall.setAuthPrincipal(principal: AuthPrincipal) {
    attributes.put(AuthPrincipalKey, principal)
}

/** Convenience: does the current call hold [node]? */
fun ApplicationCall.hasPermission(node: String): Boolean = authPrincipal.hasPermission(node)

@Serializable
data class ForbiddenResponse(
    val success: Boolean = false,
    val message: String,
    val error: String = ApiErrors.FORBIDDEN,
    val required: String
)

/**
 * Short-circuit a route if the caller lacks [node]. Responds 403 with the
 * machine-readable shape `{error: "FORBIDDEN", required: "nimbus.dashboard.xxx"}`
 * so the dashboard can render a helpful "missing permission" UI.
 *
 * Phase 2 will sprinkle `requirePermission()` across every existing route —
 * in Phase 1 the helper is available but intentionally unused so no behaviour
 * changes for existing consumers.
 */
suspend fun ApplicationCall.requirePermission(node: String): Boolean {
    if (authPrincipal.hasPermission(node)) return true
    respond(HttpStatusCode.Forbidden, ForbiddenResponse(
        message = "Missing permission: $node",
        required = node
    ))
    return false
}
