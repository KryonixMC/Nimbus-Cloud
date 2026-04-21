package dev.nimbuspowered.nimbus.module.auth.routes

import dev.nimbuspowered.nimbus.api.ApiErrors
import dev.nimbuspowered.nimbus.api.ApiMessage
import dev.nimbuspowered.nimbus.api.apiError
import dev.nimbuspowered.nimbus.module.auth.service.SessionService
import dev.nimbuspowered.nimbus.module.auth.service.TotpService
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class TotpEnrollResponse(
    val secret: String,
    val otpauthUri: String,
    val recoveryCodes: List<String>
)

@Serializable
data class TotpCodeRequest(val code: String)

@Serializable
data class TotpStatusResponse(
    val enabled: Boolean,
    val pendingEnrollment: Boolean,
    val recoveryCodesRemaining: Int
)

/**
 * `/api/profile/` routes — user self-service. All endpoints expect a dashboard
 * session token in the Authorization header (Bearer). API-token callers
 * are **not** accepted here because TOTP is user-scoped: an API token has
 * no associated uuid, so there's nothing to enroll.
 */
fun Route.profileRoutes(
    sessionService: SessionService,
    totpService: TotpService
) {
    route("/api/profile") {

        get("totp/status") {
            val session = requireUserSession(call, sessionService) ?: return@get
            val uuid = session.uuid.toString()
            val state = totpService.state(uuid)
            call.respond(TotpStatusResponse(
                enabled = state.enabled,
                pendingEnrollment = state.pendingEnrollment,
                recoveryCodesRemaining = totpService.recoveryCodesRemaining(uuid)
            ))
        }

        /**
         * Start enrollment: returns the secret + otpauth URI + 10 recovery
         * codes. TOTP is *not* yet active — the user must call `confirm`
         * with a live code first. Calling enroll again replaces any
         * pending (unconfirmed) enrollment; if TOTP is already active the
         * caller must `disable` first (returns 409 otherwise).
         */
        post("totp/enroll") {
            val session = requireUserSession(call, sessionService) ?: return@post
            val state = totpService.state(session.uuid.toString())
            if (state.enabled) {
                return@post call.respond(HttpStatusCode.Conflict,
                    apiError("TOTP already enabled — disable it first", AuthErrors.AUTH_TOTP_ALREADY_ENABLED))
            }
            val material = totpService.enroll(session.uuid.toString(), session.name)
            call.respond(TotpEnrollResponse(
                secret = material.secretBase32,
                otpauthUri = material.otpauthUri,
                recoveryCodes = material.recoveryCodes
            ))
        }

        post("totp/confirm") {
            val session = requireUserSession(call, sessionService) ?: return@post
            val req = runCatching { call.receive<TotpCodeRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest,
                    apiError("code required", ApiErrors.VALIDATION_FAILED))
            val ok = totpService.confirm(session.uuid.toString(), req.code)
            if (!ok) {
                return@post call.respond(HttpStatusCode.Unauthorized,
                    apiError("Invalid TOTP code", AuthErrors.AUTH_TOTP_INVALID))
            }
            call.respond(ApiMessage(success = true, message = "TOTP enabled"))
        }

        post("totp/disable") {
            val session = requireUserSession(call, sessionService) ?: return@post
            val req = runCatching { call.receive<TotpCodeRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest,
                    apiError("code required", ApiErrors.VALIDATION_FAILED))
            val ok = totpService.disable(session.uuid.toString(), req.code)
            if (!ok) {
                return@post call.respond(HttpStatusCode.Unauthorized,
                    apiError("Invalid TOTP code", AuthErrors.AUTH_TOTP_INVALID))
            }
            call.respond(ApiMessage(success = true, message = "TOTP disabled"))
        }
    }
}

private suspend fun requireUserSession(
    call: ApplicationCall,
    sessionService: SessionService
): dev.nimbuspowered.nimbus.module.api.AuthPrincipal.UserSession? {
    val raw = extractBearer(call)
    if (raw == null) {
        call.respond(HttpStatusCode.Unauthorized,
            apiError("Missing session token", ApiErrors.UNAUTHORIZED))
        return null
    }
    val session = sessionService.validate(raw)
    if (session == null) {
        call.respond(HttpStatusCode.Unauthorized,
            apiError("Invalid or expired session", AuthErrors.AUTH_SESSION_INVALID))
        return null
    }
    return session
}

private fun extractBearer(call: ApplicationCall): String? {
    val header = call.request.headers["Authorization"] ?: return null
    if (!header.startsWith("Bearer ", ignoreCase = true)) return null
    return header.substring(7).trim().takeIf { it.isNotEmpty() }
}
