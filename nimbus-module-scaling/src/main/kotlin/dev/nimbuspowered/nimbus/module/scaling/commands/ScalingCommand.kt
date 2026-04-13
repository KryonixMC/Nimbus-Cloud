package dev.nimbuspowered.nimbus.module.scaling.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.module.CommandOutput
import dev.nimbuspowered.nimbus.module.CompletionMeta
import dev.nimbuspowered.nimbus.module.CompletionType
import dev.nimbuspowered.nimbus.module.SubcommandMeta
import dev.nimbuspowered.nimbus.module.scaling.SmartScalingConfigManager
import dev.nimbuspowered.nimbus.module.scaling.SmartScalingManager

class ScalingCommand(
    private val manager: SmartScalingManager,
    private val configManager: SmartScalingConfigManager
) : Command {

    override val name = "scaling"
    override val description = "Smart scaling: schedules, predictions, history"
    override val usage = "scaling <status|schedule|history|predict|reload>"
    override val permission = "nimbus.cloud.scaling"

    override val subcommandMeta: List<SubcommandMeta> get() = listOf(
        SubcommandMeta("status", "Show active schedules and predictions", "scaling status"),
        SubcommandMeta("schedule list", "List all schedule rules", "scaling schedule list"),
        SubcommandMeta("schedule info", "Show rules for a group", "scaling schedule info <group>",
            listOf(CompletionMeta(0, CompletionType.GROUP))),
        SubcommandMeta("history", "Show player count history", "scaling history <group> [hours]",
            listOf(CompletionMeta(0, CompletionType.GROUP))),
        SubcommandMeta("predict", "Show predictions for next hours", "scaling predict <group>",
            listOf(CompletionMeta(0, CompletionType.GROUP))),
        SubcommandMeta("reload", "Reload scaling configs", "scaling reload")
    )

    override suspend fun execute(args: List<String>) {
        if (args.isEmpty()) {
            printUsage()
            return
        }

        when (args[0].lowercase()) {
            "status" -> executeStatus()
            "schedule" -> executeSchedule(args.drop(1))
            "history" -> executeHistory(args.drop(1))
            "predict" -> executePredict(args.drop(1))
            "reload" -> executeReload()
            else -> printUsage()
        }
    }

    private suspend fun executeStatus() {
        println(ConsoleFormatter.header("Smart Scaling Status"))

        val configs = configManager.getAllConfigs()
        if (configs.isEmpty()) {
            println(ConsoleFormatter.infoLine("No scaling configs loaded."))
            return
        }

        for ((groupName, config) in configs) {
            val status = if (config.schedule.enabled) "enabled" else "disabled"
            val activeRule = manager.getActiveRule(groupName)

            val line = buildString {
                append("  ${ConsoleFormatter.BOLD}$groupName${ConsoleFormatter.RESET}")
                append(" ${ConsoleFormatter.DIM}($status, ${config.schedule.rules.size} rules)${ConsoleFormatter.RESET}")
                if (activeRule != null) {
                    val (rule, isWarmup) = activeRule
                    val tag = if (isWarmup) "warming up" else "active"
                    append(" ${ConsoleFormatter.GREEN}[$tag: \"${rule.name}\" min=${rule.minInstances}]${ConsoleFormatter.RESET}")
                }
            }
            println(line)
        }

        // Show recent decisions
        val decisions = manager.getRecentDecisions(5)
        if (decisions.isNotEmpty()) {
            println()
            println(ConsoleFormatter.infoLine("Recent decisions:"))
            for (d in decisions) {
                println("  ${ConsoleFormatter.DIM}${d.timestamp.take(19)}${ConsoleFormatter.RESET} " +
                        "${d.groupName} ${d.action} — ${d.reason}")
            }
        }
    }

    private fun executeSchedule(args: List<String>) {
        if (args.isEmpty() || args[0].lowercase() == "list") {
            println(ConsoleFormatter.header("Schedule Rules"))
            val configs = configManager.getAllConfigs()
            if (configs.isEmpty()) {
                println(ConsoleFormatter.infoLine("No scaling configs loaded."))
                return
            }
            for ((groupName, config) in configs) {
                if (config.schedule.rules.isEmpty()) continue
                println("  ${ConsoleFormatter.BOLD}$groupName${ConsoleFormatter.RESET}" +
                        " ${ConsoleFormatter.DIM}(${config.schedule.timezone})${ConsoleFormatter.RESET}")
                for (rule in config.schedule.rules) {
                    val days = rule.days.joinToString(", ") { it.name.take(3) }
                    val max = if (rule.maxInstances != null) ", max=${rule.maxInstances}" else ""
                    println("    \"${rule.name}\": $days ${rule.from}-${rule.to} min=${rule.minInstances}$max")
                }
            }
            return
        }

        if (args[0].lowercase() == "info" && args.size >= 2) {
            val groupName = args[1]
            val config = configManager.getConfig(groupName)
            if (config == null) {
                println(ConsoleFormatter.errorLine("No scaling config for group '$groupName'"))
                return
            }

            println(ConsoleFormatter.header("Schedule: $groupName"))
            println("  Enabled: ${config.schedule.enabled}")
            println("  Timezone: ${config.schedule.timezone}")
            println("  Warmup: ${config.schedule.warmup.enabled} (${config.schedule.warmup.leadTimeMinutes}min lead)")
            println("  Rules:")
            if (config.schedule.rules.isEmpty()) {
                println("    (none)")
            }
            for (rule in config.schedule.rules) {
                val days = rule.days.joinToString(", ") { it.name.take(3) }
                val max = if (rule.maxInstances != null) ", max=${rule.maxInstances}" else ""
                println("    \"${rule.name}\": $days ${rule.from}-${rule.to} min=${rule.minInstances}$max")
            }
            val activeRule = manager.getActiveRule(groupName)
            if (activeRule != null) {
                val (rule, isWarmup) = activeRule
                val tag = if (isWarmup) "warming up" else "active"
                println("  ${ConsoleFormatter.GREEN}Currently: $tag \"${rule.name}\"${ConsoleFormatter.RESET}")
            }
            return
        }

        println(ConsoleFormatter.warnLine("Usage: scaling schedule <list|info <group>>"))
    }

    private suspend fun executeHistory(args: List<String>) {
        if (args.isEmpty()) {
            println(ConsoleFormatter.warnLine("Usage: scaling history <group> [hours]"))
            return
        }

        val groupName = args[0]
        val hours = args.getOrNull(1)?.toIntOrNull() ?: 24
        val history = manager.getHistory(groupName, hours)

        println(ConsoleFormatter.header("Player History: $groupName (last ${hours}h)"))
        if (history.isEmpty()) {
            println(ConsoleFormatter.infoLine("No data available yet. Snapshots are collected every 60s."))
            return
        }

        // Show hourly summaries
        val byHour = history.groupBy { it.timestamp.take(13) } // Group by YYYY-MM-DDTHH
        for ((hourKey, snapshots) in byHour.toSortedMap().toList().takeLast(24)) {
            val avgPlayers = snapshots.map { it.playerCount }.average().toInt()
            val maxPlayers = snapshots.maxOf { it.playerCount }
            val avgServices = snapshots.map { it.serviceCount }.average().toInt()
            val hour = hourKey.takeLast(2)
            println("  ${hour}:00  avg=${avgPlayers} max=${maxPlayers} services=${avgServices}")
        }
    }

    private suspend fun executePredict(args: List<String>) {
        if (args.isEmpty()) {
            println(ConsoleFormatter.warnLine("Usage: scaling predict <group>"))
            return
        }

        val groupName = args[0]
        val predictions = manager.getPredictions(groupName, 6)

        println(ConsoleFormatter.header("Predictions: $groupName (next 6h)"))
        for (p in predictions) {
            println("  ${String.format("%02d", p.hour)}:00 ${p.dayOfWeek.name.take(3)}  " +
                    "~${p.predictedPlayers} players ${ConsoleFormatter.DIM}(${p.confidence}, ${p.source}, ${p.dataPoints} samples)${ConsoleFormatter.RESET}")
        }
    }

    private fun executeReload() {
        configManager.reload()
        println(ConsoleFormatter.successLine("Smart scaling configs reloaded (${configManager.getAllConfigs().size} groups)"))
    }

    private fun printUsage() {
        println(ConsoleFormatter.warnLine("Usage: $usage"))
        println("  status              Active schedules and recent decisions")
        println("  schedule list       All schedule rules")
        println("  schedule info <g>   Rules for a group")
        println("  history <g> [h]     Player history (default 24h)")
        println("  predict <g>         Predictions for next 6 hours")
        println("  reload              Reload configs")
    }

    // ── Remote execution (Bridge/API) ───────────────────

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        if (args.isEmpty()) {
            remoteHelp(output)
            return true
        }

        when (args[0].lowercase()) {
            "status" -> remoteStatus(output)
            "schedule" -> remoteSchedule(args.drop(1), output)
            "history" -> remoteHistory(args.drop(1), output)
            "predict" -> remotePredict(args.drop(1), output)
            "reload" -> {
                configManager.reload()
                output.success("Smart scaling configs reloaded (${configManager.getAllConfigs().size} groups)")
            }
            else -> remoteHelp(output)
        }
        return true
    }

    private suspend fun remoteStatus(out: CommandOutput) {
        out.header("Smart Scaling Status")
        val configs = configManager.getAllConfigs()
        if (configs.isEmpty()) {
            out.info("No scaling configs loaded.")
            return
        }
        for ((groupName, config) in configs) {
            val status = if (config.schedule.enabled) "enabled" else "disabled"
            val activeRule = manager.getActiveRule(groupName)
            val activeText = if (activeRule != null) {
                val (rule, isWarmup) = activeRule
                val tag = if (isWarmup) "warming" else "active"
                " [$tag: \"${rule.name}\" min=${rule.minInstances}]"
            } else ""
            out.item("$groupName ($status, ${config.schedule.rules.size} rules)$activeText")
        }
    }

    private fun remoteSchedule(args: List<String>, out: CommandOutput) {
        if (args.isEmpty() || args[0].lowercase() == "list") {
            out.header("Schedule Rules")
            for ((groupName, config) in configManager.getAllConfigs()) {
                for (rule in config.schedule.rules) {
                    val days = rule.days.joinToString(",") { it.name.take(3) }
                    out.item("$groupName: \"${rule.name}\" $days ${rule.from}-${rule.to} min=${rule.minInstances}")
                }
            }
            return
        }
        if (args[0].lowercase() == "info" && args.size >= 2) {
            val config = configManager.getConfig(args[1])
            if (config == null) {
                out.error("No scaling config for group '${args[1]}'")
                return
            }
            out.header("Schedule: ${args[1]}")
            out.info("Enabled: ${config.schedule.enabled}, Timezone: ${config.schedule.timezone}")
            out.info("Warmup: ${config.schedule.warmup.enabled} (${config.schedule.warmup.leadTimeMinutes}min)")
            for (rule in config.schedule.rules) {
                val days = rule.days.joinToString(",") { it.name.take(3) }
                out.item("\"${rule.name}\": $days ${rule.from}-${rule.to} min=${rule.minInstances}")
            }
        }
    }

    private suspend fun remoteHistory(args: List<String>, out: CommandOutput) {
        if (args.isEmpty()) {
            out.error("Usage: scaling history <group> [hours]")
            return
        }
        val hours = args.getOrNull(1)?.toIntOrNull() ?: 24
        val history = manager.getHistory(args[0], hours)
        out.header("Player History: ${args[0]} (${hours}h)")
        if (history.isEmpty()) {
            out.info("No data yet.")
            return
        }
        val byHour = history.groupBy { it.timestamp.take(13) }
        for ((hourKey, snapshots) in byHour.toSortedMap().toList().takeLast(24)) {
            val avg = snapshots.map { it.playerCount }.average().toInt()
            val max = snapshots.maxOf { it.playerCount }
            out.item("${hourKey.takeLast(2)}:00  avg=$avg max=$max services=${snapshots.map { it.serviceCount }.average().toInt()}")
        }
    }

    private suspend fun remotePredict(args: List<String>, out: CommandOutput) {
        if (args.isEmpty()) {
            out.error("Usage: scaling predict <group>")
            return
        }
        val predictions = manager.getPredictions(args[0], 6)
        out.header("Predictions: ${args[0]} (6h)")
        for (p in predictions) {
            out.item("${String.format("%02d", p.hour)}:00 ${p.dayOfWeek.name.take(3)} ~${p.predictedPlayers} players (${p.confidence}, ${p.source}, ${p.dataPoints} samples)")
        }
    }

    private fun remoteHelp(out: CommandOutput) {
        out.header("Smart Scaling Commands")
        out.item("status — Active schedules and decisions")
        out.item("schedule list — All rules")
        out.item("schedule info <group> — Group rules")
        out.item("history <group> [hours] — Player history")
        out.item("predict <group> — Predictions (6h)")
        out.item("reload — Reload configs")
    }
}
