package dev.nimbuspowered.nimbus.module.backup.routes

import dev.nimbuspowered.nimbus.api.ApiErrors
import dev.nimbuspowered.nimbus.api.ApiMessage
import dev.nimbuspowered.nimbus.api.apiError
import dev.nimbuspowered.nimbus.module.backup.BackupConfigManager
import dev.nimbuspowered.nimbus.module.backup.BackupListResponse
import dev.nimbuspowered.nimbus.module.backup.BackupManager
import dev.nimbuspowered.nimbus.module.backup.BackupModuleConfig
import dev.nimbuspowered.nimbus.module.backup.BackupRetention
import dev.nimbuspowered.nimbus.module.backup.BackupScheduler
import dev.nimbuspowered.nimbus.module.backup.BackupTargetType
import dev.nimbuspowered.nimbus.module.backup.PruneRequest
import dev.nimbuspowered.nimbus.module.backup.PruneResponse
import dev.nimbuspowered.nimbus.module.backup.RestoreBackupRequest
import dev.nimbuspowered.nimbus.module.backup.RetentionClass
import dev.nimbuspowered.nimbus.module.backup.TriggerBackupRequest
import dev.nimbuspowered.nimbus.module.backup.VerifyResponse
import dev.nimbuspowered.nimbus.module.backup.toResponse
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Admin-only backup API. Archives can contain secrets (world data, plugin
 * configs, DB dumps) — don't drop to SERVICE auth.
 */
