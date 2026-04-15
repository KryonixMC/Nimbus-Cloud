package dev.nimbuspowered.nimbus.module.backup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Minute-tick scheduler: once per minute, checks every configured schedule's
 * cron against "now" and fires matching backups in a detached coroutine. The
 * [BackupManager] semaphore enforces max concurrency globally, so firing a
 * bunch at once is safe — they'll queue.
 *
 * Retention pruning runs hourly off the same loop.
 */
class BackupScheduler(
    private val manager: BackupManager,
    private val configManager: BackupConfigManager,
    private val retention: BackupRetention,
    private val database: Database,
    private val scope: CoroutineScope
) {

    private val logger = LoggerFactory.getLogger(BackupScheduler::class.java)
    private var tickJob: Job? = null
    private var pruneJob: Job? = null

    @Volatile
    private var lastTickMinute: Long = -1L

    private val zone: ZoneId = ZoneId.systemDefault()

    fun start() {
        tickJob?.cancel()
        tickJob = scope.launch(Dispatchers.Default) {
            // Wait a few seconds for the rest of the system to finish booting.
            delay(15_000)
            while (isActive) {
                try { tick() } catch (e: Exception) {
                    logger.error("Backup scheduler tick failed", e)
                }
                // Align the next check to roughly the next minute boundary.
                val msIntoMinute = System.currentTimeMillis() % 60_000
                delay((60_000 - msIntoMinute).coerceAtLeast(1_000))
            }
        }
        pruneJob?.cancel()
        pruneJob = scope.launch(Dispatchers.Default) {
            delay(60_000)
            while (isActive) {
                try {
                    val r = retention.prune()
                    if (r.deleted > 0) logger.info("Hourly prune: {} backups removed, {} bytes freed",
                        r.deleted, r.freedBytes)
                } catch (e: Exception) {
                    logger.error("Retention prune failed", e)
                }
                delay(3_600_000L) // 1h
            }
        }
    }

    fun stop() {
        tickJob?.cancel()
        pruneJob?.cancel()
    }

    /**
     * Force a reload of schedules from config — called by `backup schedule reload`.
     * The running tick job picks up the new config on the next tick.
     */
    fun reload() {
        configManager.reload()
    }

    private suspend fun tick() {
        val cfg = configManager.getConfig()
        if (!cfg.enabled) return

        val now = LocalDateTime.now(zone)
        val minuteEpoch = now.atZone(zone).toEpochSecond() / 60
        if (minuteEpoch == lastTickMinute) return
        lastTickMinute = minuteEpoch

        for (schedule in cfg.schedules) {
            val expr = try { CronExpression(schedule.cron) } catch (e: Exception) {
                logger.warn("Invalid cron '{}' for schedule '{}': {}", schedule.cron, schedule.name, e.message)
                continue
            }
            if (!expr.matches(now)) continue

            logger.info("Cron match for schedule '{}' ({}) — triggering backup", schedule.name, schedule.cron)
            scope.launch(Dispatchers.IO) {
                runScheduledBackup(schedule)
            }
        }
    }

    suspend fun runScheduledBackup(schedule: ScheduleEntry) {
        val retentionClass = parseRetention(schedule.retentionClass)
        val targets = resolveScheduleTargets(schedule.targets)
        try {
            val records = manager.runBackup(
                targets = targets,
                scheduleClass = retentionClass,
                scheduleName = schedule.name,
                triggeredBy = "scheduler"
            )
            updateScheduleLog(schedule.name, if (records.any { it.status == "FAILED" }) "FAILED" else "SUCCESS")
        } catch (e: Exception) {
            logger.error("Scheduled backup '{}' failed", schedule.name, e)
            updateScheduleLog(schedule.name, "FAILED")
        }
    }

    private fun parseRetention(s: String): RetentionClass = when (s.lowercase()) {
        "hourly" -> RetentionClass.HOURLY
        "daily" -> RetentionClass.DAILY
        "weekly" -> RetentionClass.WEEKLY
        "monthly" -> RetentionClass.MONTHLY
        else -> RetentionClass.MANUAL
    }

    private fun resolveScheduleTargets(targets: List<String>): Set<BackupTargetType> {
        if (targets.isEmpty() || targets.any { it.equals("all", ignoreCase = true) }) {
            return emptySet() // empty = all enabled in scope
        }
        val out = mutableSetOf<BackupTargetType>()
        for (t in targets) {
            when (t.lowercase()) {
                "services" -> out.add(BackupTargetType.SERVICE)
                "dedicated" -> out.add(BackupTargetType.DEDICATED)
                "templates" -> out.add(BackupTargetType.TEMPLATES)
                "config", "controller_config" -> out.add(BackupTargetType.CONFIG)
                "database" -> out.add(BackupTargetType.DATABASE)
                "state_sync", "state" -> out.add(BackupTargetType.STATE_SYNC)
            }
        }
        return out
    }

    private suspend fun updateScheduleLog(name: String, status: String) {
        newSuspendedTransaction(Dispatchers.IO, database) {
            val row = BackupScheduleLog.selectAll().where { BackupScheduleLog.scheduleName eq name }.firstOrNull()
            if (row == null) {
                BackupScheduleLog.insert {
                    it[scheduleName] = name
                    it[lastRunAt] = Instant.now().toString()
                    it[lastStatus] = status
                }
            } else {
                BackupScheduleLog.update({ BackupScheduleLog.scheduleName eq name }) {
                    it[lastRunAt] = Instant.now().toString()
                    it[lastStatus] = status
                }
            }
        }
    }

    suspend fun describeSchedules(): List<ScheduleStatusResponse> {
        val cfg = configManager.getConfig()
        val now = LocalDateTime.now(zone)

        // Fetch log rows for all configured schedules in one round-trip.
        val logByName: Map<String, Pair<String?, String?>> =
            newSuspendedTransaction(Dispatchers.IO, database) {
                BackupScheduleLog.selectAll().associate { row ->
                    row[BackupScheduleLog.scheduleName] to
                        (row[BackupScheduleLog.lastRunAt] to row[BackupScheduleLog.lastStatus])
                }
            }

        return cfg.schedules.map { s ->
            val cron = runCatching { CronExpression(s.cron) }.getOrNull()
            val next = cron?.nextAfter(now)?.atZone(zone)?.toInstant()?.toString()
            val (lastRun, lastStatus) = logByName[s.name] ?: (null to null)
            ScheduleStatusResponse(
                name = s.name,
                cron = s.cron,
                retentionClass = s.retentionClass,
                targets = s.targets,
                lastRunAt = lastRun,
                nextRunAt = next,
                lastStatus = lastStatus?.takeIf { it.isNotEmpty() }
            )
        }
    }
}
