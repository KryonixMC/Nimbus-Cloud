package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.module.api.CommandOutput
import dev.nimbuspowered.nimbus.service.ServiceRegistry

class GroupsCommand(
    private val groupManager: GroupManager,
    private val registry: ServiceRegistry
) : Command {

    override val name = "groups"
    override val description = "List all configured groups"
    override val usage = "groups"
    override val permission = "nimbus.cloud.groups"

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        val groups = groupManager.getAllGroups()
        if (groups.isEmpty()) {
            output.info("No groups configured.")
            return true
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

        output.header("Groups")
        output.text(ConsoleFormatter.formatTable(headers, rows))
        output.text(ConsoleFormatter.count(groups.size, "group"))
        return true
    }

    override suspend fun execute(args: List<String>) {
        execute(args, ConsoleOutput())
    }
}
