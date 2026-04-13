package dev.nimbuspowered.nimbus.module.backup

import org.jetbrains.exposed.dao.id.LongIdTable

/** Log of all backup operations performed by the backup module. */
object BackupEntries : LongIdTable("backup_entries") {
    val createdAt = varchar("created_at", 40)         // ISO-8601
    val targetId = varchar("target_id", 128)
    val targetType = varchar("target_type", 32)       // "service"|"group"|"directory"
    val archivePath = varchar("archive_path", 512)
    val sizeBytes = long("size_bytes")
    val status = varchar("status", 16)                // "ok"|"failed"|"pruned"
    val errorMessage = varchar("error_message", 512).nullable()
    val durationMs = long("duration_ms")

    init {
        index(false, createdAt)
        index(false, targetId, createdAt)
    }
}
