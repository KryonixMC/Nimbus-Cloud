package dev.nimbuspowered.nimbus.module.backup

import org.jetbrains.exposed.dao.id.LongIdTable

/**
 * One row per backup archive on disk. `status` drives the lifecycle:
 *   RUNNING → SUCCESS | FAILED | PARTIAL (PARTIAL = some targets skipped/failed)
 */
object Backups : LongIdTable("backups") {
    val targetType    = varchar("target_type", 32)        // SERVICE, DEDICATED, TEMPLATES, CONFIG, DATABASE, STATE_SYNC
    val targetName    = varchar("target_name", 128)       // group/service/dedicated name; "all" for aggregate targets
    val scheduleClass = varchar("schedule_class", 32)     // hourly, daily, weekly, monthly, manual
    val scheduleName  = varchar("schedule_name", 64).default("")
    val startedAt     = varchar("started_at", 30)
    val completedAt   = varchar("completed_at", 30).nullable()
    val status        = varchar("status", 16)             // RUNNING, SUCCESS, FAILED, PARTIAL
    val sizeBytes     = long("size_bytes").default(0L)
    val archivePath   = varchar("archive_path", 512).default("")  // relative to local_destination
    val checksum      = varchar("checksum", 64).default("")       // SHA-256 of archive file
    val errorMessage  = text("error_message").nullable()
    val nodeId        = varchar("node_id", 64).default("local")
    val triggeredBy   = varchar("triggered_by", 64).default("scheduler")

    init {
        index(false, targetType, targetName)
        index(false, scheduleClass)
        index(false, startedAt)
    }
}

/** One row per schedule, tracking last/next run for observability. */
object BackupScheduleLog : LongIdTable("backup_schedule_log") {
    val scheduleName = varchar("schedule_name", 64)
    val lastRunAt    = varchar("last_run_at", 30)
    val nextRunAt    = varchar("next_run_at", 30).nullable()
    val lastStatus   = varchar("last_status", 16).default("")

    init { uniqueIndex(scheduleName) }
}
