package dev.nimbus.console.commands

import dev.nimbus.console.Command
import dev.nimbus.console.ConsoleFormatter
import dev.nimbus.group.GroupManager
import dev.nimbus.service.ServiceRegistry

class InfoCommand(
    private val groupManager: GroupManager,
    private val registry: ServiceRegistry
) : Command {

    override val name = "info"
    override val description = "Show detailed group configuration"
    override val usage = "info <group>"

    override suspend fun execute(args: List<String>) {
        if (args.isEmpty()) {
            println(ConsoleFormatter.error("Usage: $usage"))
            return
        }

        val groupName = args[0]
        val group = groupManager.getGroup(groupName)
        if (group == null) {
            println(ConsoleFormatter.error("Group '$groupName' not found."))
            return
        }

        val def = group.config.group
        val services = registry.getByGroup(groupName)
        val totalPlayers = services.sumOf { it.playerCount }

        println()
        println(ConsoleFormatter.colorize("  Group: ${group.name}", ConsoleFormatter.BOLD))
        println(ConsoleFormatter.colorize("-".repeat(50), ConsoleFormatter.DIM))
        println()

        fun field(label: String, value: String) {
            val padded = label.padEnd(24)
            println("  ${ConsoleFormatter.colorize(padded, ConsoleFormatter.CYAN)}$value")
        }

        field("Type", def.type.name)
        field("Software", def.software.name)
        field("Version", def.version)
        field("Template", def.template.ifEmpty { "(default)" })
        println()

        println(ConsoleFormatter.colorize("  Resources", ConsoleFormatter.BOLD))
        field("Memory", def.resources.memory)
        field("Max Players", def.resources.maxPlayers.toString())
        println()

        println(ConsoleFormatter.colorize("  Scaling", ConsoleFormatter.BOLD))
        field("Min Instances", def.scaling.minInstances.toString())
        field("Max Instances", def.scaling.maxInstances.toString())
        field("Players/Instance", def.scaling.playersPerInstance.toString())
        field("Scale Threshold", "${(def.scaling.scaleThreshold * 100).toInt()}%")
        if (def.scaling.idleTimeout > 0) {
            field("Idle Timeout", "${def.scaling.idleTimeout}ms")
        }
        println()

        println(ConsoleFormatter.colorize("  Lifecycle", ConsoleFormatter.BOLD))
        field("Stop on Empty", if (def.lifecycle.stopOnEmpty) "yes" else "no")
        field("Restart on Crash", if (def.lifecycle.restartOnCrash) "yes" else "no")
        field("Max Restarts", def.lifecycle.maxRestarts.toString())
        println()

        println(ConsoleFormatter.colorize("  JVM Args", ConsoleFormatter.BOLD))
        if (def.jvm.args.isEmpty()) {
            println("  (none)")
        } else {
            for (arg in def.jvm.args) {
                println("  ${ConsoleFormatter.colorize(arg, ConsoleFormatter.DIM)}")
            }
        }
        println()

        println(ConsoleFormatter.colorize("  Runtime", ConsoleFormatter.BOLD))
        field("Running Instances", services.size.toString())
        field("Total Players", totalPlayers.toString())
        println()
    }
}
