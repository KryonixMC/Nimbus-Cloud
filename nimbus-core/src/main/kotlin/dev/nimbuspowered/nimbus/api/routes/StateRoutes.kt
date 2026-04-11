package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.api.ApiErrors
import dev.nimbuspowered.nimbus.api.NimbusApi
import dev.nimbuspowered.nimbus.api.apiError
import dev.nimbuspowered.nimbus.protocol.StateManifest
import dev.nimbuspowered.nimbus.service.StateSyncManager
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.serialization.Serializable
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("StateRoutes")
private val json = Json { ignoreUnknownKeys = true }

/**
 * Service state sync endpoints — used by agents to pull/push the canonical copy
 * of a service's working directory. Gated by the cluster token (shared secret),
 * not the REST API token, because agents authenticate as cluster participants.
 *
 * Routes (all query-param `?token=<clusterToken>`):
 *   GET  /api/services/{name}/state/manifest          → StateManifest JSON
 *   GET  /api/services/{name}/state/file/{path...}    → raw file bytes
 *   POST /api/services/{name}/state/sync              → multipart { manifest, files... }
 *                                                       atomic commit on success
 *
 * Pulling: agent fetches the manifest, compares with its local state, then issues
 * a `GET ...state/file/...` for each file that differs. Files are not zipped — the
 * delta is streamed file-by-file.
 *
 * Pushing: agent sends a single multipart POST containing its target manifest
 * (JSON part named "manifest") and the bytes of every file to upload (one part per
 * file, named "file:<relpath>"). The controller stages each part, validates hashes,
 * and on the final read of the last part atomically commits.
 */