fun Route.backupRoutes(
    manager: BackupManager,
    retention: BackupRetention,
    scheduler: BackupScheduler,
    configManager: BackupConfigManager
) {
    route("/api/backups") {

        // GET /api/backups/config — full serialized config for the settings UI
        get("config") {
            call.respond(configManager.getConfig())
        }

        // PUT /api/backups/config — replace config, validate, persist to TOML, hot-reload
        put("config") {
            val cfg = try { call.receive<BackupModuleConfig>() }
            catch (e: Exception) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    apiError("Invalid body: ${e.message}", ApiErrors.VALIDATION_FAILED)
                )
            }
            try {
                configManager.update(cfg)
                scheduler.reload()
                call.respond(configManager.getConfig())
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, apiError(e.message ?: "invalid config", ApiErrors.VALIDATION_FAILED))
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    apiError("Failed to write config: ${e.message}", ApiErrors.INTERNAL_ERROR)
                )
            }
        }

        // GET /api/backups?target=&status=&limit=&offset=
        get {
            val target = call.request.queryParameters["target"]
            val status = call.request.queryParameters["status"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 100
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val records = manager.list(target, status, limit, offset)
            val total = manager.count().toInt()
            call.respond(BackupListResponse(records.map { it.toResponse() }, total))
        }

        get("schedules") {
            call.respond(scheduler.describeSchedules())
        }

        get("status") {
            call.respond(mapOf(
                "activeJobs" to manager.activeJobs(),
                "localDestination" to manager.localDestination.toString(),
                "schedules" to scheduler.describeSchedules()
            ))
        }

        get("{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, apiError("Invalid id", ApiErrors.VALIDATION_FAILED))
            val rec = manager.fetchRecord(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, apiError("Backup not found", ApiErrors.NOT_FOUND))
            call.respond(rec.toResponse())
        }

        get("{id}/manifest") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, apiError("Invalid id", ApiErrors.VALIDATION_FAILED))
            val rec = manager.fetchRecord(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, apiError("Backup not found", ApiErrors.NOT_FOUND))
            // Cheapest path: extract and stream MANIFEST.sha256 entry. verify() already
            // reads the whole archive; we avoid doing that here by deferring to the
            // archive extraction helper on-demand.
            val archive = manager.localDestination.resolve(rec.archivePath)
            if (!Files.exists(archive)) {
                return@get call.respond(HttpStatusCode.NotFound, apiError("Archive file missing", ApiErrors.NOT_FOUND))
            }
            // Quick-and-dirty: read the manifest by doing a verify pass — but verify
            // doesn't return the manifest text. We stream-read just the manifest entry here.
            val manifest = readManifest(archive)
                ?: return@get call.respond(HttpStatusCode.NotFound, apiError("Manifest not in archive", ApiErrors.NOT_FOUND))
            call.response.header(HttpHeaders.ContentType, "text/plain")
            call.respondText(manifest, ContentType.parse("text/plain"))
        }

        get("{id}/download") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, apiError("Invalid id", ApiErrors.VALIDATION_FAILED))
            val rec = manager.fetchRecord(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, apiError("Backup not found", ApiErrors.NOT_FOUND))
            val file = manager.localDestination.resolve(rec.archivePath)
            if (!Files.exists(file)) {
                return@get call.respond(HttpStatusCode.NotFound, apiError("Archive file missing", ApiErrors.NOT_FOUND))
            }
            val fileName = file.fileName.toString()
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$fileName\"")
            call.response.header(HttpHeaders.ContentType, "application/zstd")
            call.response.header(HttpHeaders.ContentLength, Files.size(file).toString())
            call.respondOutputStream(ContentType.parse("application/zstd")) {
                Files.newInputStream(file).use { it.copyTo(this, 64 * 1024) }
            }
        }

        post("trigger") {
            val req = try { call.receive<TriggerBackupRequest>() }
            catch (e: Exception) { return@post call.respond(HttpStatusCode.BadRequest,
                apiError("Invalid body: ${e.message}", ApiErrors.VALIDATION_FAILED)) }
            val types: Set<BackupTargetType> = req.targets.mapNotNull { parseTargetType(it) }.toSet()
            val cls = parseRetention(req.scheduleClass)
            val records = manager.runBackup(types, cls, "", "api", req.target)
            call.respond(HttpStatusCode.Created, BackupListResponse(records.map { it.toResponse() }, records.size))
        }

        post("{id}/restore") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid id", ApiErrors.VALIDATION_FAILED))
            val req = try { call.receive<RestoreBackupRequest>() }
            catch (e: Exception) { return@post call.respond(HttpStatusCode.BadRequest,
                apiError("Invalid body: ${e.message}", ApiErrors.VALIDATION_FAILED)) }
            try {
                val result = manager.restore(
                    id,
                    req.targetPath?.let { Path.of(it) },
                    req.dryRun,
                    req.force,
                    "api"
                )
                call.respond(mapOf(
                    "id" to id,
                    "dryRun" to req.dryRun,
                    "filesExtracted" to result.filesExtracted,
                    "files" to result.files
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.Conflict, apiError(e.message ?: "restore failed", ApiErrors.VALIDATION_FAILED))
            }
        }

        post("{id}/verify") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid id", ApiErrors.VALIDATION_FAILED))
            val r = manager.verify(id)
            call.respond(VerifyResponse(r.valid, r.errors))
        }

        delete("{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, apiError("Invalid id", ApiErrors.VALIDATION_FAILED))
            if (manager.delete(id)) {
                call.respond(ApiMessage(true, "Backup #$id deleted"))
            } else {
                call.respond(HttpStatusCode.NotFound, apiError("Backup not found", ApiErrors.NOT_FOUND))
            }
        }

        post("prune") {
            val req = try { call.receive<PruneRequest>() }
            catch (e: Exception) { PruneRequest() }
            val r = retention.prune(req.dryRun, req.retentionClass)
            call.respond(PruneResponse(r.deleted, r.freedBytes, r.errors))
        }
    }
}

private fun parseTargetType(s: String): BackupTargetType? = when (s.lowercase()) {
    "services", "service" -> BackupTargetType.SERVICE
    "dedicated" -> BackupTargetType.DEDICATED
    "templates" -> BackupTargetType.TEMPLATES
    "config" -> BackupTargetType.CONFIG
    "database" -> BackupTargetType.DATABASE
    "state", "state_sync" -> BackupTargetType.STATE_SYNC
    else -> null
}

private fun parseRetention(s: String): RetentionClass = when (s.lowercase()) {
    "hourly" -> RetentionClass.HOURLY
    "daily" -> RetentionClass.DAILY
    "weekly" -> RetentionClass.WEEKLY
    "monthly" -> RetentionClass.MONTHLY
    else -> RetentionClass.MANUAL
}

/** Read the MANIFEST.sha256 entry from a tar.zst archive without extracting files. */
private fun readManifest(archive: Path): String? {
    return Files.newInputStream(archive).use { fis ->
        com.github.luben.zstd.ZstdInputStream(fis).use { zstd ->
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(zstd).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: return@use null
                    if (entry.name == "MANIFEST.sha256") {
                        return@use tar.readAllBytes().toString(Charsets.UTF_8)
                    }
                }
                @Suppress("UNREACHABLE_CODE") null
            }
        }
    }
}
