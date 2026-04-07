package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceState

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
                    ConsoleFormatter.hint("$playerName -> $targetService") +
                    ConsoleFormatter.hint(" (via ${proxy.name})"))
        } else {
            println(ConsoleFormatter.error("Failed to send transfer command to ${proxy.name}."))
        }
    }
}
