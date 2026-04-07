package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry

class StopCommand(
    private val serviceManager: ServiceManager,
    private val registry: ServiceRegistry
) : Command {

    override val name = "stop"
    override val description = "Stop a running service"
    override val usage = "stop <service>"

    override suspend fun execute(args: List<String>) {
        if (args.isEmpty()) {
            println(ConsoleFormatter.error("Usage: $usage"))
            return
        }

        val serviceName = args[0]
        val service = registry.get(serviceName)
        if (service == null) {
            println(ConsoleFormatter.error("Service '$serviceName' not found."))
            return
        }

        println(ConsoleFormatter.info("Stopping service '$serviceName'..."))
        try {
            val stopped = serviceManager.stopService(serviceName)
            if (stopped) {
                println(ConsoleFormatter.success("Service '$serviceName' stop initiated."))
            } else {
                println(ConsoleFormatter.error("Failed to stop service '$serviceName'."))
            }
        } catch (e: Exception) {
            println(ConsoleFormatter.error("Failed to stop service: ${e.message}"))
        }
    }
}
