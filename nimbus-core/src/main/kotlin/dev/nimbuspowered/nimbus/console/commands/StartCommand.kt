package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.module.api.CommandOutput
import dev.nimbuspowered.nimbus.service.ServiceManager

class StartCommand(
    private val serviceManager: ServiceManager,
    private val groupManager: GroupManager
) : Command {

    override val name = "start"
    override val description = "Start a new service instance for a group"
    override val usage = "start <group>"
    override val permission = "nimbus.cloud.start"

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        if (args.isEmpty()) {
            output.error("Usage: $usage")
            return true
        }

        val groupName = args[0]
        val group = groupManager.getGroup(groupName)
        if (group == null) {
            output.error("Group '$groupName' not found.")
            output.info("Available groups: ${groupManager.getAllGroups().joinToString(", ") { it.name }}")
            return true
        }

        output.info("Starting new instance for group '$groupName'...")
        try {
            val service = serviceManager.startService(groupName)
            if (service != null) {
                output.success("Service '${service.name}' starting on port ${service.port}.")
            } else {
                output.error("Failed to start service for group '$groupName'.")
            }
        } catch (e: Exception) {
            output.error("Failed to start service: ${e.message}")
        }
        return true
    }

    override suspend fun execute(args: List<String>) {
        execute(args, ConsoleOutput())
    }
}
