package dev.kryonix.nimbus.console.commands

import dev.kryonix.nimbus.console.Command
import dev.kryonix.nimbus.console.ConsoleFormatter
import dev.kryonix.nimbus.service.ServiceManager
import dev.kryonix.nimbus.service.ServiceRegistry

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
            serviceManager.stopService(serviceName)
            println(ConsoleFormatter.success("Service '$serviceName' stop initiated."))
        } catch (e: Exception) {
            println(ConsoleFormatter.error("Failed to stop service: ${e.message}"))
        }
    }
}
