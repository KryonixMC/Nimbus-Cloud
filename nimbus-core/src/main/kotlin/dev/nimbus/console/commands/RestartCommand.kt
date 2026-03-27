package dev.nimbus.console.commands

import dev.nimbus.console.Command
import dev.nimbus.console.ConsoleFormatter
import dev.nimbus.service.ServiceManager
import dev.nimbus.service.ServiceRegistry

class RestartCommand(
    private val serviceManager: ServiceManager,
    private val registry: ServiceRegistry
) : Command {

    override val name = "restart"
    override val description = "Restart a running service"
    override val usage = "restart <service>"

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

        println(ConsoleFormatter.info("Restarting service '$serviceName'..."))
        try {
            serviceManager.restartService(serviceName)
            println(ConsoleFormatter.success("Service '$serviceName' restart initiated."))
        } catch (e: Exception) {
            println(ConsoleFormatter.error("Failed to restart service: ${e.message}"))
        }
    }
}
