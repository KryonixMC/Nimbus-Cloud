package dev.nimbuspowered.nimbus.module.backup

import dev.nimbuspowered.nimbus.config.NimbusConfig
import dev.nimbuspowered.nimbus.database.DatabaseManager
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.service.DedicatedServiceManager
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.fileSize

/**
 * Orchestrates backup runs. Resolves which targets to back up based on config
 * scope + schedule targets, quiesces running services via `save-off`/`save-all`,
 * streams each target to its own tar.zst archive, records the outcome in the
 * `backups` table, and fires events.
 *
 * Concurrency is bounded by [BackupModuleConfig.maxConcurrent]. Each logical
 * backup target is one row, one archive.
 */
class BackupManager(
    private val dbm: DatabaseManager,
    private val configManager: BackupConfigManager,
    private val registry: ServiceRegistry,
    private val dedicatedManager: DedicatedServiceManager?,
    private val nimbusConfig: NimbusConfig?,
    private val eventBus: EventBus,
    val baseDir: Path
) {

    private val logger = LoggerFactory.getLogger(BackupManager::class.java)
    private val archiver = BackupArchiver()
    private val database get() = dbm.database

    @Volatile
    var serviceManager: ServiceManager? = null

    private val config get() = configManager.getConfig()
    val localDestination: Path get() = resolvePath(config.localDestination)
    private val semaphore by lazy { Semaphore(maxOf(1, config.maxConcurrent)) }
    private val activeCount = AtomicInteger(0)

    fun activeJobs(): Int = activeCount.get()

    // ── Orchestration ──────────────────────────────────────────

    /**
     * Plan + execute a backup. [targets] is the set of scope types to run;
     * empty set means "all types enabled in config.scope".
     * [singleTarget] narrows SERVICE/DEDICATED to a specific name.
     *
     * Returns one record per produced archive. FAILED+SKIPPED archives are
     * still present as rows with that status so the operator can see them
     * via `backup list`.
     */
    suspend fun runBackup(
        targets: Set<BackupTargetType>,
        scheduleClass: RetentionClass,
        scheduleName: String,
        triggeredBy: String,
        singleTarget: String? = null
    ): List<BackupRecord> {
        val scope = config.scope
        val resolved = resolveTargets(targets, scope, singleTarget)
        if (resolved.isEmpty()) {
            logger.info("No backup targets matched — nothing to do")
            return emptyList()
        }
        val out = mutableListOf<BackupRecord>()
        for (target in resolved) {
            val record = runSingleTarget(target, scheduleClass, scheduleName, triggeredBy)
            if (record != null) out.add(record)
        }
        return out
    }

    private suspend fun runSingleTarget(
        target: PlannedTarget,
        scheduleClass: RetentionClass,
        scheduleName: String,
        triggeredBy: String
    ): BackupRecord? = semaphore.withPermit {
        activeCount.incrementAndGet()
        try {
            val startedAt = Instant.now().toString()
            val id = insertRunningRow(target, scheduleClass, scheduleName, startedAt, triggeredBy)
            eventBus.emit(BackupEvents.started(id, target.type.name, target.name, triggeredBy))

            val classLabel = scheduleClass.name.lowercase()
            val filename = buildArchiveName(target, classLabel)
            val archivePath = localDestination.resolve(filename)

            try {
                val (sourceDir, cleanupStaging) = prepareSource(target)
                    ?: return@withPermit finishWithStatus(
                        id, target, startedAt, BackupStatus.PARTIAL, 0, "",
                        "${target.skipReason ?: "source unavailable"}", scheduleClass, scheduleName, triggeredBy
                    )

                var status = BackupStatus.SUCCESS
                val mustQuiesce = target.type == BackupTargetType.SERVICE || target.type == BackupTargetType.DEDICATED
                val quiesced = if (mustQuiesce && config.quiesceServices) quiesce(target.name) else false
                try {
                    val result = archiver.archive(
                        sourceDir = sourceDir,
                        destArchive = archivePath,
                        excludeGlobs = config.excludes,
                        compressionLevel = config.compressionLevel,
                        workers = config.compressionWorkers
                    )
                    finishWithStatus(
                        id, target, startedAt, status, result.sizeBytes,
                        relativizeArchive(archivePath), null, scheduleClass, scheduleName, triggeredBy,
                        checksum = result.archiveSha256
                    )
                    eventBus.emit(BackupEvents.completed(id, target.name, result.sizeBytes, result.durationMs, status.name))
                    logger.info(
                        "Backup '{}/{}' → {} ({} files, {} skipped, {} ms, {} KB)",
                        target.type, target.name, archivePath.fileName,
                        result.fileCount, result.skippedCount, result.durationMs, result.sizeBytes / 1024
                    )
                    return@withPermit fetchRecord(id)
                } finally {
                    if (quiesced) unquiesce(target.name)
                    cleanupStaging?.invoke()
                }
            } catch (e: Exception) {
                logger.error("Backup failed for {}/{}", target.type, target.name, e)
                val rec = finishWithStatus(
                    id, target, startedAt, BackupStatus.FAILED, 0, "",
                    e.message ?: e::class.simpleName ?: "error",
                    scheduleClass, scheduleName, triggeredBy
                )
                eventBus.emit(BackupEvents.failed(id, target.name, e.message ?: "error"))
                // Best-effort cleanup of partial archive on failure
                runCatching { Files.deleteIfExists(archivePath) }
                return@withPermit rec
            }
        } finally {
            activeCount.decrementAndGet()
        }
    }

    /**
     * Quiesce a service's autosave via the running process. Returns true if we
     * successfully sent the commands (caller should call [unquiesce] at end).
     */
    private suspend fun quiesce(serviceName: String): Boolean {
        val sm = serviceManager ?: return false
        val svc = registry.get(serviceName) ?: return false
        if (svc.state != ServiceState.READY && svc.state != ServiceState.STARTING) return false
        if (svc.nodeId != "local") return false
        return try {
            sm.executeCommand(serviceName, "save-off")
            sm.executeCommand(serviceName, "save-all flush")
            delay((config.quiesceWaitSeconds * 1000L).coerceAtLeast(0L))
            true
        } catch (e: Exception) {
            logger.warn("Quiesce failed for {}: {}", serviceName, e.message)
            false
        }
    }

    private suspend fun unquiesce(serviceName: String) {
        val sm = serviceManager ?: return
        runCatching { sm.executeCommand(serviceName, "save-on") }
    }

    // ── Target resolution ──────────────────────────────────────

    private data class PlannedTarget(
        val type: BackupTargetType,
        val name: String,
        val source: PlannedSource,
        val skipReason: String? = null
    )

    private sealed class PlannedSource {
        data class Dir(val path: Path) : PlannedSource()
        object DatabaseDump : PlannedSource()
    }

    private fun resolveTargets(
        requested: Set<BackupTargetType>,
        scope: ScopeConfig,
        singleTarget: String?
    ): List<PlannedTarget> {
        fun want(t: BackupTargetType) = requested.isEmpty() || t in requested

        val out = mutableListOf<PlannedTarget>()

        if (want(BackupTargetType.SERVICE) && scope.services) {
            for (svc in registry.getNonDedicated()) {
                if (singleTarget != null && svc.name != singleTarget) continue
                if (svc.nodeId != "local") {
                    out.add(PlannedTarget(
                        BackupTargetType.SERVICE, svc.name, PlannedSource.Dir(svc.workingDirectory),
                        skipReason = "service lives on remote node '${svc.nodeId}' — remote streaming not implemented in this phase"
                    ))
                    continue
                }
                out.add(PlannedTarget(BackupTargetType.SERVICE, svc.name, PlannedSource.Dir(svc.workingDirectory)))
            }
        }
        if (want(BackupTargetType.DEDICATED) && scope.dedicated) {
            val dm = dedicatedManager
            if (dm != null) {
                for (cfg in dm.getAllConfigs()) {
                    val name = cfg.dedicated.name
                    if (singleTarget != null && name != singleTarget) continue
                    out.add(PlannedTarget(BackupTargetType.DEDICATED, name, PlannedSource.Dir(dm.getServiceDirectory(name))))
                }
            }
        }
        if (want(BackupTargetType.TEMPLATES) && scope.templates) {
            val dir = resolvePath(nimbusConfig?.paths?.templates ?: "templates")
            if (dir.exists()) out.add(PlannedTarget(BackupTargetType.TEMPLATES, "all", PlannedSource.Dir(dir)))
        }
        if (want(BackupTargetType.CONFIG) && scope.controllerConfig) {
            val dir = baseDir.resolve("config")
            if (dir.exists()) out.add(PlannedTarget(BackupTargetType.CONFIG, "all", PlannedSource.Dir(dir)))
        }
        if (want(BackupTargetType.STATE_SYNC) && scope.stateSync) {
            val dir = resolvePath(nimbusConfig?.paths?.services ?: "services").resolve("state")
            if (dir.exists()) out.add(PlannedTarget(BackupTargetType.STATE_SYNC, "all", PlannedSource.Dir(dir)))
        }
        if (want(BackupTargetType.DATABASE) && scope.database) {
            out.add(PlannedTarget(BackupTargetType.DATABASE, "all", PlannedSource.DatabaseDump))
        }
        return out
    }

    /**
     * Convert a planned target's source into a physical directory we can archive.
     * For DATABASE, this dumps the DB into a staging dir first; callers must
     * invoke the returned cleanup lambda (deletes the staging dir) once archiving
     * is complete.
     */
    private suspend fun prepareSource(target: PlannedTarget): Pair<Path, (() -> Unit)?>? {
        return when (val src = target.source) {
            is PlannedSource.Dir -> {
                if (!src.path.exists()) return null
                src.path to null
            }
            PlannedSource.DatabaseDump -> {
                val cfg = nimbusConfig?.database ?: return null
                val staging = baseDir.resolve("data").resolve("backups").resolve("_staging-db-${System.currentTimeMillis()}")
                val helper = DatabaseBackupHelper(database, cfg, baseDir)
                when (val r = helper.dump(staging)) {
                    is DatabaseBackupHelper.Result.Success -> staging to {
                        runCatching {
                            Files.walk(staging).use { s ->
                                s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                            }
                        }
                        Unit
                    }
                    is DatabaseBackupHelper.Result.Skipped -> {
                        logger.warn("Database backup skipped: {}", r.reason)
                        runCatching { Files.deleteIfExists(staging) }
                        null
                    }
                    is DatabaseBackupHelper.Result.Failed -> {
                        logger.error("Database dump failed: {}", r.reason)
                        runCatching { Files.deleteIfExists(staging) }
                        throw RuntimeException(r.reason)
                    }
                }
            }
        }
    }

    // ── Restore + verify + delete ──────────────────────────────

    data class RestoreResult(val record: BackupRecord, val filesExtracted: Int, val dryRun: Boolean, val files: List<String>)

    suspend fun restore(id: Long, overridePath: Path?, dryRun: Boolean, force: Boolean, triggeredBy: String): RestoreResult {
        val record = fetchRecord(id) ?: error("Backup #$id not found")
        val archive = localDestination.resolve(record.archivePath)
        if (!archive.exists()) error("Archive file missing on disk: $archive")

        val target = resolveDefaultRestoreTarget(record, overridePath)

        // Safety: refuse to overwrite a running service's workdir unless --force.
        val svc = if (record.targetType == "SERVICE" || record.targetType == "DEDICATED")
            registry.get(record.targetName) else null
        if (svc != null && !force) {
            val running = svc.state == ServiceState.READY || svc.state == ServiceState.STARTING
                || svc.state == ServiceState.DRAINING
            if (running) error("Service '${record.targetName}' is ${svc.state} — stop it first or pass --force")
        }

        val files = archiver.extract(archive, target, dryRun)
        if (!dryRun) eventBus.emit(BackupEvents.restored(id, record.targetName, target.toString(), triggeredBy))
        return RestoreResult(record, files.size, dryRun, files)
    }

    suspend fun verify(id: Long): BackupArchiver.VerifyResult {
        val record = fetchRecord(id) ?: error("Backup #$id not found")
        val archive = localDestination.resolve(record.archivePath)
        if (!archive.exists()) return BackupArchiver.VerifyResult(false, listOf("Archive missing on disk: $archive"))
        return archiver.verify(archive)
    }

    suspend fun delete(id: Long): Boolean {
        val record = fetchRecord(id) ?: return false
        val archive = localDestination.resolve(record.archivePath)
        runCatching { Files.deleteIfExists(archive) }
        newSuspendedTransaction(Dispatchers.IO, database) {
            Backups.deleteWhere { Backups.id eq id }
        }
        return true
    }

    suspend fun list(
        targetFilter: String?,
        statusFilter: String?,
        limit: Int,
        offset: Int
    ): List<BackupRecord> = newSuspendedTransaction(Dispatchers.IO, database) {
        val query = Backups.selectAll().where {
            var predicate: org.jetbrains.exposed.sql.Op<Boolean> =
                org.jetbrains.exposed.sql.Op.TRUE
            if (targetFilter != null) predicate = predicate and (Backups.targetName eq targetFilter)
            if (statusFilter != null) predicate = predicate and (Backups.status eq statusFilter.uppercase())
            predicate
        }.orderBy(Backups.startedAt, SortOrder.DESC)
        query.limit(limit, offset.toLong()).map { it.toRecord() }
    }

    suspend fun count(): Long = newSuspendedTransaction(Dispatchers.IO, database) {
        Backups.selectAll().count()
    }

    suspend fun fetchRecord(id: Long): BackupRecord? = newSuspendedTransaction(Dispatchers.IO, database) {
        Backups.selectAll().where { Backups.id eq id }.firstOrNull()?.toRecord()
    }

    // ── DB row helpers ─────────────────────────────────────────

    private suspend fun insertRunningRow(
        target: PlannedTarget,
        scheduleClass: RetentionClass,
        scheduleName: String,
        startedAt: String,
        triggeredBy: String
    ): Long = newSuspendedTransaction(Dispatchers.IO, database) {
        Backups.insertAndGetId {
            it[Backups.targetType] = target.type.name
            it[Backups.targetName] = target.name
            it[Backups.scheduleClass] = scheduleClass.name.lowercase()
            it[Backups.scheduleName] = scheduleName
            it[Backups.startedAt] = startedAt
            it[Backups.status] = BackupStatus.RUNNING.name
            it[Backups.triggeredBy] = triggeredBy
            it[Backups.nodeId] = "local"
        }.value
    }

    private suspend fun finishWithStatus(
        id: Long,
        target: PlannedTarget,
        startedAt: String,
        status: BackupStatus,
        sizeBytes: Long,
        archivePathRel: String,
        errorMessage: String?,
        scheduleClass: RetentionClass,
        scheduleName: String,
        triggeredBy: String,
        checksum: String = ""
    ): BackupRecord = newSuspendedTransaction(Dispatchers.IO, database) {
        Backups.update({ Backups.id eq id }) {
            it[Backups.status] = status.name
            it[Backups.completedAt] = Instant.now().toString()
            it[Backups.sizeBytes] = sizeBytes
            it[Backups.archivePath] = archivePathRel
            it[Backups.checksum] = checksum
            it[Backups.errorMessage] = errorMessage
        }
        fetchRecordInTx(id)!!
    }

    private fun fetchRecordInTx(id: Long): BackupRecord? =
        Backups.selectAll().where { Backups.id eq id }.firstOrNull()?.toRecord()

    private fun ResultRow.toRecord(): BackupRecord = BackupRecord(
        id = this[Backups.id].value,
        targetType = this[Backups.targetType],
        targetName = this[Backups.targetName],
        scheduleClass = this[Backups.scheduleClass],
        scheduleName = this[Backups.scheduleName],
        startedAt = this[Backups.startedAt],
        completedAt = this[Backups.completedAt],
        status = this[Backups.status],
        sizeBytes = this[Backups.sizeBytes],
        archivePath = this[Backups.archivePath],
        checksum = this[Backups.checksum],
        errorMessage = this[Backups.errorMessage],
        nodeId = this[Backups.nodeId],
        triggeredBy = this[Backups.triggeredBy]
    )

    // ── Path helpers ───────────────────────────────────────────

    private fun resolvePath(p: String): Path {
        val path = Path.of(p)
        return if (path.isAbsolute) path else baseDir.resolve(p)
    }

    private fun relativizeArchive(archive: Path): String {
        val rel = runCatching { localDestination.relativize(archive).toString().replace('\\', '/') }
            .getOrNull()
        return rel ?: archive.fileName.toString()
    }

    private fun buildArchiveName(target: PlannedTarget, classLabel: String): String {
        val ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())
        val name = target.name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val type = target.type.name.lowercase()
        return "$type-$name-$classLabel-$ts.tar.zst"
    }

    private fun resolveDefaultRestoreTarget(record: BackupRecord, override: Path?): Path {
        if (override != null) return override
        val type = runCatching { BackupTargetType.valueOf(record.targetType) }.getOrNull()
            ?: return baseDir.resolve("restore").resolve("${record.targetType}-${record.targetName}")

        return when (type) {
            BackupTargetType.SERVICE -> registry.get(record.targetName)?.workingDirectory
                ?: baseDir.resolve(nimbusConfig?.paths?.services ?: "services").resolve(record.targetName)
            BackupTargetType.DEDICATED -> dedicatedManager?.getServiceDirectory(record.targetName)
                ?: baseDir.resolve(nimbusConfig?.paths?.dedicated ?: "dedicated").resolve(record.targetName)
            BackupTargetType.TEMPLATES -> resolvePath(nimbusConfig?.paths?.templates ?: "templates")
            BackupTargetType.CONFIG -> baseDir.resolve("config")
            BackupTargetType.STATE_SYNC -> resolvePath(nimbusConfig?.paths?.services ?: "services").resolve("state")
            BackupTargetType.DATABASE -> baseDir.resolve("restore").resolve("database")
        }
    }

    /** For status reporting: last finished backup across all targets. */
    suspend fun lastBackupSummary(): List<BackupRecord> = newSuspendedTransaction(Dispatchers.IO, database) {
        Backups.selectAll()
            .orderBy(Backups.startedAt, SortOrder.DESC)
            .limit(10)
            .map { it.toRecord() }
    }
}

