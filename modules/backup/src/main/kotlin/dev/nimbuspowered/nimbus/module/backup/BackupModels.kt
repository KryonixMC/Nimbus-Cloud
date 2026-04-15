package dev.nimbuspowered.nimbus.module.backup

import kotlinx.serialization.Serializable

enum class BackupTargetType { SERVICE, DEDICATED, TEMPLATES, CONFIG, DATABASE, STATE_SYNC }

enum class BackupStatus { RUNNING, SUCCESS, FAILED, PARTIAL }

enum class RetentionClass { HOURLY, DAILY, WEEKLY, MONTHLY, MANUAL }

data class BackupRecord(
    val id: Long,
    val targetType: String,
    val targetName: String,
    val scheduleClass: String,
    val scheduleName: String,
    val startedAt: String,
    val completedAt: String?,
    val status: String,
    val sizeBytes: Long,
    val archivePath: String,
    val checksum: String,
    val errorMessage: String?,
    val nodeId: String,
    val triggeredBy: String
)

@Serializable
data class BackupResponse(
    val id: Long,
    val targetType: String,
    val targetName: String,
    val scheduleClass: String,
    val scheduleName: String,
    val startedAt: String,
    val completedAt: String?,
    val status: String,
    val sizeBytes: Long,
    val archivePath: String,
    val checksum: String,
    val errorMessage: String?,
    val nodeId: String,
    val triggeredBy: String
)

fun BackupRecord.toResponse() = BackupResponse(
    id, targetType, targetName, scheduleClass, scheduleName,
    startedAt, completedAt, status, sizeBytes, archivePath, checksum,
    errorMessage, nodeId, triggeredBy
)

@Serializable
data class BackupListResponse(val backups: List<BackupResponse>, val total: Int)

@Serializable
data class TriggerBackupRequest(
    val targets: List<String> = emptyList(),   // empty = all enabled scopes
    val scheduleClass: String = "manual",
    val target: String? = null                  // optional single target name filter
)

@Serializable
data class RestoreBackupRequest(
    val targetPath: String? = null,
    val dryRun: Boolean = false,
    val force: Boolean = false
)

@Serializable
data class PruneRequest(val dryRun: Boolean = false, val retentionClass: String? = null)

@Serializable
data class PruneResponse(val deleted: Int, val freedBytes: Long, val errors: List<String>)

@Serializable
data class ScheduleStatusResponse(
    val name: String,
    val cron: String,
    val retentionClass: String,
    val targets: List<String>,
    val lastRunAt: String?,
    val nextRunAt: String?,
    val lastStatus: String?
)

@Serializable
data class VerifyResponse(val valid: Boolean, val errors: List<String>)
