package dev.nimbus.console.commands

import dev.nimbus.config.ServerSoftware
import dev.nimbus.console.Command
import dev.nimbus.console.ConsoleFormatter
import dev.nimbus.group.GroupManager
import dev.nimbus.service.ServiceManager
import dev.nimbus.service.ServiceRegistry
import dev.nimbus.service.ServiceState

class SendCommand(
    private val serviceManager: ServiceManager,
    private val registry: ServiceRegistry,
    private val groupManager: GroupManager
) : Command {

    override val name = "send"
    override val description = "Transfer a player to another service"
    override val usage = "send <player> <service>"

    override suspend fun execute(args: List<String>) {
        if (args.size != 2) {
            println(ConsoleFormatter.error("Usage: $usage"))
            return
        }

        val playerName = args[0]
        val targetService = args[1]

        // Verify the target service exists
        if (registry.get(targetService) == null) {
            println(ConsoleFormatter.error("Target service '$targetService' not found."))
            return
        }

        // Find a running Velocity proxy
        val proxy = registry.getAll()
            .filter { it.state == ServiceState.READY }
            .firstOrNull { service ->
                val group = groupManager.getGroup(service.groupName)
                group != null && group.config.group.software == ServerSoftware.VELOCITY
            }

        if (proxy == null) {
            println(ConsoleFormatter.error("No running Velocity proxy found."))
            return
        }

        val command = "send $playerName $targetService"
        val success = serviceManager.executeCommand(proxy.name, command)

        if (success) {
            println(ConsoleFormatter.success("Sent transfer command: ") +
                    ConsoleFormatter.colorize("$playerName -> $targetService", ConsoleFormatter.DIM) +
                    ConsoleFormatter.colorize(" (via ${proxy.name})", ConsoleFormatter.DIM))
        } else {
            println(ConsoleFormatter.error("Failed to send transfer command to ${proxy.name}."))
        }
    }
}
