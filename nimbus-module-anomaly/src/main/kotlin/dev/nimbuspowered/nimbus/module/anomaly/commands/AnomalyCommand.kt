package dev.nimbuspowered.nimbus.module.anomaly.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.module.CommandOutput
import dev.nimbuspowered.nimbus.module.SubcommandMeta
import dev.nimbuspowered.nimbus.module.anomaly.AnomalyEntry
import dev.nimbuspowered.nimbus.module.anomaly.AnomalyManager
import kotlin.math.abs

class AnomalyCommand(
    private val manager: AnomalyManager
) : Command {

    override val name = "anomaly"
    override val description = "Anomaly detection: active alerts, history"
    override val usage = "anomaly <status|history> [service] [--limit N]"
    override val permission = "nimbus.cloud.anomaly"

    override val subcommandMeta: List<SubcommandMeta> get() = listOf(
        SubcommandMeta("status", "Show currently active anomalies", "anomaly status"),
        SubcommandMeta("history", "Show anomaly history", "anomaly history [service] [--limit N]")
    )

    // ── Console execution ───────────────────────────────────

    override suspend fun execute(args: List<String>) {
        if (args.isEmpty()) {
            printUsage()
            return
        }
        when (args[0].lowercase()) {
            "status" -> executeStatus()
            "history" -> executeHistory(args.drop(1))
            else -> printUsage()
        }
    }

    private suspend fun executeStatus() {
        val anomalies = manager.getCurrentAnomalies()
        val stats = manager.getStats()

        println(ConsoleFormatter.header("Anomaly Detection — Active"))
        println(
            "  ${ConsoleFormatter.DIM}Total detected:${ConsoleFormatter.RESET} ${stats.totalDetected}  " +
                "${ConsoleFormatter.RED}critical: ${stats.criticalCount}${ConsoleFormatter.RESET}  " +
                "${ConsoleFormatter.YELLOW}warning: ${stats.warningCount}${ConsoleFormatter.RESET}"
        )
        if (stats.mostAffectedGroup != null) {
            println("  ${ConsoleFormatter.DIM}Most affected group:${ConsoleFormatter.RESET} ${stats.mostAffectedGroup}")
        }
        println()

        if (anomalies.isEmpty()) {
            println(ConsoleFormatter.infoLine("No active anomalies."))
            return
        }

        for (entry in anomalies) {
            println(formatEntry(entry))
        }
    }

    private suspend fun executeHistory(args: List<String>) {
        val (serviceName, limit) = parseHistoryArgs(args)
        val history = manager.getHistory(limit, serviceName)

        val title = if (serviceName != null) "Anomaly History: $serviceName" else "Anomaly History"
        println(ConsoleFormatter.header(title))

        if (history.isEmpty()) {
            println(ConsoleFormatter.infoLine("No anomaly history found."))
            return
        }

        for (entry in history) {
            val resolvedTag = if (entry.resolved) {
                " ${ConsoleFormatter.DIM}[resolved]${ConsoleFormatter.RESET}"
            } else {
                " ${ConsoleFormatter.YELLOW}[active]${ConsoleFormatter.RESET}"
            }
            println(formatEntry(entry) + resolvedTag)
        }
    }

    private fun printUsage() {
        println(ConsoleFormatter.warnLine("Usage: $usage"))
        println("  status                 Active anomalies and stats")
        println("  history [svc] [--limit N]  Anomaly history (default limit 50)")
    }

    // ── Remote execution (Bridge/API) ───────────────────────

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        if (args.isEmpty()) {
            remoteHelp(output)
            return true
        }
        when (args[0].lowercase()) {
            "status" -> remoteStatus(output)
            "history" -> remoteHistory(args.drop(1), output)
            else -> remoteHelp(output)
        }
        return true
    }

    private suspend fun remoteStatus(out: CommandOutput) {
        val anomalies = manager.getCurrentAnomalies()
        val stats = manager.getStats()
        out.header("Anomaly Detection — Active")
        out.info("Total detected: ${stats.totalDetected}  critical: ${stats.criticalCount}  warning: ${stats.warningCount}")
        if (stats.mostAffectedGroup != null) out.info("Most affected group: ${stats.mostAffectedGroup}")
        if (anomalies.isEmpty()) {
            out.info("No active anomalies.")
            return
        }
        for (entry in anomalies) {
            out.item(formatEntryPlain(entry))
        }
    }

    private suspend fun remoteHistory(args: List<String>, out: CommandOutput) {
        val (serviceName, limit) = parseHistoryArgs(args)
        val history = manager.getHistory(limit, serviceName)
        out.header(if (serviceName != null) "Anomaly History: $serviceName" else "Anomaly History")
        if (history.isEmpty()) {
            out.info("No anomaly history found.")
            return
        }
        for (entry in history) {
            val tag = if (entry.resolved) "[resolved]" else "[active]"
            out.item("${formatEntryPlain(entry)} $tag")
        }
    }

    private fun remoteHelp(out: CommandOutput) {
        out.header("Anomaly Commands")
        out.item("status — Active anomalies and stats")
        out.item("history [service] [--limit N] — Anomaly history")
    }

    // ── Formatting helpers ──────────────────────────────────

    private fun formatEntry(entry: AnomalyEntry): String {
        val severityColor = if (entry.severity == "critical") ConsoleFormatter.RED else ConsoleFormatter.YELLOW
        val icon = if (entry.severity == "critical") "✖" else "!"
        val zDisplay = "%.2f".format(abs(entry.zscore))
        val group = if (entry.groupName != null) " ${ConsoleFormatter.DIM}(${entry.groupName})${ConsoleFormatter.RESET}" else ""
        return "  $severityColor$icon${ConsoleFormatter.RESET} " +
            "${ConsoleFormatter.BOLD}${entry.serviceName}${ConsoleFormatter.RESET}$group " +
            "${ConsoleFormatter.DIM}${entry.metric}${ConsoleFormatter.RESET} " +
            "val=${ConsoleFormatter.BOLD}${"%.1f".format(entry.value)}${ConsoleFormatter.RESET} " +
            "baseline=${"%.1f".format(entry.baseline)} z=$zDisplay " +
            "${ConsoleFormatter.DIM}[${entry.anomalyType}]${ConsoleFormatter.RESET}"
    }

    private fun formatEntryPlain(entry: AnomalyEntry): String {
        val zDisplay = "%.2f".format(abs(entry.zscore))
        val group = if (entry.groupName != null) " (${entry.groupName})" else ""
        return "${entry.severity.uppercase()} ${entry.serviceName}$group " +
            "${entry.metric} val=${"%.1f".format(entry.value)} " +
            "baseline=${"%.1f".format(entry.baseline)} z=$zDisplay [${entry.anomalyType}]"
    }

    private fun parseHistoryArgs(args: List<String>): Pair<String?, Int> {
        var serviceName: String? = null
        var limit = 50

        var i = 0
        while (i < args.size) {
            when {
                args[i] == "--limit" && i + 1 < args.size -> {
                    limit = args[i + 1].toIntOrNull() ?: 50
                    i += 2
                }
                !args[i].startsWith("--") -> {
                    serviceName = args[i]
                    i++
                }
                else -> i++
            }
        }
        return serviceName to limit.coerceIn(1, 500)
    }
}
