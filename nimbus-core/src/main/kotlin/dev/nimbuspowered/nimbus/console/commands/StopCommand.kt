package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.module.CommandOutput
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry

class StopCommand(
    private val serviceManager: ServiceManager,
    private val registry: ServiceRegistry
) : Command {

    override val name = "stop"
    override val description = "Stop a running service"
    override val usage = "stop <service>"

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

        output.info("Stopping service '$serviceName'...")
        try {
            val stopped = serviceManager.stopService(serviceName)
            if (stopped) {
                output.success("Service '$serviceName' stop initiated.")
            } else {
                output.error("Failed to stop service '$serviceName'.")
            }
        } catch (e: Exception) {
            output.error("Failed to stop service: ${e.message}")
        }
        return true
    }

    override suspend fun execute(args: List<String>) {
        execute(args, ConsoleOutput())
    }
}
