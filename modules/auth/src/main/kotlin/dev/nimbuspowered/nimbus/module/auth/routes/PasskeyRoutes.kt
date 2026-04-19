package dev.nimbuspowered.nimbus.module.auth.routes

import dev.nimbuspowered.nimbus.api.ApiErrors
import dev.nimbuspowered.nimbus.api.ApiMessage
import dev.nimbuspowered.nimbus.api.apiError
import dev.nimbuspowered.nimbus.module.PermissionSet
import dev.nimbuspowered.nimbus.module.auth.service.PendingTotpStore
import dev.nimbuspowered.nimbus.module.auth.service.PermissionResolver
import dev.nimbuspowered.nimbus.module.auth.service.SessionService
import dev.nimbuspowered.nimbus.module.auth.service.TotpService
import dev.nimbuspowered.nimbus.module.auth.service.WebAuthnService
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger("PasskeyRoutes")

@Serializable
data class PasskeyStartRegisterRequest(val label: String = "Passkey")

@Serializable
data class PasskeyStartResponse(
    val ceremonyId: String,
    /** Opaque JSON the browser feeds into `navigator.credentials.create/get()` */
    val publicKeyOptionsJson: JsonElement
)

@Serializable
data class PasskeyFinishRequest(
    val ceremonyId: String,
    /** The `PublicKeyCredential` JSON returned by the browser, **as a string**. */
    val responseJson: String,
    val label: String? = null
)

@Serializable
data class PasskeyCredentialDto(
    val credentialId: String,
    val label: String,
    val createdAt: Long,
    val lastUsedAt: Long?,
    val aaguid: String?
)

@Serializable
data class PasskeyListResponse(val credentials: List<PasskeyCredentialDto>)

@Serializable
data class PasskeyStartLoginRequest(val username: String? = null)

/**
 * Union-shape login result. When the user has TOTP enabled the controller
 * hands back a `challengeId` (same pattern as `/api/auth/consume-challenge`)
 * instead of a session token — the dashboard then posts it with the
 * authenticator code to `/api/auth/totp-verify` to receive the real session.
 *
 * Passkeys are strong authentication on their own, but TOTP is an explicit
 * user-chosen second factor: if an operator enabled it we honour it on every
 * login path, not just the 6-digit/magic-link one.
 */
@Serializable
data class PasskeyLoginResult(
    val token: String? = null,
    val expiresAt: Long? = null,
    val user: UserInfo? = null,
    val totpRequired: Boolean = false,
    val challengeId: String? = null
)

/**
 * All routes here live under `/api/auth/passkey/`. Registration endpoints
 * require a dashboard session (bearer), login endpoints are public.
 */
