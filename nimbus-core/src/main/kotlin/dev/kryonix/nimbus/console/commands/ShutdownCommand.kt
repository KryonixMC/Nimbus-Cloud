package dev.kryonix.nimbus.console.commands

import dev.kryonix.nimbus.console.Command
import dev.kryonix.nimbus.console.ConsoleFormatter
import dev.kryonix.nimbus.scaling.ScalingEngine
import dev.kryonix.nimbus.service.ServiceManager
import dev.kryonix.nimbus.service.ServiceRegistry

class ShutdownCommand(
    private val serviceManager: ServiceManager,
    private val registry: ServiceRegistry,
    private val scalingEngine: ScalingEngine? = null
) : Command {

    override val name = "shutdown"
    override val description = "Gracefully shut down all services and exit"
    override val usage = "shutdown  →  then  shutdown confirm  (within 30s)"

    /** Timestamp when the confirmation was requested. 0 = not awaiting. */
    @Volatile
    private var confirmRequestedAt: Long = 0

    /** Set to true after a successful shutdown — checked by CommandDispatcher to exit the REPL. */
    @Volatile
    var shouldExit: Boolean = false
        private set

    override suspend fun execute(args: List<String>) {
        if (args.firstOrNull()?.lowercase() == "confirm") {
            val elapsed = System.currentTimeMillis() - confirmRequestedAt
            if (confirmRequestedAt == 0L || elapsed > 30_000) {
                confirmRequestedAt = 0
                println(ConsoleFormatter.error("No pending shutdown or confirmation expired. Run ${ConsoleFormatter.BOLD}shutdown${ConsoleFormatter.RESET} first."))
                return
            }
            confirmRequestedAt = 0
            performShutdown()
            shouldExit = true
            return
        }

        val services = registry.getAll()
        if (services.isEmpty()) {
            println(ConsoleFormatter.info("No services running. Shutting down..."))
            shouldExit = true
            return
        }

        confirmRequestedAt = System.currentTimeMillis()
        println(ConsoleFormatter.warnLine(
            "${ConsoleFormatter.BOLD}${services.size} service(s)${ConsoleFormatter.RESET} will be stopped. " +
            "Type ${ConsoleFormatter.BOLD}${ConsoleFormatter.RED}shutdown confirm${ConsoleFormatter.RESET} within 30 seconds to proceed."
        ))
    }

    private suspend fun performShutdown() {
        val services = registry.getAll()
        if (services.isEmpty()) {
            println(ConsoleFormatter.info("No services running. Shutting down..."))
            return
        }

        println(ConsoleFormatter.warn("Initiating graceful shutdown..."))
        scalingEngine?.shutdown()
        println(ConsoleFormatter.hint("Stopping ${services.size} service(s)..."))

        try {
            serviceManager.stopAll()
            println("${ConsoleFormatter.success("All services stopped.")} ${ConsoleFormatter.hint("Goodbye.")}")
        } catch (e: Exception) {
            println(ConsoleFormatter.error("Error during shutdown: ${e.message}"))
            println(ConsoleFormatter.warn("Forcing exit."))
        }
    }
}
