package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.module.CommandOutput
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry

class RestartCommand(
    private val serviceManager: ServiceManager,
    private val registry: ServiceRegistry
) : Command {

    override val name = "restart"
    override val description = "Restart a running service"
    override val usage = "restart <service>"

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        if (args.isEmpty()) {
            output.error("Usage: $usage")
            return true
        }

        val serviceName = args[0]
        val service = registry.get(serviceName)
        if (service == null) {
            output.error("Service '$serviceName' not found.")
            return true
        }

        output.info("Restarting service '$serviceName'...")
        try {
            val newService = serviceManager.restartService(serviceName)
            if (newService != null) {
                output.success("Service restarted as '${newService.name}' on port ${newService.port}.")
            } else {
                output.error("Failed to restart service '$serviceName'.")
            }
        } catch (e: Exception) {
            output.error("Failed to restart service: ${e.message}")
        }
        return true
    }

    override suspend fun execute(args: List<String>) {
        execute(args, ConsoleOutput())
    }
}
