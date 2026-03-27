package dev.nimbus.console.commands

import dev.nimbus.console.Command
import dev.nimbus.console.ConsoleFormatter
import dev.nimbus.config.NimbusConfig
import dev.nimbus.group.GroupManager
import dev.nimbus.service.ServiceRegistry
import dev.nimbus.service.ServiceState

class StatusCommand(
    private val config: NimbusConfig,
    private val registry: ServiceRegistry,
    private val groupManager: GroupManager
) : Command {

    override val name = "status"
    override val description = "Show full network status overview"
    override val usage = "status"

    override suspend fun execute(args: List<String>) {
        val allServices = registry.getAll()
        val groups = groupManager.getAllGroups()

        println()
        println(ConsoleFormatter.colorize("  Network: ${config.network.name}", ConsoleFormatter.BOLD))
        println(ConsoleFormatter.colorize("-".repeat(60), ConsoleFormatter.DIM))

        // Summary line
        val readyCount = allServices.count { it.state == ServiceState.READY }
        val totalPlayers = allServices.sumOf { it.playerCount }
        println(
            "  Services: ${ConsoleFormatter.success("$readyCount ready")} / " +
                    "${allServices.size} total    " +
                    "Players: ${ConsoleFormatter.colorize("$totalPlayers", ConsoleFormatter.BOLD)}"
        )
        println()

        // Per-group overview
        if (groups.isEmpty()) {
            println(ConsoleFormatter.warn("  No groups configured."))
        } else {
            val headers = listOf("GROUP", "TYPE", "INSTANCES", "MIN/MAX", "PLAYERS", "STATUS")
            val rows = groups.sortedBy { it.name }.map { group ->
                val services = registry.getByGroup(group.name)
                val running = services.count {
                    it.state == ServiceState.READY || it.state == ServiceState.STARTING
                }
                val players = services.sumOf { it.playerCount }
                val crashed = services.count { it.state == ServiceState.CRASHED }

                val statusText = when {
                    crashed > 0 -> ConsoleFormatter.error("$crashed crashed")
                    running == 0 -> ConsoleFormatter.warn("idle")
                    running >= group.maxInstances -> ConsoleFormatter.warn("at max")
                    else -> ConsoleFormatter.success("healthy")
                }

                listOf(
                    ConsoleFormatter.colorize(group.name, ConsoleFormatter.BOLD),
                    if (group.isStatic) "STATIC" else "DYNAMIC",
                    "$running/${services.size}",
                    "${group.minInstances}/${group.maxInstances}",
                    players.toString(),
                    statusText
                )
            }

            println(ConsoleFormatter.formatTable(headers, rows))
        }

        // Memory bar
        println()
        val maxServices = config.controller.maxServices
        val usedSlots = allServices.size
        val barWidth = 30
        val filled = if (maxServices > 0) (usedSlots.toDouble() / maxServices * barWidth).toInt() else 0
        val bar = buildString {
            append("  Capacity: [")
            append(ConsoleFormatter.colorize("#".repeat(filled.coerceAtMost(barWidth)), ConsoleFormatter.GREEN))
            append(ConsoleFormatter.colorize("-".repeat((barWidth - filled).coerceAtLeast(0)), ConsoleFormatter.DIM))
            append("] $usedSlots/$maxServices services")
        }
        println(bar)
        println()
    }
}
