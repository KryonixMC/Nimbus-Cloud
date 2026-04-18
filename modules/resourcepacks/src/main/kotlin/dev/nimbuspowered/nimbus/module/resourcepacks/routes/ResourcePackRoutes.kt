package dev.nimbuspowered.nimbus.module.resourcepacks.routes

import dev.nimbuspowered.nimbus.api.ApiErrors
import dev.nimbuspowered.nimbus.api.ApiMessage
import dev.nimbuspowered.nimbus.api.apiError
import dev.nimbuspowered.nimbus.api.requirePermission
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.module.resourcepacks.AssignmentRequest
import dev.nimbuspowered.nimbus.module.resourcepacks.CreateResourcePackRequest
import dev.nimbuspowered.nimbus.module.resourcepacks.ResolvedPackListResponse
import dev.nimbuspowered.nimbus.module.resourcepacks.ResourcePackListResponse
import dev.nimbuspowered.nimbus.module.resourcepacks.ResourcePackManager
import dev.nimbuspowered.nimbus.module.resourcepacks.ResourcePacksEvents
import dev.nimbuspowered.nimbus.module.resourcepacks.StatusReportRequest
import dev.nimbuspowered.nimbus.module.resourcepacks.toResponse
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.nio.file.Files

/**
 * Authenticated routes (AuthLevel.SERVICE).
 * CRUD + assignments + status reporting.
 */
fun Route.resourcePackAuthedRoutes(
    manager: ResourcePackManager,
    maxUploadBytes: Long,
    publicBaseUrlProvider: () -> String,
    eventBus: EventBus
) {
    route("/api/resourcepacks") {

        // GET /api/resourcepacks — list all packs
        get {
            if (!call.requirePermission("nimbus.dashboard.resourcepacks.view")) return@get
            val packs = manager.listPacks()
            call.respond(ResourcePackListResponse(packs.map { it.toResponse() }, packs.size))
        }

        // GET /api/resourcepacks/{id}
        get("{id}") {
            if (!call.requirePermission("nimbus.dashboard.resourcepacks.view")) return@get
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, apiError("Invalid id", ApiErrors.VALIDATION_FAILED))
            val pack = manager.getPack(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, apiError("Pack not found", ApiErrors.RESOURCE_PACK_NOT_FOUND))
            call.respond(pack.toResponse())
        }

        // POST /api/resourcepacks — create by URL (JSON body)
        post {
            if (!call.requirePermission("nimbus.dashboard.resourcepacks.manage")) return@post
            val req = call.receive<CreateResourcePackRequest>()
            if (req.name.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, apiError("name is required", ApiErrors.VALIDATION_FAILED))
            }
            if (!req.url.startsWith("http://") && !req.url.startsWith("https://")) {
                return@post call.respond(HttpStatusCode.BadRequest,
                    apiError("URL must be http(s)://", ApiErrors.RESOURCE_PACK_INVALID_URL))
            }
            val sha1 = req.sha1Hash?.lowercase()?.takeIf { it.length == 40 && it.all { c -> c.isDigit() || c in 'a'..'f' } }
                ?: return@post call.respond(HttpStatusCode.BadRequest,
                    apiError("sha1Hash must be a 40-character hex string", ApiErrors.VALIDATION_FAILED))

            val pack = manager.createUrlPack(
                name = req.name,
                url = req.url,
                sha1Hash = sha1,
                promptMessage = req.promptMessage,
                force = req.force,
                uploadedBy = "api"
            )
            eventBus.emit(ResourcePacksEvents.created(pack))
            call.respond(HttpStatusCode.Created, pack.toResponse())
        }

        /*
         * POST /api/resourcepacks/upload?name=...&force=true&prompt=...
         *
         * Raw-body upload (application/octet-stream). Matches the ModpackRoutes
         * pattern — call.receiveStream() returns a java.io.InputStream backed by
         * the Ktor request channel, which reads from the socket as bytes arrive
         * with TCP backpressure. No body buffering on Ktor's side, no multipart
         * parsing, no 250 MB memory spike. The manager downstream uses a fixed
         * 64 KiB transfer buffer so the whole upload path stays streaming from
         * socket → disk.
         *
         * Name / force / prompt arrive as query params to keep the body pure
         * bytes (no Content-Type disposition overhead).
         */
        post("upload") {
            if (!call.requirePermission("nimbus.dashboard.resourcepacks.manage")) return@post
            val name = call.request.queryParameters["name"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("name query param is required", ApiErrors.VALIDATION_FAILED))
            val force = call.request.queryParameters["force"]?.toBooleanStrictOrNull() ?: false
            val prompt = call.request.queryParameters["prompt"] ?: ""

            val created = try {
                call.receiveStream().use { input ->
                    manager.uploadLocalPack(
                        name = name,
                        input = input,
                        maxBytes = maxUploadBytes,
                        promptMessage = prompt,
                        force = force,
                        uploadedBy = "api"
                    )
                }
            } catch (e: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.PayloadTooLarge,
                    apiError(e.message ?: "Upload rejected", ApiErrors.PAYLOAD_TOO_LARGE))
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.InternalServerError,
                    apiError("Upload failed: ${e.message}", ApiErrors.RESOURCE_PACK_UPLOAD_FAILED))
            }

            eventBus.emit(ResourcePacksEvents.created(created))
            call.respond(HttpStatusCode.Created, created.toResponse())
        }

        // DELETE /api/resourcepacks/{id}
        delete("{id}") {
            if (!call.requirePermission("nimbus.dashboard.resourcepacks.manage")) return@delete
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, apiError("Invalid id", ApiErrors.VALIDATION_FAILED))
            val pack = manager.getPack(id)
                ?: return@delete call.respond(HttpStatusCode.NotFound, apiError("Pack not found", ApiErrors.RESOURCE_PACK_NOT_FOUND))
            manager.deletePack(id)
            eventBus.emit(ResourcePacksEvents.deleted(id, pack.name))
            call.respond(ApiMessage(true, "Pack '${pack.name}' deleted"))
        }

        // ── Assignments ─────────────────────────────────────────

        // GET /api/resourcepacks/assignments
        get("assignments") {
            if (!call.requirePermission("nimbus.dashboard.resourcepacks.view")) return@get
            val packId = call.request.queryParameters["packId"]?.toIntOrNull()
            val list = manager.listAssignments(packId)
            call.respond(list.map { it.toResponse() })
        }

        // POST /api/resourcepacks/{id}/assignments
        post("{id}/assignments") {
            if (!call.requirePermission("nimbus.dashboard.resourcepacks.assign")) return@post
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid id", ApiErrors.VALIDATION_FAILED))
            val req = call.receive<AssignmentRequest>()
            val scope = req.scope.uppercase()
            if (scope !in setOf("GLOBAL", "GROUP", "SERVICE")) {
                return@post call.respond(HttpStatusCode.BadRequest,
                    apiError("scope must be GLOBAL, GROUP, or SERVICE", ApiErrors.VALIDATION_FAILED))
            }
            if (scope != "GLOBAL" && req.target.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest,
                    apiError("target is required for scope $scope", ApiErrors.VALIDATION_FAILED))
            }
            val assignment = manager.createAssignment(id, scope, if (scope == "GLOBAL") "" else req.target, req.priority)
                ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Pack not found", ApiErrors.RESOURCE_PACK_NOT_FOUND))
            eventBus.emit(ResourcePacksEvents.assigned(id, scope, assignment.target))
            call.respond(HttpStatusCode.Created, assignment.toResponse())
        }

        // DELETE /api/resourcepacks/assignments/{id}
        delete("assignments/{id}") {
            if (!call.requirePermission("nimbus.dashboard.resourcepacks.assign")) return@delete
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, apiError("Invalid id", ApiErrors.VALIDATION_FAILED))
            val deleted = manager.deleteAssignment(id)
            if (!deleted) {
                return@delete call.respond(HttpStatusCode.NotFound,
                    apiError("Assignment not found", ApiErrors.RESOURCE_PACK_ASSIGNMENT_NOT_FOUND))
            }
            call.respond(ApiMessage(true, "Assignment removed"))
        }

        // ── Plugin-facing queries ───────────────────────────────

        // GET /api/resourcepacks/for-group/{group}?service=<name>
        // Returns the ordered stack of packs the backend should apply.
        get("for-group/{group}") {
            val group = call.parameters["group"]!!
            val service = call.request.queryParameters["service"] ?: ""
            val packs = manager.resolvePacks(group, service, publicBaseUrlProvider())
            call.respond(ResolvedPackListResponse(packs.map { it.toResponse() }))
        }

        // POST /api/resourcepacks/status — backend reports accept/decline/load
        post("status") {
            val req = call.receive<StatusReportRequest>()
            eventBus.emit(ResourcePacksEvents.statusReport(req.playerUuid, req.packUuid, req.status))
            call.respond(ApiMessage(true, "Status recorded"))
        }
    }
}

