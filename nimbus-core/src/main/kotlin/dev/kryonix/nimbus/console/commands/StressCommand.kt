package dev.kryonix.nimbus.console.commands

import dev.kryonix.nimbus.config.ServerSoftware
import dev.kryonix.nimbus.console.Command
import dev.kryonix.nimbus.console.ConsoleFormatter
import dev.kryonix.nimbus.group.GroupManager
import dev.kryonix.nimbus.service.ServiceRegistry
import dev.kryonix.nimbus.service.ServiceState
import dev.kryonix.nimbus.stress.StressTestManager

class StressCommand(
    private val stressTestManager: StressTestManager,
    private val registry: ServiceRegistry,
    private val groupManager: GroupManager
) : Command {

    override val name = "stress"
    override val description = "Simulate player load for stress testing"
    override val usage = "stress [start <players> [group] [--ramp <seconds>] | stop | ramp <players> [--duration <seconds>] | status]"

    override suspend fun execute(args: List<String>) {
        if (args.isEmpty()) {
            showStatus()
            return
        }

        when (args[0].lowercase()) {
            "start" -> handleStart(args.drop(1))
            "stop" -> handleStop()
            "ramp" -> handleRamp(args.drop(1))
            "status" -> showStatus()
            else -> {
                println(ConsoleFormatter.error("Unknown subcommand: ${args[0]}"))
                println(ConsoleFormatter.hint("Usage: $usage"))
            }
        }
    }

    private fun handleStart(args: List<String>) {
        if (args.isEmpty()) {
            println(ConsoleFormatter.error("Usage: stress start <players> [group] [--ramp <seconds>]"))
            return
        }

        val players = args[0].toIntOrNull()
        if (players == null || players <= 0) {
            println(ConsoleFormatter.error("Invalid player count: ${args[0]}"))
            return
        }

        // Parse optional group and --ramp flag
        var groupName: String? = null
        var rampSeconds = 0L

        var i = 1
        while (i < args.size) {
            when {
                args[i] == "--ramp" && i + 1 < args.size -> {
                    rampSeconds = args[i + 1].toLongOrNull() ?: 0L
                    i += 2
                }
                groupName == null && !args[i].startsWith("--") -> {
                    groupName = args[i]
                    i++
                }
                else -> i++
            }
        }

        // Validate group exists and is not a proxy
        if (groupName != null) {
            val group = groupManager.getGroup(groupName)
            if (group == null) {
                println(ConsoleFormatter.error("Group '$groupName' not found."))
                return
            }
            if (group.config.group.software == ServerSoftware.VELOCITY) {
                println(ConsoleFormatter.error("Cannot stress test proxy group '$groupName' directly."))
                println(ConsoleFormatter.hint("Proxy player counts are auto-calculated from backend servers."))
                return
            }
        }

        // Check there are READY backend services to target
        val backendGroups = groupManager.getAllGroups()
            .filter { it.config.group.software != ServerSoftware.VELOCITY }
            .map { it.name }
            .toSet()

        val readyBackends = if (groupName != null) {
            registry.getByGroup(groupName).filter { it.state == ServiceState.READY }
        } else {
            registry.getAll().filter { it.state == ServiceState.READY && it.groupName in backendGroups }
        }

        if (readyBackends.isEmpty()) {
            println(ConsoleFormatter.error("No READY backend services found${if (groupName != null) " in group '$groupName'" else ""}."))
            println(ConsoleFormatter.hint("Start some services first, then run the stress test."))
            return
        }

        // Show capacity info
        val totalCapacity = readyBackends.sumOf { service ->
            val group = groupManager.getGroup(service.groupName)
            group?.config?.group?.resources?.maxPlayers ?: 50
        }

        val rampMs = rampSeconds * 1000
        val started = stressTestManager.start(groupName, players, rampMs)

        if (!started) {
            println(ConsoleFormatter.warn("A stress test is already running. Stop it first with: stress stop"))
            return
        }

        val target = if (groupName != null) "group ${ConsoleFormatter.BOLD}$groupName${ConsoleFormatter.RESET}" else "all backend groups"
        val rampInfo = if (rampSeconds > 0) " ${ConsoleFormatter.DIM}(ramping over ${rampSeconds}s)${ConsoleFormatter.RESET}" else ""
        println(ConsoleFormatter.successLine(
            "Stress test started: ${ConsoleFormatter.BOLD}$players${ConsoleFormatter.RESET} simulated players across $target$rampInfo"
        ))

        if (players > totalCapacity) {
            println(ConsoleFormatter.warnLine(
                "Requested $players players exceeds current capacity of $totalCapacity. " +
                "Scaling will add more instances (up to maxInstances)."
            ))
        }

        println(ConsoleFormatter.hint("  Proxy groups auto-updated. Use 'stress status' to monitor, 'stress stop' to end."))
    }

    private suspend fun handleStop() {
        if (!stressTestManager.isActive()) {
            println(ConsoleFormatter.warn("No stress test is running."))
            return
        }

        stressTestManager.stop()
        println(ConsoleFormatter.successLine("Stress test stopped. All simulated players removed, proxy display restored."))
    }

    private fun handleRamp(args: List<String>) {
        if (!stressTestManager.isActive()) {
            println(ConsoleFormatter.error("No stress test is running. Start one first with: stress start <players>"))
            return
        }

        if (args.isEmpty()) {
            println(ConsoleFormatter.error("Usage: stress ramp <players> [--duration <seconds>]"))
            return
        }

        val players = args[0].toIntOrNull()
        if (players == null || players < 0) {
            println(ConsoleFormatter.error("Invalid player count: ${args[0]}"))
            return
        }

        var durationSeconds = 30L
        var i = 1
        while (i < args.size) {
            if (args[i] == "--duration" && i + 1 < args.size) {
                durationSeconds = args[i + 1].toLongOrNull() ?: 30L
                i += 2
            } else {
                i++
            }
        }

        stressTestManager.ramp(players, durationSeconds * 1000)
        println(ConsoleFormatter.successLine(
            "Ramping to ${ConsoleFormatter.BOLD}$players${ConsoleFormatter.RESET} players over ${durationSeconds}s"
        ))
    }

    private fun showStatus() {
        val status = stressTestManager.getStatus()
        if (status == null) {
            println(ConsoleFormatter.emptyState("No stress test running."))
            println(ConsoleFormatter.hint("Start one with: stress start <players> [group] [--ramp <seconds>]"))
            return
        }

        val p = status.profile
        val elapsed = status.elapsedMs / 1000

        println(ConsoleFormatter.header("Stress Test"))
        println()

        // Profile info
        val target = p.groupName ?: "all backend groups"
        println(ConsoleFormatter.field("Target", "${ConsoleFormatter.BOLD}$target${ConsoleFormatter.RESET}"))
        println(ConsoleFormatter.field("Players", "${ConsoleFormatter.BOLD}${p.currentPlayers}${ConsoleFormatter.RESET} / ${p.targetPlayers}"))
        println(ConsoleFormatter.field("Capacity", "${ConsoleFormatter.BOLD}${status.totalCapacity}${ConsoleFormatter.RESET} max across ${status.perService.size} service(s)"))
        println(ConsoleFormatter.field("Elapsed", formatDuration(elapsed)))

        if (p.overflow > 0) {
            println(ConsoleFormatter.field("Overflow", ConsoleFormatter.warn("${p.overflow} players over capacity")))
        }

        // Ramp progress
        if (p.rampDurationMs > 0) {
            val rampProgress = (status.elapsedMs.toDouble() / p.rampDurationMs).coerceAtMost(1.0)
            val pct = (rampProgress * 100).toInt()
            val bar = ConsoleFormatter.progressBar(pct, 100)
            val rampStatus = if (pct >= 100) ConsoleFormatter.success("COMPLETE") else "${pct}%"
            println(ConsoleFormatter.field("Ramp", "$bar $rampStatus"))
        }

        // Backend services breakdown
        if (status.perService.isNotEmpty()) {
            println()
            println(ConsoleFormatter.section("Backend Services"))

            val headers = listOf("Service", "Players", "Max")
            val rows = status.perService.entries.sortedByDescending { it.value }.map { (name, count) ->
                val service = registry.get(name)
                val group = if (service != null) groupManager.getGroup(service.groupName) else null
                val maxPlayers = group?.config?.group?.resources?.maxPlayers ?: 50
                val bar = ConsoleFormatter.progressBar(count, maxPlayers, width = 15)
                listOf(
                    "${ConsoleFormatter.BOLD}$name${ConsoleFormatter.RESET}",
                    "$bar $count/$maxPlayers",
                    ""
                )
            }
            println(ConsoleFormatter.formatTable(headers, rows))
        }

        // Proxy services
        if (status.proxyServices.isNotEmpty()) {
            println()
            println(ConsoleFormatter.section("Proxy Services"))
            for ((name, count) in status.proxyServices) {
                println("  ${ConsoleFormatter.BOLD}$name${ConsoleFormatter.RESET} ${ConsoleFormatter.DIM}→${ConsoleFormatter.RESET} $count players ${ConsoleFormatter.DIM}(reflects total backend count)${ConsoleFormatter.RESET}")
            }
        }

        println()
        println(ConsoleFormatter.hint("Commands: stress ramp <players> [--duration <s>] | stress stop"))
    }

    private fun formatDuration(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}
