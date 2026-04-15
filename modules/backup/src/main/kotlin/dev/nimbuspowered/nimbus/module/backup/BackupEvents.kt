package dev.nimbuspowered.nimbus.module.backup

import dev.nimbuspowered.nimbus.event.NimbusEvent

object BackupEvents {
    const val MODULE_ID = "backup"

    fun started(id: Long, targetType: String, targetName: String, triggeredBy: String) =
        NimbusEvent.ModuleEvent(MODULE_ID, "BACKUP_STARTED", mapOf(
            "id" to id.toString(),
            "targetType" to targetType,
            "targetName" to targetName,
            "triggeredBy" to triggeredBy
        ))

    fun completed(id: Long, targetName: String, sizeBytes: Long, durationMs: Long, status: String) =
        NimbusEvent.ModuleEvent(MODULE_ID, "BACKUP_COMPLETED", mapOf(
            "id" to id.toString(),
            "targetName" to targetName,
            "sizeBytes" to sizeBytes.toString(),
            "durationMs" to durationMs.toString(),
            "status" to status
        ))

    fun failed(id: Long, targetName: String, reason: String) =
        NimbusEvent.ModuleEvent(MODULE_ID, "BACKUP_FAILED", mapOf(
            "id" to id.toString(),
            "targetName" to targetName,
            "reason" to reason
        ))

    fun restored(id: Long, targetName: String, targetPath: String, triggeredBy: String) =
        NimbusEvent.ModuleEvent(MODULE_ID, "BACKUP_RESTORED", mapOf(
            "id" to id.toString(),
            "targetName" to targetName,
            "targetPath" to targetPath,
            "triggeredBy" to triggeredBy
        ))

    fun pruned(count: Int, freedBytes: Long, scheduleClass: String) =
        NimbusEvent.ModuleEvent(MODULE_ID, "BACKUP_PRUNED", mapOf(
            "count" to count.toString(),
            "freedBytes" to freedBytes.toString(),
            "scheduleClass" to scheduleClass
        ))
}
