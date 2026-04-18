package dev.nimbuspowered.nimbus.module.auth.routes

import dev.nimbuspowered.nimbus.api.ApiErrors
import dev.nimbuspowered.nimbus.api.ApiMessage
import dev.nimbuspowered.nimbus.api.apiError
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.module.PermissionSet
import dev.nimbuspowered.nimbus.module.auth.AuthConfig
import dev.nimbuspowered.nimbus.module.auth.service.ChallengeKind
import dev.nimbuspowered.nimbus.module.auth.service.LoginChallengeService
import dev.nimbuspowered.nimbus.module.auth.service.PendingTotpStore
import dev.nimbuspowered.nimbus.module.auth.service.PermissionResolver
import dev.nimbuspowered.nimbus.module.auth.service.SessionService
import dev.nimbuspowered.nimbus.module.auth.service.TotpService
import dev.nimbuspowered.nimbus.module.auth.service.WsTicketStore
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Error code strings local to the auth module. Added here rather than in
 * ApiErrors.kt (core) to keep the module self-contained in Phase 1.
 */
object AuthErrors {
    const val AUTH_CHALLENGE_INVALID = "AUTH_CHALLENGE_INVALID"
    const val AUTH_RATE_LIMITED = "AUTH_RATE_LIMITED"
    const val AUTH_DISABLED = "AUTH_DISABLED"
    const val AUTH_SESSION_INVALID = "AUTH_SESSION_INVALID"
    const val PLAYER_OFFLINE = "PLAYER_OFFLINE"
    const val AUTH_TOTP_REQUIRED = "AUTH_TOTP_REQUIRED"
    const val AUTH_TOTP_INVALID = "AUTH_TOTP_INVALID"
    const val AUTH_TOTP_ALREADY_ENABLED = "AUTH_TOTP_ALREADY_ENABLED"
}

@Serializable
data class GenerateCodeRequest(val uuid: String, val name: String)

@Serializable
data class GenerateCodeResponse(
    val code: String,
    val expiresAt: Long,
    val ttlSeconds: Long
)

@Serializable
data class RequestMagicLinkRequest(val uuid: String, val name: String)

@Serializable
data class RequestMagicLinkResponse(
    val url: String,
    val token: String,
    val expiresAt: Long,
    val ttlSeconds: Long
)

@Serializable
data class ConsumeChallengeRequest(val challenge: String)

@Serializable
data class UserInfo(
    val uuid: String,
    val name: String,
    val permissions: List<String>,
    val isAdmin: Boolean,
    val totpEnabled: Boolean
)

/**
 * Unified consume response. When TOTP is active for the user we return a
 * `challengeId` instead of a session token — the dashboard then posts it
 * with the authenticator code to `/api/auth/totp-verify` to receive the
 * real session.
 */
@Serializable
data class ConsumeChallengeResponse(
    val token: String? = null,
    val expiresAt: Long? = null,
    val user: UserInfo? = null,
    val totpRequired: Boolean = false,
    val challengeId: String? = null
)

@Serializable
data class TotpVerifyRequest(val challengeId: String, val code: String)

@Serializable
data class TotpVerifyResponse(
    val token: String,
    val expiresAt: Long,
    val user: UserInfo
)

/** Dashboard-initiated magic-link delivery request. */
@Serializable
data class DeliverMagicLinkRequest(val name: String)

@Serializable
data class DeliverMagicLinkResponse(val status: String = "delivered", val ttlSeconds: Long)

@Serializable
data class SessionSummaryDto(
    val sessionId: String,
    val name: String,
    val createdAt: Long,
    val expiresAt: Long,
    val lastUsedAt: Long,
    val ip: String?,
    val userAgent: String?,
    val loginMethod: String
)

@Serializable
data class SessionListResponse(val sessions: List<SessionSummaryDto>)

@Serializable
data class LogoutAllResponse(val revoked: Int)

@Serializable
data class WsTicketResponse(val ticket: String, val expiresAt: Long)

