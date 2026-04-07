package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.CommandDispatcher
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.module.CommandOutput

class HelpCommand(
    private val dispatcher: CommandDispatcher
) : Command {

    override val name = "help"
    override val description = "Show all available commands"
    override val usage = "help [command]"

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        if (args.isNotEmpty()) {
            val cmd = dispatcher.getCommand(args[0])
            if (cmd != null) {
                output.text("${ConsoleFormatter.colorize(cmd.name, "${ConsoleFormatter.BOLD}${ConsoleFormatter.BRIGHT_CYAN}")} ${ConsoleFormatter.DIM}— ${cmd.description}${ConsoleFormatter.RESET}")
                output.info("Usage: ${cmd.usage}")
            } else {
                output.error("Unknown command: ${args[0]}")
            }
            return true
        }

        output.header("Commands")

        val commands = dispatcher.getCommands()
        val maxLen = commands.maxOfOrNull { it.name.length } ?: 0

        for (cmd in commands) {
            output.text(ConsoleFormatter.commandEntry(cmd.name, cmd.description, maxLen + 2))
        }

        output.info("Type 'help <command>' for detailed usage.")
        return true
    }

    override suspend fun execute(args: List<String>) {
        execute(args, ConsoleOutput())
    }
}
