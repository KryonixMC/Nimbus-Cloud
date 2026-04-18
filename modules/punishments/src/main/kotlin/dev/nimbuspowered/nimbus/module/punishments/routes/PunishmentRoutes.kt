package dev.nimbuspowered.nimbus.module.punishments.routes

import dev.nimbuspowered.nimbus.api.ApiErrors
import dev.nimbuspowered.nimbus.api.ApiMessage
import dev.nimbuspowered.nimbus.api.apiError
import dev.nimbuspowered.nimbus.api.requirePermission
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.module.punishments.DurationParser
import dev.nimbuspowered.nimbus.module.punishments.IssuePunishmentRequest
import dev.nimbuspowered.nimbus.module.punishments.MojangUuidLookup
import dev.nimbuspowered.nimbus.module.punishments.PunishmentCheckResponse
import dev.nimbuspowered.nimbus.module.punishments.PunishmentListResponse
import dev.nimbuspowered.nimbus.module.punishments.PunishmentManager
import dev.nimbuspowered.nimbus.module.punishments.PunishmentRecord
import dev.nimbuspowered.nimbus.module.punishments.PunishmentScope
import dev.nimbuspowered.nimbus.module.punishments.PunishmentType
import dev.nimbuspowered.nimbus.module.punishments.PunishmentsEvents
import dev.nimbuspowered.nimbus.module.punishments.PunishmentsMessages
import dev.nimbuspowered.nimbus.module.punishments.PunishmentsMessagesStore
import dev.nimbuspowered.nimbus.module.punishments.RevokePunishmentRequest
import dev.nimbuspowered.nimbus.module.punishments.renderPunishmentMessage
import dev.nimbuspowered.nimbus.module.punishments.templateFor
import dev.nimbuspowered.nimbus.module.punishments.toResponse
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