/**
 * Abstraction over the online-player lookup used to resolve a dashboard-initiated
 * magic-link request to a specific in-game player. Backed by the `players` module's
 * `PlayerTracker` when present — otherwise the deliver-magic-link endpoint returns
 * 404 `PLAYER_OFFLINE` (fail-closed, consistent with the contract).
 */
fun interface PlayerLookup {
    /** Resolve a player name to `(uuid, service)` if currently online, else `null`. */
    fun findOnlinePlayer(name: String): Pair<String, String>?
}

/**
 * Bridge/SDK-facing auth routes — service-token authenticated.
 *
 * These are what the in-game `/dashboard` command hits to issue codes/links
 * that the player can then enter on the dashboard. Separated from the public
 * block below so operators can audit that no backend-plugin endpoint is
 * reachable without a token.
 */
fun Route.authServiceRoutes(
    challengeService: LoginChallengeService,
    sessionService: SessionService,
    configSupplier: () -> AuthConfig,
    publicUrlSupplier: () -> String
) {
    route("/api/auth") {

        // Called by the Bridge/SDK to hand a freshly generated 6-digit code
        // back to the in-game player via chat.
        post("generate-code") {
            val req = runCatching { call.receive<GenerateCodeRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest,
                    apiError("uuid + name required", ApiErrors.VALIDATION_FAILED))
            val uuid = runCatching { UUID.fromString(req.uuid) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest,
                    apiError("Invalid uuid", ApiErrors.VALIDATION_FAILED))
            val cfg = configSupplier().loginChallenge
            try {
                val issued = challengeService.issueCode(uuid.toString(), req.name.take(16))
                call.respond(GenerateCodeResponse(
                    code = issued.raw,
                    expiresAt = issued.expiresAt,
                    ttlSeconds = cfg.codeTtlSeconds
                ))
            } catch (e: LoginChallengeService.RateLimitedException) {
                call.respond(HttpStatusCode.TooManyRequests,
                    apiError(e.message ?: "Rate limited", AuthErrors.AUTH_RATE_LIMITED))
            }
        }

        // Called by the Bridge/SDK when the player runs `/dashboard login link`.
        // Returns the pre-built URL so the caller can render the clickable
        // chat component themselves. Also valid as a fallback for the
        // dashboard-initiated `deliver-magic-link` flow below.
        post("request-magic-link") {
            val req = runCatching { call.receive<RequestMagicLinkRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest,
                    apiError("uuid + name required", ApiErrors.VALIDATION_FAILED))
            if (!configSupplier().loginChallenge.magicLinkEnabled) {
                return@post call.respond(HttpStatusCode.Forbidden,
                    apiError("Magic link login is disabled", AuthErrors.AUTH_DISABLED))
            }
            val uuid = runCatching { UUID.fromString(req.uuid) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest,
                    apiError("Invalid uuid", ApiErrors.VALIDATION_FAILED))
            val ip = call.request.local.remoteAddress
            try {
                val issued = challengeService.issueMagicLink(uuid.toString(), req.name.take(16), ip)
                val base = publicUrlSupplier().trimEnd('/')
                val url = "$base/login?link=${issued.raw}"
                call.respond(HttpStatusCode.Accepted, RequestMagicLinkResponse(
                    url = url,
                    token = issued.raw,
                    expiresAt = issued.expiresAt,
                    ttlSeconds = configSupplier().loginChallenge.magicLinkTtlSeconds
                ))
            } catch (e: LoginChallengeService.RateLimitedException) {
                call.respond(HttpStatusCode.TooManyRequests,
                    apiError(e.message ?: "Rate limited", AuthErrors.AUTH_RATE_LIMITED))
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Forbidden,
                    apiError(e.message ?: "Disabled", AuthErrors.AUTH_DISABLED))
            }
        }

        // `/dashboard sessions` — list the caller's own active dashboard sessions.
        // In Phase 3 we scope by the `uuid` query param so the Bridge/SDK can
        // forward the requesting player's UUID. A future phase will replace
        // this with session-scoped auth (the player's own token).
        get("sessions") {
            val uuidStr = call.request.queryParameters["uuid"]
                ?: return@get call.respond(HttpStatusCode.BadRequest,
                    apiError("uuid query param required", ApiErrors.VALIDATION_FAILED))
            val uuid = runCatching { UUID.fromString(uuidStr) }.getOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest,
                    apiError("Invalid uuid", ApiErrors.VALIDATION_FAILED))
            val summaries = sessionService.listForUser(uuid).map {
                SessionSummaryDto(
                    sessionId = it.sessionId,
                    name = it.name,
                    createdAt = it.createdAt,
                    expiresAt = it.expiresAt,
                    lastUsedAt = it.lastUsedAt,
                    ip = it.ip,
                    userAgent = it.userAgent,
                    loginMethod = it.loginMethod
                )
            }
            call.respond(SessionListResponse(summaries))
        }

        // `/dashboard logout-all` — revoke every session for the calling player.
        post("logout-all") {
            val uuidStr = call.request.queryParameters["uuid"]
                ?: return@post call.respond(HttpStatusCode.BadRequest,
                    apiError("uuid query param required", ApiErrors.VALIDATION_FAILED))
            val uuid = runCatching { UUID.fromString(uuidStr) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest,
                    apiError("Invalid uuid", ApiErrors.VALIDATION_FAILED))
            val revoked = sessionService.revokeAll(uuid)
            call.respond(LogoutAllResponse(revoked))
        }
    }
}

