package dev.kryonix.nimbus.console.commands

import dev.kryonix.nimbus.console.Command
import dev.kryonix.nimbus.console.ConsoleFormatter
import dev.kryonix.nimbus.service.ServiceRegistry

class ListCommand(
    private val registry: ServiceRegistry,
    private val clusterEnabled: Boolean = false
) : Command {

    override val name = "list"
    override val description = "List all running services"
    override val usage = "list [group]"

    override suspend fun execute(args: List<String>) {
        val services = if (args.isNotEmpty()) {
            registry.getByGroup(args[0])
        } else {
            registry.getAll()
        }

        if (services.isEmpty()) {
            println(ConsoleFormatter.emptyState("No services running."))
            return
        }

        val headers = if (clusterEnabled) {
            listOf("NAME", "GROUP", "STATE", "HOST", "PORT", "PLAYERS", "NODE", "PID", "UPTIME")
        } else {
            listOf("NAME", "GROUP", "STATE", "PORT", "PLAYERS", "PID", "UPTIME")
        }

        val rows = services.sortedBy { it.name }.map { svc ->
            if (clusterEnabled) {
                listOf(
                    ConsoleFormatter.colorize(svc.name, ConsoleFormatter.BOLD),
                    svc.groupName,
                    ConsoleFormatter.coloredState(svc.state),
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
                    if (svc.bedrockPort != null) "${svc.port}/${svc.bedrockPort}" else svc.port.toString(),
                    svc.playerCount.toString(),
                    (svc.pid?.toString() ?: "-"),
                    ConsoleFormatter.formatUptime(svc.startedAt)
                )
            }
        }

        println(ConsoleFormatter.header("Services"))
        println(ConsoleFormatter.formatTable(headers, rows))
        println(ConsoleFormatter.count(services.size, "service"))
    }
}
