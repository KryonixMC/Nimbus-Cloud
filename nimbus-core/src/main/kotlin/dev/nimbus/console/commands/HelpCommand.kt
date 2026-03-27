package dev.nimbus.console.commands

import dev.nimbus.console.Command
import dev.nimbus.console.CommandDispatcher
import dev.nimbus.console.ConsoleFormatter

class HelpCommand(
    private val dispatcher: CommandDispatcher
) : Command {

    override val name = "help"
    override val description = "Show all available commands"
    override val usage = "help [command]"

    override suspend fun execute(args: List<String>) {
        if (args.isNotEmpty()) {
            val cmd = dispatcher.getCommand(args[0])
            if (cmd != null) {
                println(ConsoleFormatter.colorize(cmd.name, ConsoleFormatter.BOLD) +
                        " - ${cmd.description}")
                println(ConsoleFormatter.colorize("  Usage: ${cmd.usage}", ConsoleFormatter.DIM))
            } else {
                println(ConsoleFormatter.error("Unknown command: ${args[0]}"))
            }
            return
        }

        println()
        println(ConsoleFormatter.colorize("Available Commands", ConsoleFormatter.BOLD))
        println(ConsoleFormatter.colorize("-".repeat(40), ConsoleFormatter.DIM))

        val commands = dispatcher.getCommands()
        val maxLen = commands.maxOfOrNull { it.name.length } ?: 0

        for (cmd in commands) {
            val padded = cmd.name.padEnd(maxLen + 2)
            println("  ${ConsoleFormatter.colorize(padded, ConsoleFormatter.CYAN)}${cmd.description}")
        }

        println()
        println(ConsoleFormatter.colorize("Type 'help <command>' for detailed usage.", ConsoleFormatter.DIM))
    }
}