/**
 * Dashboard-initiated magic-link delivery — public, rate-limited.
 *
 * User types their MC name on the login page → controller resolves the name
 * to an online player and fires an `AUTH_MAGIC_LINK_DELIVERY` event that the
 * SDK plugin on the target service turns into a clickable chat component.
 *
 * All negative outcomes (player offline, name never existed, Players module
 * absent) collapse to the same 404 `PLAYER_OFFLINE` with an identical error
 * body. That way this unauthenticated surface cannot be used to probe which
 * names are registered on the network.
 */
fun Route.authPublicDeliveryRoutes(
    challengeService: LoginChallengeService,
    configSupplier: () -> AuthConfig,
    publicUrlSupplier: () -> String,
    playerLookupSupplier: () -> PlayerLookup?,
    eventBus: EventBus?
) {
    route("/api/auth") {
        post("deliver-magic-link") {
            val req = runCatching { call.receive<DeliverMagicLinkRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest,
                    apiError("name required", ApiErrors.VALIDATION_FAILED))

            val cfg = configSupplier()
            if (!cfg.loginChallenge.magicLinkEnabled) {
                return@post call.respond(HttpStatusCode.Forbidden,
                    apiError("Magic link login is disabled", AuthErrors.AUTH_DISABLED))
            }

            // Uniform error message whether the player is offline, never
            // existed, or the Players module isn't loaded — avoids leaking
            // account-existence info across this unauthenticated surface.
            val genericNotFound = apiError(
                "Player not found — please join any Nimbus server and try again.",
                AuthErrors.PLAYER_OFFLINE
            )
            val lookup = playerLookupSupplier()
                ?: return@post call.respond(HttpStatusCode.NotFound, genericNotFound)

            val (uuidStr, _) = lookup.findOnlinePlayer(req.name)
                ?: return@post call.respond(HttpStatusCode.NotFound, genericNotFound)

            val ip = call.request.local.remoteAddress
            val issued = try {
                challengeService.issueMagicLink(uuidStr, req.name.take(16), ip)
            } catch (e: LoginChallengeService.RateLimitedException) {
                return@post call.respond(HttpStatusCode.TooManyRequests,
                    apiError(e.message ?: "Rate limited", AuthErrors.AUTH_RATE_LIMITED))
            } catch (e: IllegalStateException) {
                return@post call.respond(HttpStatusCode.Forbidden,
                    apiError(e.message ?: "Disabled", AuthErrors.AUTH_DISABLED))
            }

            val base = publicUrlSupplier().trimEnd('/')
            val url = "$base/login?link=${issued.raw}"

            // Fire a module event — the Velocity auth plugin subscribes to
            // this and renders the Adventure clickable component directly to
            // the target player via `proxyServer.getPlayer(uuid)`. No service
            // routing needed because Velocity owns every player connection.
            eventBus?.emit(NimbusEvent.ModuleEvent(
                moduleId = "auth",
                type = "AUTH_MAGIC_LINK_DELIVERY",
                data = mapOf(
                    "uuid" to uuidStr,
                    "name" to req.name,
                    "url" to url,
                    "ttl" to cfg.loginChallenge.magicLinkTtlSeconds.toString(),
                    "expiresAt" to issued.expiresAt.toString()
                )
            ))

            call.respond(HttpStatusCode.Accepted,
                DeliverMagicLinkResponse(ttlSeconds = cfg.loginChallenge.magicLinkTtlSeconds))
        }
    }
}

