package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.module.api.CommandOutput
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceState

class BroadcastCommand(
    private val serviceManager: ServiceManager,
    private val registry: ServiceRegistry,
    private val groupManager: GroupManager
) : Command {

    override val name = "broadcast"
    override val description = "Broadcast a message to all services (or a specific group)"
    override val usage = "broadcast [--group <group>] <message...>"
    override val permission = "nimbus.cloud.broadcast"

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        if (args.isEmpty()) {
            output.error("Usage: $usage")
            return true
        }

        var group: String? = null
        var messageStart = 0

        if (args[0].equals("--group", ignoreCase = true) || args[0].equals("-g", ignoreCase = true)) {
            if (args.size < 3) {
                output.error("Usage: broadcast --group <group> <message...>")
                return true
            }
            group = args[1]
            messageStart = 2
        }

        val message = args.drop(messageStart).joinToString(" ")
        if (message.isBlank()) {
            output.error("Usage: $usage")
            return true
        }

        val targets = if (group != null) {
            registry.getByGroup(group).filter { it.state == ServiceState.READY }
        } else {
            registry.getAll().filter { it.state == ServiceState.READY }
        }

        if (targets.isEmpty()) {
            val scope = group?.let { "group '$it'" } ?: "network"
            output.error("No ready services found in $scope.")
            return true
        }

        var sent = 0
        for (service in targets) {
            val grp = groupManager.getGroup(service.groupName)
            val cmd = if (grp?.config?.group?.software == ServerSoftware.VELOCITY) {
                "velocity broadcast $message"
            } else {
                "say $message"
            }
            if (serviceManager.executeCommand(service.name, cmd)) sent++
        }

        val scope = group?.let { "group '$it'" } ?: "network"
        output.success("Broadcast sent to $sent/${targets.size} services in $scope.")
        return true
    }

    override suspend fun execute(args: List<String>) {
        execute(args, ConsoleOutput())
    }
}
