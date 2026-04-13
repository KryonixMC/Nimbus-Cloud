package dev.nimbuspowered.nimbus.module.backup.routes

import dev.nimbuspowered.nimbus.module.backup.BackupManager
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.io.File

fun Route.backupRoutes(manager: BackupManager) {

    route("/api/backups") {

        // GET /api/backups?targetId=templates&limit=50
        get {
            val targetId = call.request.queryParameters["targetId"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50

            val entries = manager.listBackups(targetId, limit.coerceIn(1, 200))
            call.respond(BackupListResponse(
                entries = entries.map { it.toResponse() },
                count = entries.size
            ))
        }

        // POST /api/backups — create backup
        post {
            val req = runCatching { call.receive<CreateBackupRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
                return@post
            }

            if (req.targetId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "targetId is required"))
                return@post
            }

            val result = manager.createBackup(req.targetId, triggeredBy = "api")
            if (result.success) {
                call.respond(HttpStatusCode.Created, BackupResultResponse(
                    success = true,
                    backupId = result.backupId,
                    archivePath = result.archivePath,
                    sizeBytes = result.sizeBytes,
                    durationMs = result.durationMs,
                    error = null
                ))
            } else {
                call.respond(HttpStatusCode.InternalServerError, BackupResultResponse(
                    success = false,
                    backupId = null,
                    archivePath = null,
                    sizeBytes = 0L,
                    durationMs = result.durationMs,
                    error = result.error
                ))
            }
        }

        // GET /api/backups/status
        get("status") {
            val status = manager.getStatus()
            call.respond(BackupStatusResponse(
                activeJobs = status.activeJobs,
                totalBackupCount = status.totalBackupCount,
                totalSizeBytes = status.totalSizeBytes,
                backupDir = status.backupDir
            ))
        }

        // GET /api/backups/{id}/download — stream archive
        get("{id}/download") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid backup ID"))

            val entries = manager.listBackups(null, 1000)
            val entry = entries.find { it.id == id }
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Backup #$id not found"))

            val archiveFile = File(entry.archivePath)
            if (!archiveFile.exists()) {
                call.respond(HttpStatusCode.Gone, mapOf("error" to "Archive file no longer exists"))
                return@get
            }

            call.response.header(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"${archiveFile.name}\""
            )
            call.respondFile(archiveFile)
        }

        // POST /api/backups/{id}/restore
        post("{id}/restore") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid backup ID"))

            val result = manager.restoreBackup(id)
            if (result.success) {
                call.respond(mapOf("success" to "true", "message" to "Backup #$id restored"))
            } else {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (result.error ?: "Restore failed"))
                )
            }
        }

        // DELETE /api/backups/{id}
        delete("{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid backup ID"))

            val deleted = manager.deleteBackup(id)
            if (deleted) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Backup #$id not found"))
            }
        }
    }
}

// ── Request / Response DTOs ───────────────────────────

@Serializable
data class CreateBackupRequest(
    val targetId: String
)

@Serializable
data class BackupEntryResponse(
    val id: Long,
    val createdAt: String,
    val targetId: String,
    val targetType: String,
    val archivePath: String,
    val sizeBytes: Long,
    val status: String,
    val errorMessage: String?,
    val durationMs: Long
)

@Serializable
data class BackupListResponse(
    val entries: List<BackupEntryResponse>,
    val count: Int
)

@Serializable
data class BackupResultResponse(
    val success: Boolean,
    val backupId: Long?,
    val archivePath: String?,
    val sizeBytes: Long,
    val durationMs: Long,
    val error: String?
)

@Serializable
data class BackupStatusResponse(
    val activeJobs: List<String>,
    val totalBackupCount: Long,
    val totalSizeBytes: Long,
    val backupDir: String
)

private fun BackupManager.BackupEntry.toResponse() = BackupEntryResponse(
    id = id,
    createdAt = createdAt,
    targetId = targetId,
    targetType = targetType,
    archivePath = archivePath,
    sizeBytes = sizeBytes,
    status = status,
    errorMessage = errorMessage,
    durationMs = durationMs
)
