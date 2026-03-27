package dev.nimbus.console.commands

import dev.nimbus.console.Command
import dev.nimbus.console.ConsoleFormatter
import dev.nimbus.service.ServiceManager
import dev.nimbus.service.ServiceRegistry
import kotlin.io.path.exists
import kotlin.io.path.readLines

class LogsCommand(
    private val serviceManager: ServiceManager,
    private val registry: ServiceRegistry
) : Command {

    override val name = "logs"
    override val description = "Show recent log output from a service"
    override val usage = "logs <service> [lines]"

    override suspend fun execute(args: List<String>) {
        if (args.isEmpty()) {
            println(ConsoleFormatter.error("Usage: $usage"))
            return
        }

        val serviceName = args[0]
        val lineCount = if (args.size >= 2) {
            args[1].toIntOrNull() ?: run {
                println(ConsoleFormatter.error("Invalid line count: '${args[1]}'"))
                return
            }
        } else {
            50
        }

        val service = registry.get(serviceName)
        if (service == null) {
            println(ConsoleFormatter.error("Service '$serviceName' not found."))
            return
        }

        val logFile = service.workingDirectory.resolve("logs/latest.log")

        if (logFile.exists()) {
            val allLines = logFile.readLines()
            val lines = allLines.takeLast(lineCount)

            println()
            println(ConsoleFormatter.info("Last $lineCount line(s) of $serviceName") +
                    ConsoleFormatter.colorize(" (${logFile})", ConsoleFormatter.DIM))
            println(ConsoleFormatter.colorize("-".repeat(60), ConsoleFormatter.DIM))

            for (line in lines) {
                println(ConsoleFormatter.colorize(line, ConsoleFormatter.DIM))
            }

            println(ConsoleFormatter.colorize("-".repeat(60), ConsoleFormatter.DIM))
            println(ConsoleFormatter.colorize("Showing ${lines.size} of ${allLines.size} line(s)", ConsoleFormatter.DIM))
        } else {
            // Fall back to stdout buffer (best-effort: SharedFlow has no replay)
            println(ConsoleFormatter.warn("Log file not found at $logFile"))

            val handle = serviceManager.getProcessHandle(serviceName)
            if (handle == null) {
                println(ConsoleFormatter.error("No process handle available for '$serviceName'."))
                return
            }

            println(ConsoleFormatter.info("No log file available. The stdout stream is live-only (SharedFlow with no replay)."))
            println(ConsoleFormatter.info("Use 'screen $serviceName' to attach to the live console."))
        }
    }
}
