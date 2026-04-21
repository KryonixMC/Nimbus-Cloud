package dev.nimbuspowered.nimbus.module.backup.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.module.api.CommandOutput
import dev.nimbuspowered.nimbus.module.backup.BackupManager
import dev.nimbuspowered.nimbus.module.backup.BackupRetention
import dev.nimbuspowered.nimbus.module.backup.BackupScheduler
import dev.nimbuspowered.nimbus.module.backup.BackupTargetType
import dev.nimbuspowered.nimbus.module.backup.RetentionClass
import java.nio.file.Path

/**
 * Console surface for the backup module.
 *
 * `backup now` runs synchronously from the operator's perspective: the console
 * blocks until the archive is written. For long-running jobs this is fine —
 * the operator typically triggers them knowingly. Scheduled backups fire in
 * background coroutines via [BackupScheduler].
 */
class BackupCommand(
    private val manager: BackupManager,
    private val retention: BackupRetention,
    private val scheduler: BackupScheduler
) : Command {

    override val name = "backup"
    override val description = "Manage backups (snapshots of services, configs, database)"
    override val usage = "backup <now|list|status|restore|verify|prune|schedule>"

    override suspend fun execute(args: List<String>) {
        val out = object : CommandOutput {
            override fun header(text: String) = println(ConsoleFormatter.header(text))
            override fun info(text: String) = println(ConsoleFormatter.info(text))
            override fun success(text: String) = println(ConsoleFormatter.successLine(text))
            override fun error(text: String) = println(ConsoleFormatter.errorLine(text))
            override fun item(text: String) = println("  $text")
            override fun text(text: String) = println(text)
        }
        execute(args, out)
    }

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        if (args.isEmpty()) { output.error("Usage: $usage"); return true }
        when (args[0].lowercase()) {
            "now" -> runNow(args.drop(1), output)
            "list" -> listBackups(args.drop(1), output)
            "status" -> status(output)
            "restore" -> restore(args.drop(1), output)
            "verify" -> verify(args.drop(1), output)
            "prune" -> prune(args.drop(1), output)
            "schedule" -> schedule(args.drop(1), output)
            else -> output.error("Unknown subcommand '${args[0]}'")
        }
        return true
    }

    private suspend fun runNow(args: List<String>, output: CommandOutput) {
        val typeIdx = args.indexOf("--type")
        val targetIdx = args.indexOf("--target")
        val typeFilter = if (typeIdx >= 0 && typeIdx + 1 < args.size) args[typeIdx + 1] else null
        val targetFilter = if (targetIdx >= 0 && targetIdx + 1 < args.size) args[targetIdx + 1] else null

        val targetTypes: Set<BackupTargetType> = if (typeFilter != null) {
            val t = parseType(typeFilter)
            if (t == null) { output.error("Unknown --type '$typeFilter'"); return }
            setOf(t)
        } else emptySet()

        output.info("Running backup${if (targetFilter != null) " for '$targetFilter'" else ""}…")
        val records = manager.runBackup(
            targets = targetTypes,
            scheduleClass = RetentionClass.MANUAL,
            scheduleName = "",
            triggeredBy = "console",
            singleTarget = targetFilter
        )
        if (records.isEmpty()) { output.info("Nothing backed up (no matching targets)"); return }
        output.header("Backup complete (${records.size} archive(s))")
        for (r in records) {
            val sizeKb = r.sizeBytes / 1024
            val statusColored = colorStatus(r.status)
            output.item("#${r.id} $statusColored ${ConsoleFormatter.BOLD}${r.targetType}${ConsoleFormatter.RESET}/" +
                    "${r.targetName} ${ConsoleFormatter.DIM}(${sizeKb} KB)${ConsoleFormatter.RESET}")
            r.errorMessage?.let { output.item("    ${ConsoleFormatter.RED}error:${ConsoleFormatter.RESET} $it") }
        }
    }

    private suspend fun listBackups(args: List<String>, output: CommandOutput) {
        val limitIdx = args.indexOf("--limit")
        val limit = if (limitIdx >= 0 && limitIdx + 1 < args.size) args[limitIdx + 1].toIntOrNull() ?: 20 else 20
        val targetIdx = args.indexOf("--target")
        val target = if (targetIdx >= 0 && targetIdx + 1 < args.size) args[targetIdx + 1] else null
        val records = manager.list(target, null, limit, 0)
        if (records.isEmpty()) { output.info("No backups yet"); return }
        output.header("Backups (${records.size})")
        for (r in records) {
            val statusColored = colorStatus(r.status)
            val size = humanSize(r.sizeBytes)
            output.item("#${r.id} $statusColored ${ConsoleFormatter.BOLD}${r.targetType}${ConsoleFormatter.RESET}/" +
                    "${r.targetName} ${ConsoleFormatter.DIM}[${r.scheduleClass}]${ConsoleFormatter.RESET} " +
                    "$size ${ConsoleFormatter.DIM}${r.startedAt}${ConsoleFormatter.RESET}")
        }
    }

    private suspend fun status(output: CommandOutput) {
        output.header("Backup status")
        output.item("Active jobs: ${ConsoleFormatter.BOLD}${manager.activeJobs()}${ConsoleFormatter.RESET}")
        output.item("Local destination: ${ConsoleFormatter.DIM}${manager.localDestination}${ConsoleFormatter.RESET}")
        val schedules = scheduler.describeSchedules()
        output.item("Schedules: ${schedules.size}")
        for (s in schedules) {
            output.item("  • ${ConsoleFormatter.BOLD}${s.name}${ConsoleFormatter.RESET} " +
                    "(${s.cron}) next=${s.nextRunAt ?: "-"} class=${s.retentionClass}")
        }
        val recent = manager.lastBackupSummary()
        if (recent.isNotEmpty()) {
            output.item("Recent:")
            for (r in recent.take(5)) {
                output.item("  #${r.id} ${colorStatus(r.status)} ${r.targetType}/${r.targetName} " +
                        "${ConsoleFormatter.DIM}${r.startedAt}${ConsoleFormatter.RESET}")
            }
        }
    }

    private suspend fun restore(args: List<String>, output: CommandOutput) {
        if (args.isEmpty()) { output.error("Usage: backup restore <id> [--target <path>] [--dry-run] [--force]"); return }
        val id = args[0].toLongOrNull() ?: run { output.error("Invalid id"); return }
        val dryRun = args.contains("--dry-run")
        val force = args.contains("--force")
        val targetIdx = args.indexOf("--target")
        val overrideTarget = if (targetIdx >= 0 && targetIdx + 1 < args.size)
            Path.of(args[targetIdx + 1]) else null

        try {
            val result = manager.restore(id, overrideTarget, dryRun, force, "console")
            val prefix = if (dryRun) "${ConsoleFormatter.YELLOW}[DRY-RUN]${ConsoleFormatter.RESET} " else ""
            output.success("${prefix}Restored backup #$id → ${ConsoleFormatter.BOLD}${result.files.size}${ConsoleFormatter.RESET} file(s)")
            if (dryRun && result.files.size <= 30) {
                for (f in result.files) output.item(ConsoleFormatter.DIM + f + ConsoleFormatter.RESET)
            }
        } catch (e: Exception) {
            output.error("Restore failed: ${e.message}")
        }
    }

    private suspend fun verify(args: List<String>, output: CommandOutput) {
        if (args.isEmpty()) { output.error("Usage: backup verify <id>"); return }
        val id = args[0].toLongOrNull() ?: run { output.error("Invalid id"); return }
        val result = manager.verify(id)
        if (result.valid) {
            output.success("Backup #$id verified — manifest OK")
        } else {
            output.error("Backup #$id FAILED verification (${result.errors.size} problem(s))")
            for (err in result.errors.take(20)) output.item(ConsoleFormatter.RED + err + ConsoleFormatter.RESET)
        }
    }

    private suspend fun prune(args: List<String>, output: CommandOutput) {
        val dryRun = args.contains("--dry-run")
        val clsIdx = args.indexOf("--retention-class")
        val cls = if (clsIdx >= 0 && clsIdx + 1 < args.size) args[clsIdx + 1] else null
        val result = retention.prune(dryRun, cls)
        val prefix = if (dryRun) "${ConsoleFormatter.YELLOW}[DRY-RUN]${ConsoleFormatter.RESET} " else ""
        output.success("${prefix}Pruned ${result.deleted} backup(s), freed ${humanSize(result.freedBytes)}")
        for (err in result.errors) output.error(err)
    }

    private suspend fun schedule(args: List<String>, output: CommandOutput) {
        if (args.isEmpty()) { output.error("Usage: backup schedule <list|reload>"); return }
        when (args[0].lowercase()) {
            "list" -> {
                val list = scheduler.describeSchedules()
                if (list.isEmpty()) { output.info("No schedules configured"); return }
                output.header("Schedules (${list.size})")
                for (s in list) {
                    output.item("${ConsoleFormatter.BOLD}${s.name}${ConsoleFormatter.RESET} " +
                            "${ConsoleFormatter.DIM}[${s.cron}]${ConsoleFormatter.RESET} " +
                            "class=${s.retentionClass} targets=${s.targets.joinToString(",")} " +
                            "${ConsoleFormatter.DIM}next=${s.nextRunAt ?: "-"}${ConsoleFormatter.RESET}")
                }
            }
            "reload" -> {
                scheduler.reload()
                output.success("Backup config reloaded")
            }
            else -> output.error("Unknown schedule subcommand '${args[0]}'")
        }
    }

    private fun parseType(s: String): BackupTargetType? = when (s.lowercase()) {
        "services", "service" -> BackupTargetType.SERVICE
        "dedicated" -> BackupTargetType.DEDICATED
        "templates" -> BackupTargetType.TEMPLATES
        "config" -> BackupTargetType.CONFIG
        "database" -> BackupTargetType.DATABASE
        "state", "state_sync" -> BackupTargetType.STATE_SYNC
        else -> null
    }

    private fun colorStatus(status: String): String = when (status) {
        "SUCCESS" -> "${ConsoleFormatter.GREEN}$status${ConsoleFormatter.RESET}"
        "FAILED" -> "${ConsoleFormatter.RED}$status${ConsoleFormatter.RESET}"
        "PARTIAL" -> "${ConsoleFormatter.YELLOW}$status${ConsoleFormatter.RESET}"
        "RUNNING" -> "${ConsoleFormatter.CYAN}$status${ConsoleFormatter.RESET}"
        else -> status
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }
}