fun Route.punishmentRoutes(
    manager: PunishmentManager,
    messages: PunishmentsMessagesStore,
    eventBus: EventBus
) {
    route("/api/punishments") {

        // GET /api/punishments?active=true&type=BAN&limit=50&offset=0
        get {
            if (!call.requirePermission("nimbus.dashboard.punishments.view")) return@get
            val active = call.request.queryParameters["active"]?.toBooleanStrictOrNull() ?: true
            val type = call.request.queryParameters["type"]?.let {
                runCatching { PunishmentType.valueOf(it.uppercase()) }.getOrNull()
            }
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val records = manager.list(active, type, limit, offset)
            call.respond(PunishmentListResponse(records.map { it.toResponse() }, records.size))
        }

        // ── Messages (templates) ────────────────────────────────────
        // Separate top-level block so routing doesn't mistake "messages" for a {id}.

        get("messages") {
            if (!call.requirePermission("nimbus.dashboard.punishments.view")) return@get
            call.respond(messages.current())
        }

        put("messages") {
            if (!call.requirePermission("nimbus.dashboard.admin")) return@put
            val next = call.receive<PunishmentsMessages>()
            call.respond(messages.update(next))
        }

        // GET /api/punishments/{id}
        get("{id}") {
            if (!call.requirePermission("nimbus.dashboard.punishments.view")) return@get
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, apiError("Invalid id", ApiErrors.VALIDATION_FAILED))
            val record = manager.getById(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, apiError("Punishment $id not found", ApiErrors.PUNISHMENT_NOT_FOUND))
            call.respond(record.toResponse())
        }

        // GET /api/punishments/player/{uuid}
        get("player/{uuid}") {
            if (!call.requirePermission("nimbus.dashboard.punishments.history")) return@get
            val uuid = call.parameters["uuid"]!!
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500)
            val history = manager.getHistory(uuid, limit)
            call.respond(PunishmentListResponse(history.map { it.toResponse() }, history.size))
        }

        /*
         * GET /api/punishments/check/{uuid}?ip=[&group=&service=]
         *
         * Without group/service: returns only NETWORK-scoped active bans — used by the
         *   proxy on LoginEvent to deny login entirely.
         * With group/service:    returns NETWORK + matching scoped bans — used by the
         *   proxy on ServerPreConnectEvent to block access to a specific backend.
         *
         * Always returns a pre-rendered kickMessage so the proxy plugin doesn't have
         * to maintain its own templates.
         */
        get("check/{uuid}") {
            val uuid = call.parameters["uuid"]!!
            val ip = call.request.queryParameters["ip"]
            val group = call.request.queryParameters["group"]
            val service = call.request.queryParameters["service"]

            val record = if (group == null && service == null) {
                manager.checkLoginCached(uuid, ip)
            } else {
                manager.checkConnectCached(uuid, ip, group, service)
            }
            call.respond(record.toCheckResponse(messages.current()))
        }

        // GET /api/punishments/mute/{uuid}?group=&service= — scoped mute check
        get("mute/{uuid}") {
            val uuid = call.parameters["uuid"]!!
            val group = call.request.queryParameters["group"]
            val service = call.request.queryParameters["service"]
            val record = manager.checkMuteCached(uuid, group, service)
            call.respond(record.toCheckResponse(messages.current()))
        }

        // POST /api/punishments — issue a new punishment.
        // Per-type permission: warn / mute / tempmute / kick / ban / tempban / ipban.
        post {
            val req = call.receive<IssuePunishmentRequest>()
            val type = runCatching { PunishmentType.valueOf(req.type.uppercase()) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Unknown type '${req.type}'", ApiErrors.VALIDATION_FAILED))

            val requiredNode = when (type) {
                PunishmentType.WARN -> "nimbus.dashboard.punishments.warn"
                PunishmentType.MUTE -> "nimbus.dashboard.punishments.mute"
                PunishmentType.TEMPMUTE -> "nimbus.dashboard.punishments.tempmute"
                PunishmentType.KICK -> "nimbus.dashboard.punishments.kick"
                PunishmentType.BAN -> "nimbus.dashboard.punishments.ban"
                PunishmentType.TEMPBAN -> "nimbus.dashboard.punishments.tempban"
                PunishmentType.IPBAN -> "nimbus.dashboard.punishments.ipban"
            }
            if (!call.requirePermission(requiredNode)) return@post

            if (req.targetName.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, apiError("targetName is required", ApiErrors.PUNISHMENT_TARGET_INVALID))
            }
            if (type == PunishmentType.IPBAN && req.targetIp.isNullOrBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, apiError("IPBAN requires targetIp", ApiErrors.PUNISHMENT_TARGET_INVALID))
            }

            // Resolve UUID: explicit > looks-like-UUID > Mojang API lookup by name.
            // The API is the single source of truth for dashboard / third-party
            // integrations — without this, those clients can't pre-ban by name.
            val uuid: String
            val resolvedName: String
            val suppliedUuid = req.targetUuid?.trim()?.takeIf { it.isNotBlank() }
            if (suppliedUuid != null && isUuidShaped(suppliedUuid)) {
                uuid = suppliedUuid
                resolvedName = req.targetName
            } else if (isUuidShaped(req.targetName)) {
                uuid = req.targetName
                resolvedName = req.targetName
            } else {
                val mojang = withContext(Dispatchers.IO) { MojangUuidLookup.resolve(req.targetName) }
                if (mojang == null) {
                    return@post call.respond(
                        HttpStatusCode.NotFound,
                        apiError(
                            "Could not resolve '${req.targetName}' via Mojang — check the name or pass targetUuid explicitly",
                            ApiErrors.PUNISHMENT_TARGET_INVALID
                        )
                    )
                }
                uuid = mojang.first
                resolvedName = mojang.second
            }

            val scope = PunishmentScope.parse(req.scope)
            if (scope != PunishmentScope.NETWORK && req.scopeTarget.isNullOrBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest,
                    apiError("$scope scope requires a scopeTarget (group or service name)", ApiErrors.VALIDATION_FAILED))
            }

            val duration = try {
                if (type.isTemporary()) {
                    val raw = req.duration ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        apiError("${type.name} requires duration", ApiErrors.PUNISHMENT_DURATION_INVALID)
                    )
                    DurationParser.parse(raw) ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        apiError("${type.name} cannot be permanent — use BAN/MUTE instead", ApiErrors.PUNISHMENT_DURATION_INVALID)
                    )
                } else if (!type.isRevocable() || req.duration == null) {
                    null
                } else {
                    DurationParser.parse(req.duration)
                }
            } catch (e: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, apiError(e.message ?: "Invalid duration", ApiErrors.PUNISHMENT_DURATION_INVALID))
            }

            val record = manager.issue(
                type = type,
                targetUuid = uuid,
                targetName = resolvedName,
                targetIp = req.targetIp,
                duration = duration,
                reason = req.reason,
                issuer = req.issuer,
                issuerName = req.issuerName,
                scope = scope,
                scopeTarget = if (scope == PunishmentScope.NETWORK) null else req.scopeTarget
            )
            val rendered = renderPunishmentMessage(messages.current().templateFor(record.type), record)
            eventBus.emit(PunishmentsEvents.issued(record, rendered))
            call.respond(HttpStatusCode.Created, record.toResponse())
        }

        // DELETE /api/punishments/{id} — revoke
        delete("{id}") {
            if (!call.requirePermission("nimbus.dashboard.punishments.revoke")) return@delete
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, apiError("Invalid id", ApiErrors.VALIDATION_FAILED))
            val req = runCatching { call.receive<RevokePunishmentRequest>() }.getOrDefault(RevokePunishmentRequest())
            val existing = manager.getById(id)
                ?: return@delete call.respond(HttpStatusCode.NotFound, apiError("Punishment $id not found", ApiErrors.PUNISHMENT_NOT_FOUND))
            if (!existing.active) {
                return@delete call.respond(HttpStatusCode.Conflict, apiError("Already revoked or expired", ApiErrors.PUNISHMENT_ALREADY_REVOKED))
            }
            val revoked = manager.revoke(id, req.revokedBy, req.reason.ifBlank { null })
                ?: return@delete call.respond(HttpStatusCode.Conflict, apiError("Could not revoke", ApiErrors.PUNISHMENT_ALREADY_REVOKED))
            eventBus.emit(PunishmentsEvents.revoked(revoked))
            call.respond(ApiMessage(true, "Punishment ${revoked.id} revoked"))
        }
    }
}

private fun isUuidShaped(s: String): Boolean =
    s.length == 36 && s[8] == '-' && s[13] == '-' && s[18] == '-' && s[23] == '-'

/**
 * Build the compact response the Bridge expects, including a rendered kickMessage
 * so the proxy plugin can disconnect the player with the configured text without
 * maintaining its own copy of the templates.
 */
private fun PunishmentRecord?.toCheckResponse(messages: PunishmentsMessages): PunishmentCheckResponse {
    if (this == null) return PunishmentCheckResponse(punished = false)
    val remaining = expiresAt?.let {
        try { Duration.between(Instant.now(), Instant.parse(it)).seconds.coerceAtLeast(0) } catch (_: Exception) { null }
    }
    val rendered = renderPunishmentMessage(messages.templateFor(type), this)
    return PunishmentCheckResponse(
        punished = true,
        type = type.name,
        reason = reason,
        issuerName = issuerName,
        issuedAt = issuedAt,
        expiresAt = expiresAt,
        remainingSeconds = remaining,
        scope = scope.name,
        scopeTarget = scopeTarget,
        kickMessage = rendered
    )
}
