package dev.kryonix.nimbus.console.commands

import dev.kryonix.nimbus.console.Command
import dev.kryonix.nimbus.console.ConsoleFormatter
import dev.kryonix.nimbus.console.ScreenSession
import dev.kryonix.nimbus.service.ServiceManager
import dev.kryonix.nimbus.service.ServiceRegistry
import dev.kryonix.nimbus.service.ServiceState
import org.jline.terminal.Terminal

class ScreenCommand(
    private val serviceManager: ServiceManager,
    private val registry: ServiceRegistry,
    private val terminal: Terminal
) : Command {

    override val name = "screen"
    override val description = "Attach to a service console"
    override val usage = "screen <service>"

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

        if (service.state == ServiceState.STOPPED || service.state == ServiceState.PREPARING) {
            println(ConsoleFormatter.error("Service '$serviceName' is ${service.state.name}, cannot attach."))
            return
        }

        val processHandle = serviceManager.getProcessHandle(serviceName)
        if (processHandle == null) {
            println(ConsoleFormatter.error("No process handle for '$serviceName'."))
            return
        }

        val session = ScreenSession()
        session.attach(serviceName, processHandle, terminal)
    }
}
