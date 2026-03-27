package dev.nimbus.console.commands

import dev.nimbus.console.Command
import dev.nimbus.console.ConsoleFormatter
import dev.nimbus.group.GroupManager
import dev.nimbus.service.ServiceManager

class StartCommand(
    private val serviceManager: ServiceManager,
    private val groupManager: GroupManager
) : Command {

    override val name = "start"
    override val description = "Start a new service instance for a group"
    override val usage = "start <group>"

    override suspend fun execute(args: List<String>) {
        if (args.isEmpty()) {
            println(ConsoleFormatter.error("Usage: $usage"))
            return
        }

        val groupName = args[0]
        val group = groupManager.getGroup(groupName)
        if (group == null) {
            println(ConsoleFormatter.error("Group '$groupName' not found."))
            println(ConsoleFormatter.colorize(
                "Available groups: ${groupManager.getAllGroups().joinToString(", ") { it.name }}",
                ConsoleFormatter.DIM
            ))
            return
        }

        println(ConsoleFormatter.info("Starting new instance for group '$groupName'..."))
        try {
            serviceManager.startService(groupName)
            println(ConsoleFormatter.success("Service start initiated for group '$groupName'."))
        } catch (e: Exception) {
            println(ConsoleFormatter.error("Failed to start service: ${e.message}"))
        }
    }
}
