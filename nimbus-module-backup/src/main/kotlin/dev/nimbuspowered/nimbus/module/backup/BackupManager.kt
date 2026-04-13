package dev.nimbuspowered.nimbus.module.backup

import dev.nimbuspowered.nimbus.database.DatabaseManager
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class BackupManager(
    private val db: DatabaseManager,
    private val configManager: BackupConfigManager,
    private val baseDir: Path,
    private val eventBus: EventBus,
    private val scope: CoroutineScope
) {

    private val logger = LoggerFactory.getLogger(BackupManager::class.java)
    private lateinit var semaphore: Semaphore
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val cronScheduler = SimpleCronScheduler(scope)

    private val timestampFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneOffset.UTC)

    // ── Data Classes ──────────────────────────────────────

    data class BackupResult(
        val success: Boolean,
        val backupId: Long?,
        val archivePath: String?,
        val sizeBytes: Long,
        val durationMs: Long,
        val error: String?
    )

    data class RestoreResult(
        val success: Boolean,
        val error: String?
    )

    data class BackupEntry(
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

    data class BackupStatus(
        val activeJobs: List<String>,
        val totalBackupCount: Long,
        val totalSizeBytes: Long,
        val backupDir: String
    )

    // ── Lifecycle ─────────────────────────────────────────

    fun init() {
        val maxConcurrent = configManager.getConfig().global.maxConcurrent
        semaphore = Semaphore(maxConcurrent)
    }

    fun startScheduler() {
        val config = configManager.getConfig()
        if (!config.global.enabled) {
            logger.info("Backup module is disabled — scheduler not started")
            return
        }

        var scheduled = 0
        for (target in config.targets) {
            val schedule = target.schedule ?: continue
            cronScheduler.schedule(schedule) {
                logger.info("Running scheduled backup for target '{}'", target.id)
                createBackup(target.id, triggeredBy = "schedule")
            }
            scheduled++
        }

        if (scheduled > 0) {
            logger.info("Backup scheduler started ({} target(s) with schedules)", scheduled)
        }
    }

    fun shutdown() {
        cronScheduler.cancelAll()
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
    }

    // ── Backup Operations ─────────────────────────────────

    suspend fun createBackup(targetId: String, triggeredBy: String = "manual"): BackupResult {
        val config = configManager.getConfig()
        val target = config.targets.find { it.id == targetId }
            ?: return BackupResult(
                success = false,
                backupId = null,
                archivePath = null,
                sizeBytes = 0L,
                durationMs = 0L,
                error = "Unknown target '$targetId'"
            )

        return semaphore.withPermit {
            performBackup(target, triggeredBy)
        }
    }

    private suspend fun performBackup(target: BackupTarget, triggeredBy: String): BackupResult {
        val startMs = System.currentTimeMillis()
        val config = configManager.getConfig()
        val sourcePath = baseDir.resolve(target.path)

        if (!sourcePath.exists()) {
            val error = "Source path does not exist: ${sourcePath.toAbsolutePath()}"
            logger.warn(error)
            val durationMs = System.currentTimeMillis() - startMs
            val id = recordEntry(
                target = target,
                archivePath = "",
                sizeBytes = 0L,
                status = "failed",
                errorMessage = error,
                durationMs = durationMs
            )
            emitEvent("BACKUP_FAILED", target, id, error = error)
            return BackupResult(false, id, null, 0L, durationMs, error)
        }

        val timestamp = timestampFormatter.format(Instant.now())
        val backupSubDir = baseDir.resolve(config.global.backupDir).resolve(target.id)
        backupSubDir.createDirectories()

        val archiveName = "${target.id}-${timestamp}.tar.gz"
        val archivePath = backupSubDir.resolve(archiveName)

        return try {
            val sourceFile = sourcePath.toFile()
            val parentDir = sourceFile.parent ?: baseDir.toFile().absolutePath
            val sourceName = sourceFile.name

            val process = ProcessBuilder(
                "tar", "-czf", archivePath.toAbsolutePath().toString(),
                "-C", parentDir,
                sourceName
            )
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            val durationMs = System.currentTimeMillis() - startMs

            if (exitCode != 0) {
                val error = "tar failed (exit $exitCode): $output".take(512)
                logger.warn("Backup failed for target '{}': {}", target.id, error)
                val id = recordEntry(target, archivePath.toString(), 0L, "failed", error, durationMs)
                emitEvent("BACKUP_FAILED", target, id, error = error)
                return BackupResult(false, id, null, 0L, durationMs, error)
            }

            val sizeBytes = archivePath.toFile().length()
            val id = recordEntry(target, archivePath.toString(), sizeBytes, "ok", null, durationMs)

            logger.info(
                "Backup created: {} ({} bytes, {}ms) [{}]",
                archiveName, sizeBytes, durationMs, triggeredBy
            )

            emitEvent("BACKUP_CREATED", target, id, archivePath = archivePath.toString(), sizeBytes = sizeBytes)

            // Prune after successful backup
            scope.launch { pruneBackups(target.id) }

            BackupResult(true, id, archivePath.toString(), sizeBytes, durationMs, null)

        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startMs
            val error = e.message?.take(512) ?: "Unknown error"
            logger.error("Backup exception for target '{}'", target.id, e)
            val id = recordEntry(target, archivePath.toString(), 0L, "failed", error, durationMs)
            emitEvent("BACKUP_FAILED", target, id, error = error)
            BackupResult(false, id, null, 0L, durationMs, error)
        }
    }

    suspend fun restoreBackup(backupId: Long): RestoreResult {
        val entry = db.query {
            BackupEntries.selectAll()
                .where { BackupEntries.id eq backupId }
                .singleOrNull()
                ?.toEntry()
        } ?: return RestoreResult(false, "Backup #$backupId not found")

        if (entry.status != "ok") {
            return RestoreResult(false, "Backup #$backupId has status '${entry.status}' — cannot restore")
        }

        val archiveFile = File(entry.archivePath)
        if (!archiveFile.exists()) {
            return RestoreResult(false, "Archive file not found: ${entry.archivePath}")
        }

        val config = configManager.getConfig()
        val target = config.targets.find { it.id == entry.targetId }
            ?: return RestoreResult(false, "Target '${entry.targetId}' no longer configured")

        val targetDir = baseDir.resolve(target.path).toFile()
        if (!targetDir.exists()) targetDir.mkdirs()

        return try {
            val process = ProcessBuilder(
                "tar", "-xzf", archiveFile.absolutePath,
                "-C", targetDir.absolutePath
            )
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                val error = "tar extract failed (exit $exitCode): $output".take(512)
                logger.warn("Restore failed for backup #{}: {}", backupId, error)
                return RestoreResult(false, error)
            }

            logger.info("Restored backup #{} to '{}'", backupId, targetDir.absolutePath)
            emitEvent("BACKUP_RESTORED", null, backupId, archivePath = entry.archivePath)
            RestoreResult(true, null)

        } catch (e: Exception) {
            val error = e.message?.take(512) ?: "Unknown error"
            logger.error("Restore exception for backup #{}", backupId, e)
            RestoreResult(false, error)
        }
    }

    suspend fun listBackups(targetId: String?, limit: Int = 50): List<BackupEntry> {
        return db.query {
            val query = if (targetId != null) {
                BackupEntries.selectAll().where { BackupEntries.targetId eq targetId }
            } else {
                BackupEntries.selectAll()
            }
            query
                .orderBy(BackupEntries.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { it.toEntry() }
        }
    }

    suspend fun deleteBackup(backupId: Long): Boolean {
        val entry = db.query {
            BackupEntries.selectAll()
                .where { BackupEntries.id eq backupId }
                .singleOrNull()
                ?.toEntry()
        } ?: return false

        val archiveFile = File(entry.archivePath)
        if (archiveFile.exists()) {
            archiveFile.delete()
        }

        db.query {
            BackupEntries.deleteWhere { BackupEntries.id eq backupId }
        }

        logger.info("Deleted backup #{} ({})", backupId, entry.archivePath)
        return true
    }

    suspend fun pruneBackups(targetId: String) {
        val config = configManager.getConfig()
        val target = config.targets.find { it.id == targetId }
        val retentionCount = target?.retentionCount ?: config.global.retentionCount

        val allEntries = db.query {
            BackupEntries.selectAll()
                .where { (BackupEntries.targetId eq targetId) and (BackupEntries.status eq "ok") }
                .orderBy(BackupEntries.createdAt, SortOrder.DESC)
                .map { it.toEntry() }
        }

        if (allEntries.size <= retentionCount) return

        val toDelete = allEntries.drop(retentionCount)
        for (entry in toDelete) {
            val archiveFile = File(entry.archivePath)
            if (archiveFile.exists()) archiveFile.delete()

            db.query {
                BackupEntries.update({ BackupEntries.id eq entry.id }) {
                    it[status] = "pruned"
                }
            }
        }

        if (toDelete.isNotEmpty()) {
            logger.info("Pruned {} old backup(s) for target '{}'", toDelete.size, targetId)
        }
    }

    suspend fun getStatus(): BackupStatus {
        val config = configManager.getConfig()
        val (count, totalSize) = db.query {
            val count = BackupEntries.selectAll()
                .where { BackupEntries.status eq "ok" }
                .count()
            val totalSize = BackupEntries.selectAll()
                .where { BackupEntries.status eq "ok" }
                .sumOf { it[BackupEntries.sizeBytes] }
            Pair(count, totalSize)
        }

        return BackupStatus(
            activeJobs = activeJobs.keys.toList(),
            totalBackupCount = count,
            totalSizeBytes = totalSize,
            backupDir = config.global.backupDir
        )
    }

    // ── DB helpers ────────────────────────────────────────

    private suspend fun recordEntry(
        target: BackupTarget,
        archivePath: String,
        sizeBytes: Long,
        status: String,
        errorMessage: String?,
        durationMs: Long
    ): Long {
        return db.query {
            BackupEntries.insertAndGetId {
                it[createdAt] = Instant.now().toString()
                it[BackupEntries.targetId] = target.id
                it[targetType] = target.type
                it[BackupEntries.archivePath] = archivePath
                it[BackupEntries.sizeBytes] = sizeBytes
                it[BackupEntries.status] = status
                it[BackupEntries.errorMessage] = errorMessage
                it[BackupEntries.durationMs] = durationMs
            }.value
        }
    }

    private fun ResultRow.toEntry() = BackupEntry(
        id = this[BackupEntries.id].value,
        createdAt = this[BackupEntries.createdAt],
        targetId = this[BackupEntries.targetId],
        targetType = this[BackupEntries.targetType],
        archivePath = this[BackupEntries.archivePath],
        sizeBytes = this[BackupEntries.sizeBytes],
        status = this[BackupEntries.status],
        errorMessage = this[BackupEntries.errorMessage],
        durationMs = this[BackupEntries.durationMs]
    )

    // ── Event helpers ─────────────────────────────────────

    private suspend fun emitEvent(
        type: String,
        target: BackupTarget?,
        backupId: Long?,
        archivePath: String? = null,
        sizeBytes: Long? = null,
        error: String? = null
    ) {
        val data = buildMap<String, String> {
            if (target != null) {
                put("targetId", target.id)
                put("targetType", target.type)
            }
            if (backupId != null) put("backupId", backupId.toString())
            if (archivePath != null) put("archivePath", archivePath)
            if (sizeBytes != null) put("sizeBytes", sizeBytes.toString())
            if (error != null) put("error", error)
        }

        eventBus.emit(
            NimbusEvent.ModuleEvent(
                moduleId = "backup",
                type = type,
                data = data
            )
        )
    }
}
