package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.module.api.CommandOutput
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import kotlin.io.path.exists
import kotlin.io.path.inputStream

class LogsCommand(
    private val serviceManager: ServiceManager,
    private val registry: ServiceRegistry
) : Command {

    override val name = "logs"
    override val description = "Show recent log output from a service"
    override val usage = "logs <service> [lines]"

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        if (args.isEmpty()) {
            output.error("Usage: $usage")
            return true
        }

        val serviceName = args[0]
        val lineCount = if (args.size >= 2) {
            args[1].toIntOrNull() ?: run {
                output.error("Invalid line count: '${args[1]}'")
                return true
            }
        } else {
            50
        }

        val service = registry.get(serviceName)
        if (service == null) {
            output.error("Service '$serviceName' not found.")
            return true
        }

        val logFile = service.workingDirectory.resolve("logs/latest.log")

        if (logFile.exists()) {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            val allLines = InputStreamReader(logFile.inputStream(), decoder).use { it.readLines() }
            val lines = allLines.takeLast(lineCount)

            output.header("Logs: $serviceName")
            output.info("Last $lineCount line(s) from $logFile")
            output.text(ConsoleFormatter.separator())

            for (line in lines) {
                output.text(ConsoleFormatter.hint(line))
            }

            output.text(ConsoleFormatter.separator())
            output.info("${lines.size} of ${allLines.size} line(s)")
        } else {
            // Fall back to stdout buffer (best-effort: SharedFlow has no replay)
            output.info("Log file not found at $logFile")

            val handle = serviceManager.getProcessHandle(serviceName)
            if (handle == null) {
                output.error("No process handle available for '$serviceName'.")
                return true
            }

            output.info("No log file available. The stdout stream is live-only (SharedFlow with no replay).")
            output.info("Use 'screen $serviceName' to attach to the live console.")
        }
        return true
    }

    override suspend fun execute(args: List<String>) {
        execute(args, ConsoleOutput())
    }
}
