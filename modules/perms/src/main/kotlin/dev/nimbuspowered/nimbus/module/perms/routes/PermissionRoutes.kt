package dev.nimbuspowered.nimbus.module.perms.routes

import dev.nimbuspowered.nimbus.api.ApiError
import dev.nimbuspowered.nimbus.api.ApiMessage
import dev.nimbuspowered.nimbus.api.apiError
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.module.perms.*
import dev.nimbuspowered.nimbus.module.perms.PermissionContext
import dev.nimbuspowered.nimbus.module.perms.PermissionManager
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
                    ?: return@get call.respond(HttpStatusCode.NotFound, apiError("Permission group '$name' not found", ApiError.PERMISSION_GROUP_NOT_FOUND))
                call.respond(group.toResponse())
            }

            // POST /api/permissions/groups — Create group
            post {
                val request = call.receive<CreatePermissionGroupRequest>()
                if (request.name.isBlank() || !request.name.matches(Regex("^[a-zA-Z0-9_-]{1,64}$"))) {
                    return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid group name", ApiError.VALIDATION_FAILED))
                }
                if (permissionManager.getGroup(request.name) != null) {
                    return@post call.respond(HttpStatusCode.Conflict, apiError("Group '${request.name}' already exists", ApiError.GROUP_ALREADY_EXISTS))
                }

                val group = permissionManager.createGroup(request.name, request.default)
                permissionManager.logAudit("api", "group.create", group.name, "Created group '${group.name}'")
                eventBus.emit(PermsEvents.groupCreated(group.name))
                call.respond(HttpStatusCode.Created, ApiMessage(true, "Permission group '${group.name}' created"))
            }

            // PUT /api/permissions/groups/{name} — Update group
            put("{name}") {
                val name = call.parameters["name"]!!
                val group = permissionManager.getGroup(name)
                    ?: return@put call.respond(HttpStatusCode.NotFound, apiError("Permission group '$name' not found", ApiError.PERMISSION_GROUP_NOT_FOUND))

                val request = call.receive<UpdatePermissionGroupRequest>()
                request.default?.let { permissionManager.setDefault(group.name, it) }
                if (request.prefix != null || request.suffix != null || request.priority != null) {
                    permissionManager.updateGroupDisplay(group.name, request.prefix, request.suffix, request.priority)
                }
                request.weight?.let { permissionManager.setGroupWeight(group.name, it) }
                request.permissions?.let {
                    group.permissions.clear()
                    group.permissions.addAll(it)
                }
                request.parents?.let {
                    group.parents.clear()
                    group.parents.addAll(it)
                }
                request.meta?.let {
                    group.meta.clear()
                    group.meta.putAll(it)
                }

                permissionManager.logAudit("api", "group.update", group.name, "Updated group '${group.name}'")
                eventBus.emit(PermsEvents.groupUpdated(group.name))
                call.respond(ApiMessage(true, "Permission group '${group.name}' updated"))
            }

            // DELETE /api/permissions/groups/{name}
            delete("{name}") {
                val name = call.parameters["name"]!!
                if (permissionManager.getGroup(name) == null) {
                    return@delete call.respond(HttpStatusCode.NotFound, apiError("Permission group '$name' not found", ApiError.PERMISSION_GROUP_NOT_FOUND))
                }

                permissionManager.deleteGroup(name)
                permissionManager.logAudit("api", "group.delete", name, "Deleted group '$name'")
                eventBus.emit(PermsEvents.groupDeleted(name))
                call.respond(ApiMessage(true, "Permission group '$name' deleted"))
            }

            // POST /api/permissions/groups/{name}/permissions — Add permission
            post("{name}/permissions") {
                val name = call.parameters["name"]!!
                if (permissionManager.getGroup(name) == null) {
                    return@post call.respond(HttpStatusCode.NotFound, apiError("Permission group '$name' not found", ApiError.PERMISSION_GROUP_NOT_FOUND))
                }
                val request = call.receive<PermissionModifyRequest>()
                try {
                    val context = PermissionContext(request.server, request.world, request.expiresAt)
                    permissionManager.addPermission(name, request.permission, context)
                    eventBus.emit(PermsEvents.groupUpdated(name))
                    call.respond(ApiMessage(true, "Permission '${request.permission}' added to '$name'"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, apiError(e.message ?: "Invalid permission", ApiError.VALIDATION_FAILED))
                }
            }

            // DELETE /api/permissions/groups/{name}/permissions — Remove permission
            delete("{name}/permissions") {
                val name = call.parameters["name"]!!
                if (permissionManager.getGroup(name) == null) {
                    return@delete call.respond(HttpStatusCode.NotFound, apiError("Permission group '$name' not found", ApiError.PERMISSION_GROUP_NOT_FOUND))
                }
                val request = call.receive<PermissionModifyRequest>()
                permissionManager.removePermission(name, request.permission)
                eventBus.emit(PermsEvents.groupUpdated(name))
                call.respond(ApiMessage(true, "Permission '${request.permission}' removed from '$name'"))
            }

            // ── Group Meta ──────────────────────────────────

            // GET /api/permissions/groups/{name}/meta
            get("{name}/meta") {
                val name = call.parameters["name"]!!
                if (permissionManager.getGroup(name) == null) {
                    return@get call.respond(HttpStatusCode.NotFound, apiError("Group '$name' not found", ApiError.PERMISSION_GROUP_NOT_FOUND))
                }
                call.respond(MetaResponse(permissionManager.getGroupMeta(name)))
            }

            // PUT /api/permissions/groups/{name}/meta — Set meta key
            put("{name}/meta") {
                val name = call.parameters["name"]!!
                if (permissionManager.getGroup(name) == null) {
                    return@put call.respond(HttpStatusCode.NotFound, apiError("Group '$name' not found", ApiError.PERMISSION_GROUP_NOT_FOUND))
                }
                val request = call.receive<MetaSetRequest>()
                permissionManager.setGroupMeta(name, request.key, request.value)
                eventBus.emit(PermsEvents.groupUpdated(name))
                call.respond(ApiMessage(true, "Meta '${request.key}' set on group '$name'"))
            }

            // DELETE /api/permissions/groups/{name}/meta/{key}
            delete("{name}/meta/{key}") {
                val name = call.parameters["name"]!!
                val key = call.parameters["key"]!!
                if (permissionManager.getGroup(name) == null) {
                    return@delete call.respond(HttpStatusCode.NotFound, apiError("Group '$name' not found", ApiError.PERMISSION_GROUP_NOT_FOUND))
                }
                permissionManager.removeGroupMeta(name, key)
                eventBus.emit(PermsEvents.groupUpdated(name))
                call.respond(ApiMessage(true, "Meta '$key' removed from group '$name'"))
            }
        }

        // ── Players ─────────────────────────────────────────

        route("players") {

            // GET /api/permissions/players — List all known players
            get {
                val query = call.request.queryParameters["q"] ?: ""
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val allPlayers = permissionManager.getAllPlayers()
                val filtered = if (query.isNotBlank()) {
                    allPlayers.filter { (_, entry) ->
                        entry.name.contains(query, ignoreCase = true)
                    }
                } else {
                    allPlayers
                }
                val result = filtered.entries.take(limit).map { (uuid, entry) ->
                    val display = permissionManager.getPlayerDisplay(uuid)
                    PlayerListEntry(
                        uuid = uuid,
                        name = entry.name,
                        groups = entry.groups,
                        displayGroup = display.groupName,
                        prefix = display.prefix
                    )
                }
                call.respond(result)
            }

            // GET /api/permissions/players/{uuid}?server=&world=
            get("{uuid}") {
                val uuid = call.parameters["uuid"]!!
                val server = call.request.queryParameters["server"]
                val world = call.request.queryParameters["world"]
                val entry = permissionManager.getPlayer(uuid)
                val effective = permissionManager.getEffectivePermissions(uuid, server, world)
                val display = permissionManager.getPlayerDisplay(uuid)
                call.respond(PlayerPermissionResponse(
                    uuid = uuid,
                    name = entry?.name ?: "unknown",
                    groups = entry?.groups ?: emptyList(),
                    effectivePermissions = effective.sorted(),
                    prefix = display.prefix,
                    suffix = display.suffix,
                    displayGroup = display.groupName,
                    priority = display.priority,
                    meta = entry?.meta ?: emptyMap()
                ))
            }

            // PUT /api/permissions/players/{uuid}?server=&world= — Register/update player (called on join)
            put("{uuid}") {
                val uuid = call.parameters["uuid"]!!
                val server = call.request.queryParameters["server"]
                val world = call.request.queryParameters["world"]
                val request = call.receive<PlayerRegisterRequest>()
                permissionManager.registerPlayer(uuid, request.name)
                val effective = permissionManager.getEffectivePermissions(uuid, server, world)
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
                    priority = display.priority,
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
                    eventBus.emit(PermsEvents.playerUpdated(uuid, playerName))
                    call.respond(ApiMessage(true, "Group '${request.group}' added to player '$playerName'"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, apiError(e.message ?: "Invalid request", ApiError.VALIDATION_FAILED))
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
                    eventBus.emit(PermsEvents.playerUpdated(uuid, playerName))
                    call.respond(ApiMessage(true, "Group '${request.group}' removed from player '$playerName'"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, apiError(e.message ?: "Invalid request", ApiError.VALIDATION_FAILED))
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
                    return@put call.respond(HttpStatusCode.NotFound, apiError("Player not found", ApiError.PLAYER_NOT_FOUND))
                }
                val request = call.receive<MetaSetRequest>()
                permissionManager.setPlayerMeta(uuid, request.key, request.value)
                val playerName = permissionManager.getPlayer(uuid)?.name ?: "unknown"
                eventBus.emit(PermsEvents.playerUpdated(uuid, playerName))
                call.respond(ApiMessage(true, "Meta '${request.key}' set on player '$playerName'"))
            }

            // DELETE /api/permissions/players/{uuid}/meta/{key}
            delete("{uuid}/meta/{key}") {
                val uuid = call.parameters["uuid"]!!
                val key = call.parameters["key"]!!
                if (permissionManager.getPlayer(uuid) == null) {
                    return@delete call.respond(HttpStatusCode.NotFound, apiError("Player not found", ApiError.PLAYER_NOT_FOUND))
                }
                permissionManager.removePlayerMeta(uuid, key)
                val playerName = permissionManager.getPlayer(uuid)?.name ?: "unknown"
                eventBus.emit(PermsEvents.playerUpdated(uuid, playerName))
                call.respond(ApiMessage(true, "Meta '$key' removed from player"))
            }
        }

        // ── Permission Check ────────────────────────────────

        // GET /api/permissions/check/{uuid}/{permission}?server=&world=
        get("check/{uuid}/{permission...}") {
            val uuid = call.parameters["uuid"]!!
            val permission = call.parameters["permission"]!!
            val server = call.request.queryParameters["server"]
            val world = call.request.queryParameters["world"]
            val allowed = permissionManager.hasPermission(uuid, permission, server, world)
            call.respond(PermissionCheckResponse(uuid, permission, allowed))
        }

        // ── Permission Debug ────────────────────────────────

        // GET /api/permissions/debug/{uuid}/{permission}?server=&world=
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
                    ?: return@get call.respond(HttpStatusCode.NotFound, apiError("Track '$name' not found", ApiError.PERMISSION_TRACK_NOT_FOUND))
                call.respond(PermissionTrackResponse(track.name, track.groups))
            }

            // POST /api/permissions/tracks — Create track
            post {
                val request = call.receive<CreateTrackRequest>()
                try {
                    val track = permissionManager.createTrack(request.name, request.groups)
                    permissionManager.logAudit("api", "track.create", track.name, "Created track with groups: ${track.groups.joinToString(", ")}")
                    eventBus.emit(PermsEvents.trackCreated(track.name))
                    call.respond(HttpStatusCode.Created, ApiMessage(true, "Track '${track.name}' created"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, apiError(e.message ?: "Invalid request", ApiError.VALIDATION_FAILED))
                }
            }

            // DELETE /api/permissions/tracks/{name}
            delete("{name}") {
                val name = call.parameters["name"]!!
                try {
                    permissionManager.deleteTrack(name)
                    permissionManager.logAudit("api", "track.delete", name, "Deleted track '$name'")
                    eventBus.emit(PermsEvents.trackDeleted(name))
                    call.respond(ApiMessage(true, "Track '$name' deleted"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.NotFound, apiError(e.message ?: "Track not found", ApiError.PERMISSION_TRACK_NOT_FOUND))
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
                        eventBus.emit(PermsEvents.playerPromoted(uuid, playerName, trackName, newGroup))
                        eventBus.emit(PermsEvents.playerUpdated(uuid, playerName))
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
                        eventBus.emit(PermsEvents.playerDemoted(uuid, playerName, trackName, newGroup))
                        eventBus.emit(PermsEvents.playerUpdated(uuid, playerName))
                        call.respond(PromoteDemoteResponse(true, null, newGroup, "Demoted to '$newGroup'"))
                    } else {
                        call.respond(PromoteDemoteResponse(false, null, null, "Player is already at the lowest rank on track '$trackName'"))
                    }
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, PromoteDemoteResponse(false, message = e.message ?: "Invalid request"))
                }
            }
        }

        // ── Bulk Operations ─────────────────────────────────

        route("bulk") {

            // POST /api/permissions/bulk/permissions — Add a permission to multiple groups
            post("permissions") {
                val request = call.receive<BulkPermissionRequest>()
                try {
                    PermissionManager.validatePermission(request.permission)
                } catch (e: IllegalArgumentException) {
                    return@post call.respond(HttpStatusCode.BadRequest, apiError(e.message ?: "Invalid permission", ApiError.VALIDATION_FAILED))
                }

                val context = PermissionContext(request.server, request.world, request.expiresAt)
                var processed = 0
                val errors = mutableListOf<String>()

                for (groupName in request.groups) {
                    try {
                        permissionManager.addPermission(groupName, request.permission, context)
                        eventBus.emit(PermsEvents.groupUpdated(groupName))
                        processed++
                    } catch (e: Exception) {
                        errors.add("$groupName: ${e.message}")
                    }
                }

                permissionManager.logAudit("api", "bulk.addperm", request.permission,
                    "Added '${request.permission}' to ${processed}/${request.groups.size} groups")

                call.respond(BulkOperationResponse(
                    success = errors.isEmpty(),
                    processed = processed,
                    failed = errors.size,
                    errors = errors
                ))
            }

            // POST /api/permissions/bulk/groups — Add a group to multiple players
            post("groups") {
                val request = call.receive<BulkGroupAssignRequest>()
                val context = PermissionContext(request.server, request.world, request.expiresAt)
                var processed = 0
                val errors = mutableListOf<String>()

                for (uuid in request.players) {
                    try {
                        val playerName = permissionManager.getPlayer(uuid)?.name ?: uuid
                        permissionManager.setPlayerGroup(uuid, playerName, request.group, context)
                        eventBus.emit(PermsEvents.playerUpdated(uuid, playerName))
                        processed++
                    } catch (e: Exception) {
                        errors.add("$uuid: ${e.message}")
                    }
                }

                permissionManager.logAudit("api", "bulk.addgroup", request.group,
                    "Added group '${request.group}' to ${processed}/${request.players.size} players")

                call.respond(BulkOperationResponse(
                    success = errors.isEmpty(),
                    processed = processed,
                    failed = errors.size,
                    errors = errors
                ))
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

private fun dev.nimbuspowered.nimbus.module.perms.PermissionGroup.toResponse() = PermissionGroupResponse(
    name = name,
    default = default,
    prefix = prefix,
    suffix = suffix,
    priority = priority,
    weight = weight,
    permissions = permissions.toList() + contextualPermissions.keys.toList(),
    parents = parents.toList(),
    meta = meta.toMap()
)