fun Route.stateRoutes(
    stateSyncManager: StateSyncManager,
    clusterToken: String,
    eventBus: dev.nimbuspowered.nimbus.event.EventBus? = null
) {
    // Manifest: always authenticated, even if empty
    get("/api/services/{name}/state/manifest") {
        if (!validateClusterToken(call, clusterToken)) {
            call.respond(HttpStatusCode.Unauthorized, apiError("Invalid token", ApiErrors.FORBIDDEN))
            return@get
        }
        val name = call.parameters["name"]!!
        val manifest = stateSyncManager.buildManifest(name)
        call.respond(manifest)
    }

    // Stream single file
    get("/api/services/{name}/state/file/{path...}") {
        if (!validateClusterToken(call, clusterToken)) {
            call.respond(HttpStatusCode.Unauthorized, apiError("Invalid token", ApiErrors.FORBIDDEN))
            return@get
        }
        val name = call.parameters["name"]!!
        val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
        if (path.isBlank() || ".." in path) {
            call.respond(HttpStatusCode.BadRequest, apiError("Invalid path", ApiErrors.INVALID_INPUT))
            return@get
        }
        val file = stateSyncManager.openFileForRead(name, path)
        if (file == null) {
            call.respond(HttpStatusCode.NotFound, apiError("File not found: $path", ApiErrors.NOT_FOUND))
            return@get
        }
        call.respondOutputStream(contentType = ContentType.Application.OctetStream) {
            java.nio.file.Files.newInputStream(file).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    write(buf, 0, n)
                }
            }
        }
    }

    // Atomic sync: agent posts manifest + files, controller stages + commits
    post("/api/services/{name}/state/sync") {
        if (!validateClusterToken(call, clusterToken)) {
            call.respond(HttpStatusCode.Unauthorized, apiError("Invalid token", ApiErrors.FORBIDDEN))
            return@post
        }
        val name = call.parameters["name"]!!

        // Acquire per-service sync lock so concurrent pushes don't stomp on each
        // other's staging dir. Fail fast with 409 if another sync is in progress —
        // the caller can retry later.
        if (!stateSyncManager.tryAcquireLock(name)) {
            call.respond(
                HttpStatusCode.Conflict,
                apiError("Another sync for '$name' is already in progress", ApiErrors.INVALID_INPUT)
            )
            return@post
        }

        var manifest: StateManifest? = null
        var filesReceived = 0
        var bytesReceived = 0L
        var success = false
        val startTime = System.currentTimeMillis()

        try {
            // Seed staging by hardlinking from canonical, so the agent only has to
            // upload files that differ from the controller's current copy.
            stateSyncManager.beginSync(name)

            // 10 GB cap — world syncs can be large and we'd rather the user set a
            // custom limit than block a legitimate sync. Default of 50 MB is too low.
            val multipart = call.receiveMultipart(formFieldLimit = 10L * 1024 * 1024 * 1024)
            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    when (part) {
                        is PartData.FormItem -> {
                            if (part.name == "manifest") {
                                manifest = json.decodeFromString(StateManifest.serializer(), part.value)
                            }
                        }
                        is PartData.FileItem -> {
                            val partName = part.name
                            if (partName != null && partName.startsWith("file:")) {
                                val relPath = partName.removePrefix("file:")
                                val expected = manifest?.files?.get(relPath)
                                    ?: throw IllegalStateException("manifest missing entry for $relPath (must precede file parts)")
                                // Suspending read of the entire part into a ByteArray, then stage.
                                // For Ktor 3 we can't reliably convert ByteReadChannel to a blocking
                                // InputStream in non-suspending code — the channel read happens in
                                // the wrong coroutine context and returns 0 bytes.
                                val bytes: ByteArray = part.provider().toByteArray()
                                val staged = stateSyncManager.writeStagedFile(name, relPath, expected.sha256, bytes.inputStream())
                                // If the file was rewritten between scan and upload, patch the manifest
                                // entry with the actual SHA so the committed canonical accurately reflects
                                // the bytes on disk and future delta calculations are correct.
                                if (!staged.actualSha256.equals(expected.sha256, ignoreCase = true)) {
                                    manifest = manifest?.let { m ->
                                        m.copy(files = m.files + (relPath to expected.copy(sha256 = staged.actualSha256, size = staged.bytes)))
                                    }
                                }
                                filesReceived += 1
                                bytesReceived += staged.bytes
                            }
                        }
                        else -> {}
                    }
                } finally {
                    part.dispose()
                }
            }

            val m = manifest ?: throw IllegalStateException("missing manifest part")
            stateSyncManager.commitSync(name, m)
            success = true
            val duration = System.currentTimeMillis() - startTime
            eventBus?.emit(dev.nimbuspowered.nimbus.event.NimbusEvent.SyncCompleted(
                serviceName = name,
                filesInManifest = m.files.size,
                filesReceived = filesReceived,
                bytesReceived = bytesReceived,
                durationMs = duration
            ))

            call.respond(StateSyncResponse(
                success = true,
                filesReceived = filesReceived,
                bytesReceived = bytesReceived,
                filesInManifest = m.files.size
            ))
        } catch (e: StateSyncManager.QuotaExceededException) {
            logger.warn("State sync for '{}' rejected: {}", name, e.message)
            try { stateSyncManager.abortSync(name) } catch (_: Exception) {}
            eventBus?.emit(dev.nimbuspowered.nimbus.event.NimbusEvent.SyncFailed(
                serviceName = name,
                reason = "quota exceeded: ${e.message}"
            ))
            call.respond(
                HttpStatusCode.InsufficientStorage,
                apiError("Quota exceeded: ${e.message}", ApiErrors.INTERNAL_ERROR)
            )
        } catch (e: Exception) {
            logger.error("State sync for '{}' failed: {}", name, e.message, e)
            try { stateSyncManager.abortSync(name) } catch (_: Exception) {}
            eventBus?.emit(dev.nimbuspowered.nimbus.event.NimbusEvent.SyncFailed(
                serviceName = name,
                reason = e.message ?: e::class.simpleName ?: "unknown"
            ))
            if (!success) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    apiError("Sync failed: ${e.message}", ApiErrors.INTERNAL_ERROR)
                )
            }
        } finally {
            stateSyncManager.releaseLock(name)
        }
    }
}

@Serializable
data class StateSyncResponse(
    val success: Boolean,
    val filesReceived: Int,
    val bytesReceived: Long,
    val filesInManifest: Int
)

private fun validateClusterToken(call: io.ktor.server.application.ApplicationCall, expected: String): Boolean {
    if (expected.isBlank()) return false
    val presented = call.request.queryParameters["token"] ?: ""
    return NimbusApi.timingSafeEquals(presented, expected)
}
