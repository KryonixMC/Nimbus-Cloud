package dev.nimbus.console.commands

import dev.nimbus.console.Command
import dev.nimbus.console.ConsoleFormatter
import dev.nimbus.service.ServiceManager
import dev.nimbus.service.ServiceRegistry

class ExecCommand(
    private val serviceManager: ServiceManager,
    private val registry: ServiceRegistry
) : Command {

    override val name = "exec"
    override val description = "Execute a command on a service"
    override val usage = "exec <service> <command...>"

    override suspend fun execute(args: List<String>) {
        if (args.size < 2) {
            println(ConsoleFormatter.error("Usage: $usage"))
            return
        }

        val serviceName = args[0]
        val command = args.drop(1).joinToString(" ")

        val service = registry.get(serviceName)
        if (service == null) {
            println(ConsoleFormatter.error("Service '$serviceName' not found."))
            return
        }

        try {
            serviceManager.executeCommand(serviceName, command)
            println(ConsoleFormatter.success("Sent to $serviceName: ") +
                    ConsoleFormatter.colorize(command, ConsoleFormatter.DIM))
        } catch (e: Exception) {
            println(ConsoleFormatter.error("Failed to execute command: ${e.message}"))
        }
    }
}
