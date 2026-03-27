package dev.nimbus.console.commands

import dev.nimbus.console.Command
import dev.nimbus.console.ConsoleFormatter
import dev.nimbus.service.ServiceRegistry

class ListCommand(
    private val registry: ServiceRegistry
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
            println(ConsoleFormatter.warn("No services running."))
            return
        }

        val headers = listOf("NAME", "GROUP", "STATE", "PORT", "PLAYERS", "PID", "UPTIME")
        val rows = services.sortedBy { it.name }.map { svc ->
            listOf(
                ConsoleFormatter.colorize(svc.name, ConsoleFormatter.BOLD),
                svc.groupName,
                ConsoleFormatter.coloredState(svc.state),
                svc.port.toString(),
                svc.playerCount.toString(),
                (svc.pid?.toString() ?: "-"),
                ConsoleFormatter.formatUptime(svc.startedAt)
            )
        }

        println()
        println(ConsoleFormatter.formatTable(headers, rows))
        println()
        println(ConsoleFormatter.colorize("Total: ${services.size} service(s)", ConsoleFormatter.DIM))
    }
}
