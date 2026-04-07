package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry

class GroupsCommand(
    private val groupManager: GroupManager,
    private val registry: ServiceRegistry
) : Command {

    override val name = "groups"
    override val description = "List all configured groups"
    override val usage = "groups"

    override suspend fun execute(args: List<String>) {
        val groups = groupManager.getAllGroups()
        if (groups.isEmpty()) {
            println(ConsoleFormatter.emptyState("No groups configured."))
            return
        }

        val headers = listOf("NAME", "TYPE", "SOFTWARE", "VERSION", "MEMORY", "INSTANCES", "MIN/MAX")
        val rows = groups.sortedBy { it.name }.map { group ->
            val running = registry.countByGroup(group.name)
            val def = group.config.group
            listOf(
                ConsoleFormatter.colorize(group.name, ConsoleFormatter.BOLD),
                def.type.name,
                def.software.name,
                def.version,
                def.resources.memory,
                running.toString(),
                "${group.minInstances}/${group.maxInstances}"
            )
        }

        println(ConsoleFormatter.header("Groups"))
        println(ConsoleFormatter.formatTable(headers, rows))
        println(ConsoleFormatter.count(groups.size, "group"))
    }
}