/**
 * Public route (AuthLevel.NONE). Serves locally hosted pack files by UUID.
 * No auth because Minecraft clients don't send bearer tokens — the SHA-1 hash
 * negotiated during setResourcePack() protects against tampering.
 */
fun Route.resourcePackPublicRoutes(manager: ResourcePackManager) {
    get("/api/resourcepacks/files/{name}") {
        val filename = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.NotFound)
        if (!filename.endsWith(".zip")) {
            return@get call.respond(HttpStatusCode.NotFound)
        }
        val packUuid = filename.removeSuffix(".zip")
        // Guard against traversal
        if (packUuid.contains('/') || packUuid.contains('\\') || packUuid.contains("..")) {
            return@get call.respond(HttpStatusCode.BadRequest, apiError("Invalid pack name", ApiErrors.PATH_TRAVERSAL))
        }
        val file = manager.localPackFile(packUuid)
            ?: return@get call.respond(HttpStatusCode.NotFound, apiError("Pack file not found", ApiErrors.RESOURCE_PACK_NOT_FOUND))

        call.response.header(HttpHeaders.ContentType, "application/zip")
        call.response.header(HttpHeaders.ContentLength, Files.size(file).toString())
        call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
        // Streaming download: respondOutputStream wraps Ktor's ByteWriteChannel so
        // bytes flow with backpressure, never buffering the whole file. copyTo()
        // uses a 64 KiB transfer buffer — matches the upload path, keeps the
        // per-request memory footprint tiny regardless of pack size.
        call.respondOutputStream(ContentType.parse("application/zip")) {
            Files.newInputStream(file).use { input -> input.copyTo(this, 64 * 1024) }
        }
    }
}
