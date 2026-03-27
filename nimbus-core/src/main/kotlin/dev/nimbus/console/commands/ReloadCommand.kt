package dev.nimbus.console.commands

import dev.nimbus.config.ConfigLoader
import dev.nimbus.console.Command
import dev.nimbus.console.ConsoleFormatter
import dev.nimbus.group.GroupManager
import dev.nimbus.service.ServiceRegistry
import java.nio.file.Path

class ReloadCommand(
    private val groupManager: GroupManager,
    private val registry: ServiceRegistry,
    private val groupsDir: Path
) : Command {

    override val name = "reload"
    override val description = "Hot-reload group configuration files"
    override val usage = "reload"

    override suspend fun execute(args: List<String>) {
        println(ConsoleFormatter.info("Reloading configurations..."))

        val configs = try {
            ConfigLoader.loadGroupConfigs(groupsDir)
        } catch (e: Exception) {
            println(ConsoleFormatter.error("Failed to load configs: ${e.message}"))
            println(ConsoleFormatter.warn("Keeping current configuration."))
            return
        }

        // Snapshot current group names before reload
        val previousGroupNames = groupManager.getAllGroups().map { it.name }.toSet()

        groupManager.reloadGroups(configs)

        val loadedGroups = groupManager.getAllGroups()
        println(ConsoleFormatter.success("Loaded ${configs.size} group configuration(s)."))
        println()

        // Show instance count per group
        for (group in loadedGroups.sortedBy { it.name }) {
            val instances = registry.getByGroup(group.name).size
            val countText = if (instances > 0) {
                ConsoleFormatter.colorize("$instances running", ConsoleFormatter.GREEN)
            } else {
                ConsoleFormatter.colorize("0 running", ConsoleFormatter.DIM)
            }
            println("  ${ConsoleFormatter.colorize(group.name, ConsoleFormatter.BOLD)}  $countText")
        }

        // Warn about groups with running services that were removed from config
        val configuredNames = configs.map { it.group.name }.toSet()
        val orphanedGroups = previousGroupNames - configuredNames
        for (groupName in orphanedGroups.sorted()) {
            val runningServices = registry.getByGroup(groupName)
            if (runningServices.isNotEmpty()) {
                println()
                println(
                    ConsoleFormatter.warn(
                        "Group '$groupName' was removed from config but has " +
                            "${runningServices.size} running service(s). They will continue until stopped."
                    )
                )
            }
        }
    }
}
