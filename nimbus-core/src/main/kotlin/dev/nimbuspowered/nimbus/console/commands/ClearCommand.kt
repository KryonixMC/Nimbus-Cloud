package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.console.Command
import org.jline.terminal.Terminal

class ClearCommand(
    private val terminal: Terminal
) : Command {

    override val name = "clear"
    override val description = "Clear the terminal screen"
    override val usage = "clear"

    override suspend fun execute(args: List<String>) {
        terminal.writer().print("\u001B[2J\u001B[H")
        terminal.writer().flush()
    }
}
