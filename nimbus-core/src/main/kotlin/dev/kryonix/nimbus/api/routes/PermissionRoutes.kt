package dev.kryonix.nimbus.api.routes

import dev.kryonix.nimbus.api.*
import dev.kryonix.nimbus.event.EventBus
import dev.kryonix.nimbus.event.NimbusEvent
import dev.kryonix.nimbus.permissions.PermissionContext
import dev.kryonix.nimbus.permissions.PermissionManager
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.permissionRoutes(
    permissionManager: PermissionManager,
    eventBus: EventBus
) {
    route("/api/permissions") {

        // ── Groups ──────────────────────────────────────────

        route("groups") {

            // GET /api/permissions/groups
            get {
                val groups = permissionManager.getAllGroups().map { it.toResponse() }
                call.respond(PermissionGroupListResponse(groups, groups.size))
            }

            // GET /api/permissions/groups/{name}
            get("{name}") {
                val name = call.parameters["name"]!!
                val group = permissionManager.getGroup(name)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Permission group '$name' not found"))
                call.respond(group.toResponse())
            }

            // POST /api/permissions/groups — Create group
            post {
                val request = call.receive<CreatePermissionGroupRequest>()
                if (request.name.isBlank() || !request.name.matches(Regex("^[a-zA-Z0-9_-]{1,64}$"))) {
                    return@post call.respond(HttpStatusCode.BadRequest, ApiMessage(false, "Invalid group name"))
                }
                if (permissionManager.getGroup(request.name) != null) {
                    return@post call.respond(HttpStatusCode.Conflict, ApiMessage(false, "Group '${request.name}' already exists"))
                }

                val group = permissionManager.createGroup(request.name, request.default)
                permissionManager.logAudit("api", "group.create", group.name, "Created group '${group.name}'")
                eventBus.emit(NimbusEvent.PermissionGroupCreated(group.name))
                call.respond(HttpStatusCode.Created, ApiMessage(true, "Permission group '${group.name}' created"))
            }

            // PUT /api/permissions/groups/{name} — Update group
            put("{name}") {
                val name = call.parameters["name"]!!
                val group = permissionManager.getGroup(name)
                    ?: return@put call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Permission group '$name' not found"))

                val request = call.receive<UpdatePermissionGroupRequest>()
                if (request.default != null) {
                    permissionManager.setDefault(group.name, request.default)
                }
                if (request.prefix != null || request.suffix != null || request.priority != null) {
                    permissionManager.updateGroupDisplay(group.name, request.prefix, request.suffix, request.priority)
                }
                if (request.weight != null) {
                    permissionManager.setGroupWeight(group.name, request.weight)
                }
                if (request.permissions != null) {
                    group.permissions.clear()
                    group.permissions.addAll(request.permissions)
                }
                if (request.parents != null) {
                    group.parents.clear()
                    group.parents.addAll(request.parents)
                }
                if (request.meta != null) {
                    group.meta.clear()
                    group.meta.putAll(request.meta)
                }

                permissionManager.logAudit("api", "group.update", group.name, "Updated group '${group.name}'")
                eventBus.emit(NimbusEvent.PermissionGroupUpdated(group.name))
                call.respond(ApiMessage(true, "Permission group '${group.name}' updated"))
            }

            // DELETE /api/permissions/groups/{name}
            delete("{name}") {
                val name = call.parameters["name"]!!
                if (permissionManager.getGroup(name) == null) {
                    return@delete call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Permission group '$name' not found"))
                }

                permissionManager.deleteGroup(name)
                permissionManager.logAudit("api", "group.delete", name, "Deleted group '$name'")
                eventBus.emit(NimbusEvent.PermissionGroupDeleted(name))
                call.respond(ApiMessage(true, "Permission group '$name' deleted"))
            }

            // POST /api/permissions/groups/{name}/permissions — Add permission
            post("{name}/permissions") {
                val name = call.parameters["name"]!!
                if (permissionManager.getGroup(name) == null) {
                    return@post call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Permission group '$name' not found"))
                }
                val request = call.receive<PermissionModifyRequest>()
                val context = PermissionContext(request.server, request.world, request.expiresAt)
                permissionManager.addPermission(name, request.permission, context)
                eventBus.emit(NimbusEvent.PermissionGroupUpdated(name))
                call.respond(ApiMessage(true, "Permission '${request.permission}' added to '$name'"))
            }

            // DELETE /api/permissions/groups/{name}/permissions — Remove permission
            delete("{name}/permissions") {
                val name = call.parameters["name"]!!
                if (permissionManager.getGroup(name) == null) {
                    return@delete call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Permission group '$name' not found"))
                }
                val request = call.receive<PermissionModifyRequest>()
                permissionManager.removePermission(name, request.permission)
                eventBus.emit(NimbusEvent.PermissionGroupUpdated(name))
                call.respond(ApiMessage(true, "Permission '${request.permission}' removed from '$name'"))
            }

            // ── Group Meta ──────────────────────────────────

            // GET /api/permissions/groups/{name}/meta
            get("{name}/meta") {
                val name = call.parameters["name"]!!
                if (permissionManager.getGroup(name) == null) {
                    return@get call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Group '$name' not found"))
                }
                call.respond(MetaResponse(permissionManager.getGroupMeta(name)))
            }

            // PUT /api/permissions/groups/{name}/meta — Set meta key
            put("{name}/meta") {
                val name = call.parameters["name"]!!
                if (permissionManager.getGroup(name) == null) {
                    return@put call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Group '$name' not found"))
                }
                val request = call.receive<MetaSetRequest>()
                permissionManager.setGroupMeta(name, request.key, request.value)
                eventBus.emit(NimbusEvent.PermissionGroupUpdated(name))
                call.respond(ApiMessage(true, "Meta '${request.key}' set on group '$name'"))
            }

            // DELETE /api/permissions/groups/{name}/meta/{key}
            delete("{name}/meta/{key}") {
                val name = call.parameters["name"]!!
                val key = call.parameters["key"]!!
                if (permissionManager.getGroup(name) == null) {
                    return@delete call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Group '$name' not found"))
                }
                permissionManager.removeGroupMeta(name, key)
                eventBus.emit(NimbusEvent.PermissionGroupUpdated(name))
                call.respond(ApiMessage(true, "Meta '$key' removed from group '$name'"))
            }
        }

        // ── Players ─────────────────────────────────────────

        route("players") {

            // GET /api/permissions/players/{uuid}
            get("{uuid}") {
                val uuid = call.parameters["uuid"]!!
                val entry = permissionManager.getPlayer(uuid)
                val effective = permissionManager.getEffectivePermissions(uuid)
                val display = permissionManager.getPlayerDisplay(uuid)
                call.respond(PlayerPermissionResponse(
                    uuid = uuid,
                    name = entry?.name ?: "unknown",
                    groups = entry?.groups ?: emptyList(),
                    effectivePermissions = effective.sorted(),
                    prefix = display.prefix,
                    suffix = display.suffix,
                    displayGroup = display.groupName,
                    meta = entry?.meta ?: emptyMap()
                ))
            }

            // PUT /api/permissions/players/{uuid} — Register/update player (called on join)
            put("{uuid}") {
                val uuid = call.parameters["uuid"]!!
                val request = call.receive<PlayerRegisterRequest>()
                permissionManager.registerPlayer(uuid, request.name)
                val effective = permissionManager.getEffectivePermissions(uuid)
                val display = permissionManager.getPlayerDisplay(uuid)
                val entry = permissionManager.getPlayer(uuid)
                call.respond(PlayerPermissionResponse(
                    uuid = uuid,
                    name = request.name,
                    groups = entry?.groups ?: emptyList(),
                    effectivePermissions = effective.sorted(),
                    prefix = display.prefix,
                    suffix = display.suffix,
                    displayGroup = display.groupName,
                    meta = entry?.meta ?: emptyMap()
                ))
            }

            // POST /api/permissions/players/{uuid}/groups — Add group to player
            post("{uuid}/groups") {
                val uuid = call.parameters["uuid"]!!
                val request = call.receive<PlayerGroupRequest>()
                val playerName = request.name ?: permissionManager.getPlayer(uuid)?.name ?: "unknown"

                try {
                    val context = PermissionContext(request.server, request.world, request.expiresAt)
                    permissionManager.setPlayerGroup(uuid, playerName, request.group, context)
                    permissionManager.logAudit("api", "user.addgroup", uuid, "Added group '${request.group}' to '$playerName'")
                    eventBus.emit(NimbusEvent.PlayerPermissionsUpdated(uuid, playerName))
                    call.respond(ApiMessage(true, "Group '${request.group}' added to player '$playerName'"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ApiMessage(false, e.message ?: "Invalid request"))
                }
            }

            // DELETE /api/permissions/players/{uuid}/groups — Remove group from player
            delete("{uuid}/groups") {
                val uuid = call.parameters["uuid"]!!
                val request = call.receive<PlayerGroupRequest>()
                val playerName = permissionManager.getPlayer(uuid)?.name ?: "unknown"

                try {
                    permissionManager.removePlayerGroup(uuid, request.group)
                    permissionManager.logAudit("api", "user.removegroup", uuid, "Removed group '${request.group}' from '$playerName'")
                    eventBus.emit(NimbusEvent.PlayerPermissionsUpdated(uuid, playerName))
                    call.respond(ApiMessage(true, "Group '${request.group}' removed from player '$playerName'"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ApiMessage(false, e.message ?: "Invalid request"))
                }
            }

            // ── Player Meta ─────────────────────────────────

            // GET /api/permissions/players/{uuid}/meta
            get("{uuid}/meta") {
                val uuid = call.parameters["uuid"]!!
                call.respond(MetaResponse(permissionManager.getPlayerMeta(uuid)))
            }

            // PUT /api/permissions/players/{uuid}/meta — Set meta key
            put("{uuid}/meta") {
                val uuid = call.parameters["uuid"]!!
                if (permissionManager.getPlayer(uuid) == null) {
                    return@put call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Player not found"))
                }
                val request = call.receive<MetaSetRequest>()
                permissionManager.setPlayerMeta(uuid, request.key, request.value)
                val playerName = permissionManager.getPlayer(uuid)?.name ?: "unknown"
                eventBus.emit(NimbusEvent.PlayerPermissionsUpdated(uuid, playerName))
                call.respond(ApiMessage(true, "Meta '${request.key}' set on player '$playerName'"))
            }

            // DELETE /api/permissions/players/{uuid}/meta/{key}
            delete("{uuid}/meta/{key}") {
                val uuid = call.parameters["uuid"]!!
                val key = call.parameters["key"]!!
                if (permissionManager.getPlayer(uuid) == null) {
                    return@delete call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Player not found"))
                }
                permissionManager.removePlayerMeta(uuid, key)
                val playerName = permissionManager.getPlayer(uuid)?.name ?: "unknown"
                eventBus.emit(NimbusEvent.PlayerPermissionsUpdated(uuid, playerName))
                call.respond(ApiMessage(true, "Meta '$key' removed from player"))
            }
        }

        // ── Permission Check ────────────────────────────────

        // GET /api/permissions/check/{uuid}/{permission}
        get("check/{uuid}/{permission...}") {
            val uuid = call.parameters["uuid"]!!
            val permission = call.parameters["permission"]!!
            val allowed = permissionManager.hasPermission(uuid, permission)
            call.respond(PermissionCheckResponse(uuid, permission, allowed))
        }

        // ── Permission Debug ────────────────────────────────

        // GET /api/permissions/debug/{uuid}/{permission}
        get("debug/{uuid}/{permission...}") {
            val uuid = call.parameters["uuid"]!!
            val permission = call.parameters["permission"]!!
            val server = call.request.queryParameters["server"]
            val world = call.request.queryParameters["world"]

            val result = permissionManager.checkPermission(uuid, permission, server, world)
            call.respond(PermissionDebugResponse(
                uuid = uuid,
                permission = result.permission,
                result = result.result,
                reason = result.reason,
                chain = result.chain.map { DebugStepResponse(it.source, it.permission, it.type, it.granted) }
            ))
        }

        // ── Tracks ──────────────────────────────────────────

        route("tracks") {

            // GET /api/permissions/tracks
            get {
                val tracks = permissionManager.getAllTracks().map { PermissionTrackResponse(it.name, it.groups) }
                call.respond(PermissionTrackListResponse(tracks, tracks.size))
            }

            // GET /api/permissions/tracks/{name}
            get("{name}") {
                val name = call.parameters["name"]!!
                val track = permissionManager.getTrack(name)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Track '$name' not found"))
                call.respond(PermissionTrackResponse(track.name, track.groups))
            }

            // POST /api/permissions/tracks — Create track
            post {
                val request = call.receive<CreateTrackRequest>()
                try {
                    val track = permissionManager.createTrack(request.name, request.groups)
                    permissionManager.logAudit("api", "track.create", track.name, "Created track with groups: ${track.groups.joinToString(", ")}")
                    eventBus.emit(NimbusEvent.PermissionTrackCreated(track.name))
                    call.respond(HttpStatusCode.Created, ApiMessage(true, "Track '${track.name}' created"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ApiMessage(false, e.message ?: "Invalid request"))
                }
            }

            // DELETE /api/permissions/tracks/{name}
            delete("{name}") {
                val name = call.parameters["name"]!!
                try {
                    permissionManager.deleteTrack(name)
                    permissionManager.logAudit("api", "track.delete", name, "Deleted track '$name'")
                    eventBus.emit(NimbusEvent.PermissionTrackDeleted(name))
                    call.respond(ApiMessage(true, "Track '$name' deleted"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.NotFound, ApiMessage(false, e.message ?: "Track not found"))
                }
            }

            // POST /api/permissions/tracks/{name}/promote/{uuid}
            post("{name}/promote/{uuid}") {
                val trackName = call.parameters["name"]!!
                val uuid = call.parameters["uuid"]!!

                try {
                    val newGroup = permissionManager.promote(uuid, trackName)
                    val playerName = permissionManager.getPlayer(uuid)?.name ?: "unknown"
                    if (newGroup != null) {
                        permissionManager.logAudit("api", "user.promote", uuid, "Promoted '$playerName' to '$newGroup' on track '$trackName'")
                        eventBus.emit(NimbusEvent.PlayerPromoted(uuid, playerName, trackName, newGroup))
                        eventBus.emit(NimbusEvent.PlayerPermissionsUpdated(uuid, playerName))
                        call.respond(PromoteDemoteResponse(true, null, newGroup, "Promoted to '$newGroup'"))
                    } else {
                        call.respond(PromoteDemoteResponse(false, null, null, "Player is already at the highest rank on track '$trackName'"))
                    }
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, PromoteDemoteResponse(false, message = e.message ?: "Invalid request"))
                }
            }

            // POST /api/permissions/tracks/{name}/demote/{uuid}
            post("{name}/demote/{uuid}") {
                val trackName = call.parameters["name"]!!
                val uuid = call.parameters["uuid"]!!

                try {
                    val newGroup = permissionManager.demote(uuid, trackName)
                    val playerName = permissionManager.getPlayer(uuid)?.name ?: "unknown"
                    if (newGroup != null) {
                        permissionManager.logAudit("api", "user.demote", uuid, "Demoted '$playerName' to '$newGroup' on track '$trackName'")
                        eventBus.emit(NimbusEvent.PlayerDemoted(uuid, playerName, trackName, newGroup))
                        eventBus.emit(NimbusEvent.PlayerPermissionsUpdated(uuid, playerName))
                        call.respond(PromoteDemoteResponse(true, null, newGroup, "Demoted to '$newGroup'"))
                    } else {
                        call.respond(PromoteDemoteResponse(false, null, null, "Player is already at the lowest rank on track '$trackName'"))
                    }
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, PromoteDemoteResponse(false, message = e.message ?: "Invalid request"))
                }
            }
        }

        // ── Audit Log ───────────────────────────────────────

        // GET /api/permissions/audit
        get("audit") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val entries = permissionManager.getAuditLog(limit, offset)
            call.respond(AuditLogResponse(
                entries = entries.map { AuditEntryResponse(it.timestamp, it.actor, it.action, it.target, it.details) },
                total = entries.size
            ))
        }
    }
}

private fun dev.kryonix.nimbus.permissions.PermissionGroup.toResponse() = PermissionGroupResponse(
    name = name,
    default = default,
    prefix = prefix,
    suffix = suffix,
    priority = priority,
    weight = weight,
    permissions = permissions.toList(),
    parents = parents.toList(),
    meta = meta.toMap()
)
