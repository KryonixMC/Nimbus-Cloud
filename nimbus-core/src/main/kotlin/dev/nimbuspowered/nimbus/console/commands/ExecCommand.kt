package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.module.api.CommandOutput
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry

class ExecCommand(
    private val serviceManager: ServiceManager,
    private val registry: ServiceRegistry
) : Command {

    override val name = "exec"
    override val description = "Execute a command on a service"
    override val usage = "exec <service> <command...>"
    override val permission = "nimbus.cloud.exec"

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        if (args.size < 2) {
            output.error("Usage: $usage")
            return true
        }

        val serviceName = args[0]
        val command = args.drop(1).joinToString(" ")

        val service = registry.get(serviceName)
        if (service == null) {
            output.error("Service '$serviceName' not found.")
            return true
        }

        try {
            serviceManager.executeCommand(serviceName, command)
            output.text(ConsoleFormatter.success("Sent to $serviceName: ") +
                    ConsoleFormatter.hint(command))
        } catch (e: Exception) {
            output.error("Failed to execute command: ${e.message}")
        }
        return true
    }

    override suspend fun execute(args: List<String>) {
        execute(args, ConsoleOutput())
    }
}