fun Route.passkeyRoutes(
    webAuthn: WebAuthnService,
    sessionService: SessionService,
    permissionResolver: PermissionResolver,
    totpService: TotpService,
    pendingTotpStore: PendingTotpStore
) {
    val json = Json { ignoreUnknownKeys = true }

    route("/api/auth/passkey") {

        // ── Enrollment (bearer-authed) ──────────────────────────────────

        post("register/start") {
            if (!webAuthn.isEnabled()) return@post call.respondDisabled()
            val session = requireSession(call, sessionService) ?: return@post
            val req = runCatching { call.receive<PasskeyStartRegisterRequest>() }.getOrNull()
                ?: PasskeyStartRegisterRequest()

            val (ceremonyId, options) = try {
                webAuthn.startRegistration(
                    uuid = session.uuid.toString(),
                    name = session.name,
                    label = req.label
                )
            } catch (e: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.Conflict,
                    apiError(e.message ?: "Cannot start registration", "PASSKEY_LIMIT_REACHED"))
            }
            val optionsJson = json.parseToJsonElement(options.toCredentialsCreateJson())
            call.respond(PasskeyStartResponse(ceremonyId, optionsJson))
        }

        post("register/finish") {
            if (!webAuthn.isEnabled()) return@post call.respondDisabled()
            requireSession(call, sessionService) ?: return@post
            val req = runCatching { call.receive<PasskeyFinishRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest,
                    apiError("ceremonyId + responseJson required", ApiErrors.VALIDATION_FAILED))
            try {
                val stored = webAuthn.finishRegistration(req.ceremonyId, req.responseJson)
                call.respond(PasskeyCredentialDto(
                    credentialId = stored.credentialIdBase64Url,
                    label = stored.label,
                    createdAt = stored.createdAt,
                    lastUsedAt = stored.lastUsedAt,
                    aaguid = stored.aaguid
                ))
            } catch (e: Exception) {
                logger.warn("Passkey registration finish failed: {}", e.message)
                call.respond(HttpStatusCode.BadRequest,
                    apiError(e.message ?: "Registration failed", "PASSKEY_REGISTER_FAILED"))
            }
        }

        // ── Management (bearer-authed) ──────────────────────────────────

        get("credentials") {
            val session = requireSession(call, sessionService) ?: return@get
            val creds = webAuthn.listCredentials(session.uuid.toString())
            call.respond(PasskeyListResponse(creds.map {
                PasskeyCredentialDto(
                    credentialId = it.credentialIdBase64Url,
                    label = it.label,
                    createdAt = it.createdAt,
                    lastUsedAt = it.lastUsedAt,
                    aaguid = it.aaguid
                )
            }))
        }

        delete("credentials/{id}") {
            val session = requireSession(call, sessionService) ?: return@delete
            val credId = call.parameters["id"]
                ?: return@delete call.respond(HttpStatusCode.BadRequest,
                    apiError("credential id required", ApiErrors.VALIDATION_FAILED))
            val ok = webAuthn.deleteCredential(session.uuid.toString(), credId)
            if (!ok) {
                return@delete call.respond(HttpStatusCode.NotFound,
                    apiError("Credential not found", "PASSKEY_NOT_FOUND"))
            }
            call.respond(ApiMessage(success = true, message = "Passkey removed"))
        }

        // ── Login (public) ──────────────────────────────────────────────

        post("login/start") {
            if (!webAuthn.isEnabled()) return@post call.respondDisabled()
            val req = runCatching { call.receive<PasskeyStartLoginRequest>() }.getOrNull()
                ?: PasskeyStartLoginRequest()
            val (ceremonyId, assertion) = webAuthn.startAuthentication(req.username)
            val optionsJson = json.parseToJsonElement(assertion.toCredentialsGetJson())
            call.respond(PasskeyStartResponse(ceremonyId, optionsJson))
        }

        post("login/finish") {
            if (!webAuthn.isEnabled()) return@post call.respondDisabled()
            val req = runCatching { call.receive<PasskeyFinishRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest,
                    apiError("ceremonyId + responseJson required", ApiErrors.VALIDATION_FAILED))
            val (uuidStr, name) = try {
                webAuthn.finishAuthentication(req.ceremonyId, req.responseJson)
            } catch (e: Exception) {
                logger.warn("Passkey login finish failed: {}", e.message)
                return@post call.respond(HttpStatusCode.Unauthorized,
                    apiError("Passkey verification failed", "PASSKEY_LOGIN_FAILED"))
            }
            val uuid = runCatching { UUID.fromString(uuidStr) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.InternalServerError,
                    apiError("Invalid userHandle", ApiErrors.INTERNAL_ERROR))
            val permissions = permissionResolver.resolve(uuidStr)
            val totpEnabled = totpService.isEnabled(uuidStr)

            // Honour TOTP on the passkey path too — same half-authenticated
            // hand-off as `/api/auth/consume-challenge`. The dashboard trades
            // the `challengeId` at `/api/auth/totp-verify` for the real session.
            if (totpEnabled) {
                val pending = pendingTotpStore.create(
                    uuid = uuid,
                    name = name,
                    permissions = permissions,
                    ip = call.request.local.remoteAddress,
                    userAgent = call.request.headers["User-Agent"],
                    loginMethod = "passkey"
                )
                return@post call.respond(PasskeyLoginResult(
                    totpRequired = true,
                    challengeId = pending.challengeId
                ))
            }

            val issued = sessionService.issue(
                uuid = uuid,
                name = name,
                permissions = permissions,
                ip = call.request.local.remoteAddress,
                userAgent = call.request.headers["User-Agent"],
                loginMethod = "passkey"
            )
            call.respond(PasskeyLoginResult(
                token = issued.rawToken,
                expiresAt = issued.principal.expiresAt,
                user = UserInfo(
                    uuid = uuidStr,
                    name = name,
                    permissions = permissions.asSet().toList(),
                    isAdmin = permissions.has(PermissionSet.ADMIN_NODE),
                    totpEnabled = false
                )
            ))
        }
    }
}

private suspend fun ApplicationCall.respondDisabled() =
    respond(HttpStatusCode.ServiceUnavailable,
        apiError("Passkey authentication is disabled", "PASSKEY_DISABLED"))

private suspend fun requireSession(
    call: ApplicationCall,
    sessionService: SessionService
): dev.nimbuspowered.nimbus.module.AuthPrincipal.UserSession? {
    val header = call.request.headers["Authorization"] ?: run {
        call.respond(HttpStatusCode.Unauthorized,
            apiError("Missing session token", ApiErrors.UNAUTHORIZED))
        return null
    }
    if (!header.startsWith("Bearer ", ignoreCase = true)) {
        call.respond(HttpStatusCode.Unauthorized,
            apiError("Bearer token required", ApiErrors.UNAUTHORIZED))
        return null
    }
    val raw = header.substring(7).trim()
    val session = sessionService.validate(raw)
    if (session == null) {
        call.respond(HttpStatusCode.Unauthorized,
            apiError("Invalid or expired session", AuthErrors.AUTH_SESSION_INVALID))
        return null
    }
    return session
}
