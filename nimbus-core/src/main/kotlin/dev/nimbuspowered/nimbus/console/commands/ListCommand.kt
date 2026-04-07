package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.module.CommandOutput
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceState

class ListCommand(
    private val registry: ServiceRegistry,
    private val clusterEnabled: Boolean = false
) : Command {

    override val name = "list"
    override val description = "List all running services"
    override val usage = "list [group]"

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        val services = if (args.isNotEmpty()) {
            registry.getByGroup(args[0])
        } else {
            registry.getAll()
        }

        if (services.isEmpty()) {
            output.info("No services running.")
            return true
        }

        val headers = if (clusterEnabled) {
            listOf("NAME", "GROUP", "STATE", "HP", "HOST", "PORT", "PLAYERS", "NODE", "PID", "UPTIME")
        } else {
            listOf("NAME", "GROUP", "STATE", "HP", "PORT", "PLAYERS", "PID", "UPTIME")
        }

        val rows = services.sortedBy { it.name }.map { svc ->
            val healthIcon = formatHealthIcon(svc.healthy, svc.state)
            if (clusterEnabled) {
                listOf(
                    ConsoleFormatter.colorize(svc.name, ConsoleFormatter.BOLD),
                    svc.groupName,
                    ConsoleFormatter.coloredState(svc.state),
                    healthIcon,
                    svc.host,
                    if (svc.bedrockPort != null) "${svc.port}/${svc.bedrockPort}" else svc.port.toString(),
                    svc.playerCount.toString(),
                    svc.nodeId,
                    (svc.pid?.toString() ?: "-"),
                    ConsoleFormatter.formatUptime(svc.startedAt)
                )
            } else {
                listOf(
                    ConsoleFormatter.colorize(svc.name, ConsoleFormatter.BOLD),
                    svc.groupName,
                    ConsoleFormatter.coloredState(svc.state),
                    healthIcon,
                    if (svc.bedrockPort != null) "${svc.port}/${svc.bedrockPort}" else svc.port.toString(),
                    svc.playerCount.toString(),
                    (svc.pid?.toString() ?: "-"),
                    ConsoleFormatter.formatUptime(svc.startedAt)
                )
            }
        }

        output.header("Services")
        output.text(ConsoleFormatter.formatTable(headers, rows))
        output.text(ConsoleFormatter.count(services.size, "service"))
        return true
    }

    override suspend fun execute(args: List<String>) {
        execute(args, ConsoleOutput())
    }

    private fun formatHealthIcon(healthy: Boolean, state: ServiceState): String {
        if (state != ServiceState.READY) return ConsoleFormatter.colorize("-", ConsoleFormatter.DIM)
        return if (healthy) {
            ConsoleFormatter.colorize("✓", ConsoleFormatter.GREEN)
        } else {
            ConsoleFormatter.colorize("✗", ConsoleFormatter.RED)
        }
    }
}