/**
 * Public user-flow auth routes — dashboard-facing. Consume challenge, logout
 * (self), and `me` all carry their own bearer-session auth so they live here
 * rather than behind the service-token gate.
 */
fun Route.authRoutes(
    challengeService: LoginChallengeService,
    sessionService: SessionService,
    permissionResolver: PermissionResolver,
    totpService: TotpService,
    pendingTotpStore: PendingTotpStore,
    wsTicketStore: WsTicketStore,
    configSupplier: () -> AuthConfig
) {
    route("/api/auth") {

        /**
         * Unified consume endpoint: accepts either a 6-digit code or a
         * magic-link token. If the user has TOTP enabled we defer session
         * creation and hand back a `challengeId` the dashboard trades in
         * at `/api/auth/totp-verify`. Otherwise a real session is issued.
         */
        post("consume-challenge") {
            val req = runCatching { call.receive<ConsumeChallengeRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest,
                    apiError("challenge required", ApiErrors.VALIDATION_FAILED))

            val consumed = challengeService.consume(req.challenge)
                ?: return@post call.respond(HttpStatusCode.Unauthorized,
                    apiError("Invalid, expired, or already-used challenge", AuthErrors.AUTH_CHALLENGE_INVALID))

            val uuid = runCatching { UUID.fromString(consumed.uuid) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.InternalServerError,
                    apiError("Stored challenge has invalid uuid", ApiErrors.INTERNAL_ERROR))

            val permissions = permissionResolver.resolve(consumed.uuid)
            val loginMethod = when (consumed.kind) {
                ChallengeKind.CODE -> "code"
                ChallengeKind.MAGIC_LINK -> "magic_link"
            }

            val totpEnabled = totpService.isEnabled(consumed.uuid)

            // `require_for_admin` is enforced at the UI layer (dashboard
            // prompts enrollment on first login). We deliberately do not
            // block admin login here — that would lock out any operator
            // whose TOTP device is lost before disabling the flag.
            if (totpEnabled) {
                val pending = pendingTotpStore.create(
                    uuid = uuid,
                    name = consumed.name,
                    permissions = permissions,
                    ip = call.request.local.remoteAddress,
                    userAgent = call.request.headers["User-Agent"],
                    loginMethod = loginMethod
                )
                return@post call.respond(ConsumeChallengeResponse(
                    totpRequired = true,
                    challengeId = pending.challengeId
                ))
            }

            val session = sessionService.issue(
                uuid = uuid,
                name = consumed.name,
                permissions = permissions,
                ip = call.request.local.remoteAddress,
                userAgent = call.request.headers["User-Agent"],
                loginMethod = loginMethod
            )

            call.respond(ConsumeChallengeResponse(
                token = session.rawToken,
                expiresAt = session.principal.expiresAt,
                user = UserInfo(
                    uuid = uuid.toString(),
                    name = consumed.name,
                    permissions = permissions.asSet().toList(),
                    isAdmin = permissions.has(PermissionSet.ADMIN_NODE),
                    totpEnabled = false
                ),
                totpRequired = false
            ))
        }

        /**
         * Trades a pending TOTP challenge-id + authenticator code for a real
         * session. Recovery codes are accepted here too (single-use, same
         * endpoint) so users can still log in with a lost device.
         */
        post("totp-verify") {
            val req = runCatching { call.receive<TotpVerifyRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest,
                    apiError("challengeId + code required", ApiErrors.VALIDATION_FAILED))

            val pending = pendingTotpStore.peek(req.challengeId)
                ?: return@post call.respond(HttpStatusCode.Unauthorized,
                    apiError("Invalid or expired TOTP challenge", AuthErrors.AUTH_CHALLENGE_INVALID))

            val ok = totpService.verifyForLogin(pending.uuid.toString(), req.code)
            if (!ok) {
                return@post call.respond(HttpStatusCode.Unauthorized,
                    apiError("Invalid TOTP code", AuthErrors.AUTH_TOTP_INVALID))
            }
            // Consume only on success so a typo doesn't invalidate the pending entry.
            pendingTotpStore.consume(req.challengeId)

            val session = sessionService.issue(
                uuid = pending.uuid,
                name = pending.name,
                permissions = pending.permissions,
                ip = pending.ip,
                userAgent = pending.userAgent,
                loginMethod = pending.loginMethod
            )
            call.respond(TotpVerifyResponse(
                token = session.rawToken,
                expiresAt = session.principal.expiresAt,
                user = UserInfo(
                    uuid = pending.uuid.toString(),
                    name = pending.name,
                    permissions = pending.permissions.asSet().toList(),
                    isAdmin = pending.permissions.has(PermissionSet.ADMIN_NODE),
                    totpEnabled = true
                )
            ))
        }

        post("logout") {
            val raw = extractBearerToken(call)
                ?: return@post call.respond(HttpStatusCode.Unauthorized,
                    apiError("Missing session token", ApiErrors.UNAUTHORIZED))
            val ok = sessionService.revoke(raw)
            call.respond(ApiMessage(success = ok, message = if (ok) "Session revoked" else "No such session"))
        }

        /**
         * Mint a short-lived, single-use WebSocket ticket bound to the calling
         * session. The dashboard exchanges its long-lived bearer for a ticket
         * before opening a WS/SSE connection, so the session token never lands
         * in a URL query param (access logs, referrer, browser history).
         *
         * Tickets are `wt_`-prefixed and consumed on first validate() — see
         * [WsTicketStore] and the SessionValidator wiring in `AuthModule`.
         */
        get("ws-ticket") {
            val raw = extractBearerToken(call)
                ?: return@get call.respond(HttpStatusCode.Unauthorized,
                    apiError("Missing session token", ApiErrors.UNAUTHORIZED))
            sessionService.validate(raw)
                ?: return@get call.respond(HttpStatusCode.Unauthorized,
                    apiError("Invalid or expired session", AuthErrors.AUTH_SESSION_INVALID))
            // Bind the ticket to the raw bearer, so revocation + rolling
            // refresh continue to apply when the ticket is consumed later.
            val ticket = wsTicketStore.mint(raw)
            call.respond(WsTicketResponse(ticket = ticket.token, expiresAt = ticket.expiresAt))
        }

        get("me") {
            val raw = extractBearerToken(call)
                ?: return@get call.respond(HttpStatusCode.Unauthorized,
                    apiError("Missing session token", ApiErrors.UNAUTHORIZED))
            val principal = sessionService.validate(raw)
                ?: return@get call.respond(HttpStatusCode.Unauthorized,
                    apiError("Invalid or expired session", AuthErrors.AUTH_SESSION_INVALID))

            call.respond(UserInfo(
                uuid = principal.uuid.toString(),
                name = principal.name,
                permissions = principal.permissions.asSet().toList(),
                isAdmin = principal.permissions.has(PermissionSet.ADMIN_NODE),
                totpEnabled = totpService.isEnabled(principal.uuid.toString())
            ))
        }

        /**
         * User-scoped sessions listing. Uses the bearer session to identify
         * the caller and only returns *their* active sessions — no cross-user
         * leakage. The flag `currentSession` lets the UI highlight the
         * session that's making the call right now.
         */
        get("my-sessions") {
            val raw = extractBearerToken(call)
                ?: return@get call.respond(HttpStatusCode.Unauthorized,
                    apiError("Missing session token", ApiErrors.UNAUTHORIZED))
            val principal = sessionService.validate(raw)
                ?: return@get call.respond(HttpStatusCode.Unauthorized,
                    apiError("Invalid or expired session", AuthErrors.AUTH_SESSION_INVALID))

            val currentSessionId = principal.sessionId
            val list = sessionService.listForUser(principal.uuid).map { s ->
                SessionSummaryDto(
                    sessionId = s.sessionId,
                    name = s.name,
                    createdAt = s.createdAt,
                    expiresAt = s.expiresAt,
                    lastUsedAt = s.lastUsedAt,
                    ip = s.ip,
                    userAgent = s.userAgent,
                    loginMethod = s.loginMethod
                )
            }
            call.respond(MySessionsResponse(sessions = list, currentSessionId = currentSessionId))
        }

        /**
         * Revoke a specific sibling session of the caller. We match by the
         * prefix-16 `sessionId` (since we never hand the raw token back —
         * only the hash prefix is safe to expose). Revoking one's own current
         * session is allowed but the client should prefer `/api/auth/logout`
         * which also clears local state.
         */
        delete("my-sessions/{sessionId}") {
            val raw = extractBearerToken(call)
                ?: return@delete call.respond(HttpStatusCode.Unauthorized,
                    apiError("Missing session token", ApiErrors.UNAUTHORIZED))
            val principal = sessionService.validate(raw)
                ?: return@delete call.respond(HttpStatusCode.Unauthorized,
                    apiError("Invalid or expired session", AuthErrors.AUTH_SESSION_INVALID))
            val sid = call.parameters["sessionId"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest,
                    apiError("sessionId required", ApiErrors.VALIDATION_FAILED))
            val revoked = sessionService.revokeOwnedSession(principal.uuid, sid)
            if (revoked) {
                call.respond(ApiMessage(success = true, message = "Session revoked"))
            } else {
                call.respond(HttpStatusCode.NotFound,
                    apiError("Session not found", ApiErrors.NOT_FOUND))
            }
        }

        /**
         * Revoke every OTHER session for the caller, keeping the one making
         * this call alive (so the user doesn't get logged out by their own
         * "log me out everywhere" click).
         */
        post("my-sessions/revoke-others") {
            val raw = extractBearerToken(call)
                ?: return@post call.respond(HttpStatusCode.Unauthorized,
                    apiError("Missing session token", ApiErrors.UNAUTHORIZED))
            val principal = sessionService.validate(raw)
                ?: return@post call.respond(HttpStatusCode.Unauthorized,
                    apiError("Invalid or expired session", AuthErrors.AUTH_SESSION_INVALID))
            val revoked = sessionService.revokeOtherSessions(principal.uuid, principal.sessionId)
            call.respond(LogoutAllResponse(revoked))
        }
    }
}

@Serializable
data class MySessionsResponse(
    val sessions: List<SessionSummaryDto>,
    val currentSessionId: String
)

internal fun extractBearerToken(call: io.ktor.server.application.ApplicationCall): String? {
    val header = call.request.headers["Authorization"] ?: return null
    if (!header.startsWith("Bearer ", ignoreCase = true)) return null
    return header.substring(7).trim().takeIf { it.isNotEmpty() }
}
