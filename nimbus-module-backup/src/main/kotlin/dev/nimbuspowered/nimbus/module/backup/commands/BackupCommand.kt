package dev.nimbuspowered.nimbus.module.backup.commands

import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.module.CommandOutput
import dev.nimbuspowered.nimbus.module.CompletionMeta
import dev.nimbuspowered.nimbus.module.CompletionType
import dev.nimbuspowered.nimbus.module.ModuleCommand
import dev.nimbuspowered.nimbus.module.SubcommandMeta
import dev.nimbuspowered.nimbus.module.backup.BackupConfigManager
import dev.nimbuspowered.nimbus.module.backup.BackupManager

class BackupCommand(
    private val manager: BackupManager,
    private val configManager: BackupConfigManager
) : ModuleCommand {

    override val name = "backup"
    override val description = "Backup: create, restore, list, and prune backups"
    override val usage = "backup <create|restore|list|prune|status>"
    override val permission = "nimbus.cloud.backup"

    override val subcommandMeta: List<SubcommandMeta> get() = listOf(
        SubcommandMeta("create", "Create a backup for a target", "backup create <targetId>",
            listOf(CompletionMeta(0, CompletionType.FREE_TEXT))),
        SubcommandMeta("restore", "Restore a backup by ID", "backup restore <backupId>",
            listOf(CompletionMeta(0, CompletionType.FREE_TEXT))),
        SubcommandMeta("list", "List backups (optionally for a target)", "backup list [targetId] [--limit N]",
            listOf(CompletionMeta(0, CompletionType.FREE_TEXT))),
        SubcommandMeta("prune", "Prune old backups beyond retention", "backup prune [targetId]",
            listOf(CompletionMeta(0, CompletionType.FREE_TEXT))),
        SubcommandMeta("status", "Show active jobs and disk usage", "backup status")
    )

    // ── Local console execution ───────────────────────────

    override suspend fun execute(args: List<String>) {
        if (args.isEmpty()) {
            printUsage()
            return
        }

        when (args[0].lowercase()) {
            "create" -> executeCreate(args.drop(1))
            "restore" -> executeRestore(args.drop(1))
            "list" -> executeList(args.drop(1))
            "prune" -> executePrune(args.drop(1))
            "status" -> executeStatus()
            else -> printUsage()
        }
    }

    private suspend fun executeCreate(args: List<String>) {
        if (args.isEmpty()) {
            println(ConsoleFormatter.warnLine("Usage: backup create <targetId>"))
            return
        }
        val targetId = args[0]
        println(ConsoleFormatter.infoLine("Creating backup for '$targetId'..."))
        val result = manager.createBackup(targetId, triggeredBy = "console")
        if (result.success) {
            println(ConsoleFormatter.successLine(
                "Backup created: ${result.archivePath} (${result.sizeBytes} bytes, ${result.durationMs}ms)"
            ))
        } else {
            println(ConsoleFormatter.errorLine("Backup failed: ${result.error}"))
        }
    }

    private suspend fun executeRestore(args: List<String>) {
        if (args.isEmpty()) {
            println(ConsoleFormatter.warnLine("Usage: backup restore <backupId>"))
            return
        }
        val id = args[0].toLongOrNull()
        if (id == null) {
            println(ConsoleFormatter.errorLine("Invalid backup ID: '${args[0]}'"))
            return
        }
        println(ConsoleFormatter.infoLine("Restoring backup #$id..."))
        val result = manager.restoreBackup(id)
        if (result.success) {
            println(ConsoleFormatter.successLine("Backup #$id restored successfully"))
        } else {
            println(ConsoleFormatter.errorLine("Restore failed: ${result.error}"))
        }
    }

    private suspend fun executeList(args: List<String>) {
        val targetId = args.getOrNull(0)?.takeIf { !it.startsWith("--") }
        val limitIdx = args.indexOf("--limit")
        val limit = if (limitIdx >= 0) args.getOrNull(limitIdx + 1)?.toIntOrNull() ?: 50 else 50

        val entries = manager.listBackups(targetId, limit)
        val header = if (targetId != null) "Backups for '$targetId'" else "All Backups"
        println(ConsoleFormatter.header(header))

        if (entries.isEmpty()) {
            println(ConsoleFormatter.infoLine("No backups found."))
            return
        }

        for (entry in entries) {
            val sizeMb = "%.1f".format(entry.sizeBytes / 1_048_576.0)
            val status = when (entry.status) {
                "ok" -> ConsoleFormatter.GREEN + "ok" + ConsoleFormatter.RESET
                "failed" -> ConsoleFormatter.RED + "failed" + ConsoleFormatter.RESET
                else -> ConsoleFormatter.DIM + entry.status + ConsoleFormatter.RESET
            }
            println("  #${entry.id}  ${entry.createdAt.take(19)}  ${entry.targetId}  ${sizeMb}MB  $status")
        }
    }

    private suspend fun executePrune(args: List<String>) {
        val config = configManager.getConfig()
        val targets = if (args.isNotEmpty()) {
            listOf(args[0])
        } else {
            config.targets.map { it.id }
        }

        for (targetId in targets) {
            manager.pruneBackups(targetId)
        }
        println(ConsoleFormatter.successLine("Pruned backups for: ${targets.joinToString(", ")}"))
    }

    private suspend fun executeStatus() {
        val status = manager.getStatus()
        println(ConsoleFormatter.header("Backup Status"))
        println("  Backup dir:     ${status.backupDir}")
        println("  Total backups:  ${status.totalBackupCount}")
        val totalMb = "%.1f".format(status.totalSizeBytes / 1_048_576.0)
        println("  Total size:     ${totalMb} MB")
        if (status.activeJobs.isEmpty()) {
            println("  Active jobs:    ${ConsoleFormatter.DIM}none${ConsoleFormatter.RESET}")
        } else {
            println("  Active jobs:    ${status.activeJobs.joinToString(", ")}")
        }

        val config = configManager.getConfig()
        if (config.targets.isNotEmpty()) {
            println()
            println("  ${ConsoleFormatter.BOLD}Configured targets:${ConsoleFormatter.RESET}")
            for (target in config.targets) {
                val sched = target.schedule ?: "manual only"
                println("    ${target.id}  ${ConsoleFormatter.DIM}(${target.type}, schedule: $sched, retain: ${target.retentionCount})${ConsoleFormatter.RESET}")
            }
        }
    }

    private fun printUsage() {
        println(ConsoleFormatter.warnLine("Usage: $usage"))
        println("  create <targetId>           Create a backup immediately")
        println("  restore <backupId>          Restore a backup by numeric ID")
        println("  list [targetId] [--limit N] List backups")
        println("  prune [targetId]            Prune old backups beyond retention limit")
        println("  status                      Active jobs and disk usage")
    }

    // ── Remote execution (CommandOutput) ─────────────────

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        if (args.isEmpty()) {
            remoteHelp(output)
            return true
        }

        when (args[0].lowercase()) {
            "create" -> remoteCreate(args.drop(1), output)
            "restore" -> remoteRestore(args.drop(1), output)
            "list" -> remoteList(args.drop(1), output)
            "prune" -> remotePrune(args.drop(1), output)
            "status" -> remoteStatus(output)
            else -> remoteHelp(output)
        }
        return true
    }

    private suspend fun remoteCreate(args: List<String>, out: CommandOutput) {
        if (args.isEmpty()) {
            out.error("Usage: backup create <targetId>")
            return
        }
        val result = manager.createBackup(args[0], triggeredBy = "api")
        if (result.success) {
            out.success("Backup created: ${result.archivePath} (${result.sizeBytes} bytes, ${result.durationMs}ms)")
        } else {
            out.error("Backup failed: ${result.error}")
        }
    }

    private suspend fun remoteRestore(args: List<String>, out: CommandOutput) {
        if (args.isEmpty()) {
            out.error("Usage: backup restore <backupId>")
            return
        }
        val id = args[0].toLongOrNull()
        if (id == null) {
            out.error("Invalid backup ID: '${args[0]}'")
            return
        }
        val result = manager.restoreBackup(id)
        if (result.success) {
            out.success("Backup #$id restored successfully")
        } else {
            out.error("Restore failed: ${result.error}")
        }
    }

    private suspend fun remoteList(args: List<String>, out: CommandOutput) {
        val targetId = args.getOrNull(0)?.takeIf { !it.startsWith("--") }
        val limitIdx = args.indexOf("--limit")
        val limit = if (limitIdx >= 0) args.getOrNull(limitIdx + 1)?.toIntOrNull() ?: 50 else 50

        val entries = manager.listBackups(targetId, limit)
        val header = if (targetId != null) "Backups for '$targetId'" else "All Backups"
        out.header(header)

        if (entries.isEmpty()) {
            out.info("No backups found.")
            return
        }

        for (entry in entries) {
            val sizeMb = "%.1f".format(entry.sizeBytes / 1_048_576.0)
            out.item("#${entry.id}  ${entry.createdAt.take(19)}  ${entry.targetId}  ${sizeMb}MB  ${entry.status}")
        }
    }

    private suspend fun remotePrune(args: List<String>, out: CommandOutput) {
        val config = configManager.getConfig()
        val targets = if (args.isNotEmpty()) listOf(args[0]) else config.targets.map { it.id }

        for (targetId in targets) {
            manager.pruneBackups(targetId)
        }
        out.success("Pruned backups for: ${targets.joinToString(", ")}")
    }

    private suspend fun remoteStatus(out: CommandOutput) {
        val status = manager.getStatus()
        out.header("Backup Status")
        out.item("Backup dir: ${status.backupDir}")
        out.item("Total backups: ${status.totalBackupCount}")
        out.item("Total size: ${"%.1f".format(status.totalSizeBytes / 1_048_576.0)} MB")
        if (status.activeJobs.isEmpty()) {
            out.info("No active jobs")
        } else {
            out.item("Active jobs: ${status.activeJobs.joinToString(", ")}")
        }
    }

    private fun remoteHelp(out: CommandOutput) {
        out.header("Backup Commands")
        out.item("create <targetId> — Create backup immediately")
        out.item("restore <backupId> — Restore a backup")
        out.item("list [targetId] [--limit N] — List backups")
        out.item("prune [targetId] — Prune old backups")
        out.item("status — Active jobs and disk usage")
    }
}
