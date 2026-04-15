package dev.nimbuspowered.nimbus.module.backup

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * GFS retention: per `(targetType, targetName, scheduleClass)` group, keep the
 * configured number of most-recent backups. Older ones are deleted (archive
 * file + DB row). Manual backups are immune when `keepManual = true`.
 */
class BackupRetention(
    private val database: Database,
    private val localDestination: Path,
    private val retention: RetentionConfig
) {

    private val logger = LoggerFactory.getLogger(BackupRetention::class.java)

    data class PruneResult(val deleted: Int, val freedBytes: Long, val errors: List<String>)

    /**
     * Prune old backups. If [onlyClass] is provided, only that retention class
     * (lowercase: hourly|daily|weekly|monthly) is considered.
     */
    suspend fun prune(dryRun: Boolean = false, onlyClass: String? = null): PruneResult {
        val errors = mutableListOf<String>()
        var deleted = 0
        var freed = 0L

        // Collect (id, path, size) lists to delete, grouped externally so we can
        // batch DB deletes without keeping the transaction open across FS I/O.
        data class ToDelete(val id: Long, val path: String, val size: Long)
        val pending = mutableListOf<ToDelete>()

        newSuspendedTransaction(Dispatchers.IO, database) {
            val rows = Backups.selectAll()
                .orderBy(Backups.startedAt, SortOrder.DESC)
                .map {
                    BackupRow(
                        id = it[Backups.id].value,
                        targetType = it[Backups.targetType],
                        targetName = it[Backups.targetName],
                        scheduleClass = it[Backups.scheduleClass],
                        status = it[Backups.status],
                        archivePath = it[Backups.archivePath],
                        sizeBytes = it[Backups.sizeBytes]
                    )
                }

            val grouped = rows.groupBy { Triple(it.targetType, it.targetName, it.scheduleClass) }
            for ((key, items) in grouped) {
                val (_, _, cls) = key
                val clsLower = cls.lowercase()
                if (onlyClass != null && clsLower != onlyClass.lowercase()) continue

                // Only successful/partial backups are candidates; FAILED rows are
                // noise and shouldn't count against the keep budget.
                val candidates = items.filter { it.status == "SUCCESS" || it.status == "PARTIAL" }

                if (clsLower == "manual" && retention.keepManual) continue

                val keep = when (clsLower) {
                    "hourly" -> retention.hourlyKeep
                    "daily" -> retention.dailyKeep
                    "weekly" -> retention.weeklyKeep
                    "monthly" -> retention.monthlyKeep
                    else -> Int.MAX_VALUE
                }
                if (candidates.size <= keep) continue
                val toDelete = candidates.drop(keep)
                for (item in toDelete) {
                    pending.add(ToDelete(item.id, item.archivePath, item.sizeBytes))
                }
            }
        }

        for (p in pending) {
            if (p.path.isNotEmpty()) {
                val file = localDestination.resolve(p.path)
                try {
                    if (!dryRun) Files.deleteIfExists(file)
                } catch (e: Exception) {
                    errors.add("Could not delete $file: ${e.message}")
                    continue
                }
            }
            if (!dryRun) {
                newSuspendedTransaction(Dispatchers.IO, database) {
                    Backups.deleteWhere { Backups.id eq p.id }
                }
            }
            deleted++
            freed += p.size
        }

        if (pending.isNotEmpty()) {
            logger.info("Retention prune: {} backup(s){} freed, {} byte(s) freed",
                deleted, if (dryRun) " [DRY-RUN]" else "", freed)
        }
        return PruneResult(deleted, freed, errors)
    }

    private data class BackupRow(
        val id: Long,
        val targetType: String,
        val targetName: String,
        val scheduleClass: String,
        val status: String,
        val archivePath: String,
        val sizeBytes: Long
    )
}
