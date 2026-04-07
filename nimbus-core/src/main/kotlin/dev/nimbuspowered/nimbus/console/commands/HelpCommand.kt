package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.CommandDispatcher
import dev.nimbuspowered.nimbus.console.ConsoleFormatter

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
                println("${ConsoleFormatter.colorize(cmd.name, "${ConsoleFormatter.BOLD}${ConsoleFormatter.BRIGHT_CYAN}")} ${ConsoleFormatter.DIM}— ${cmd.description}${ConsoleFormatter.RESET}")
                println(ConsoleFormatter.hint("Usage: ${cmd.usage}"))
            } else {
                println(ConsoleFormatter.error("Unknown command: ${args[0]}"))
            }
            return
        }

        println(ConsoleFormatter.header("Commands"))

        val commands = dispatcher.getCommands()
        val maxLen = commands.maxOfOrNull { it.name.length } ?: 0

        for (cmd in commands) {
            println(ConsoleFormatter.commandEntry(cmd.name, cmd.description, maxLen + 2))
        }

        println(ConsoleFormatter.hint("Type 'help <command>' for detailed usage."))
    }
}
