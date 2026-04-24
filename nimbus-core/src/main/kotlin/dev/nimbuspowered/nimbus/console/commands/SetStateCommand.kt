package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.module.api.CommandOutput
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceState

class SetStateCommand(
    private val registry: ServiceRegistry,
    private val eventBus: EventBus
) : Command {

    override val name = "setstate"
    override val description = "Set or clear a custom state label on a service"
    override val usage = "setstate <service> <state|clear>"
    override val permission = "nimbus.cloud.setstate"

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        if (args.size < 2) {
            output.error("Usage: $usage")
            return true
        }

        val serviceName = args[0]
        val stateArg = args[1]

        val service = registry.get(serviceName)
        if (service == null) {
            output.error("Service '$serviceName' not found.")
            return true
        }

        if (service.state != ServiceState.READY) {
            output.error("Service '$serviceName' is not READY (current: ${service.state}).")
            return true
        }

        val oldState = service.customState
        val newState = if (stateArg.equals("clear", ignoreCase = true) || stateArg.equals("null", ignoreCase = true)) {
            null
        } else {
            stateArg.uppercase()
        }

        service.customState = newState
        eventBus.emit(NimbusEvent.ServiceCustomStateChanged(
            serviceName = serviceName,
            groupName = service.groupName,
            oldState = oldState,
            newState = newState
        ))

        if (newState == null) {
            output.success("Cleared custom state on '$serviceName'.")
        } else {
            output.success("Custom state on '$serviceName' set to '$newState'.")
        }
        return true
    }

    override suspend fun execute(args: List<String>) {
        execute(args, ConsoleOutput())
    }
}
