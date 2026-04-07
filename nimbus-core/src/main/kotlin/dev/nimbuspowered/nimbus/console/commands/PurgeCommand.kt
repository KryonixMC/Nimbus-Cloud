package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceState

class PurgeCommand(
    private val serviceManager: ServiceManager,
    private val registry: ServiceRegistry
) : Command {

    override val name = "purge"
    override val description = "Force-kill a service process or clean up crashed services"
    override val usage = "purge <service|crashed|pid:<pid>>"

    override suspend fun execute(args: List<String>) {
        if (args.isEmpty()) {
            println(ConsoleFormatter.error("Usage: $usage"))
            println(ConsoleFormatter.hint("  purge <service>   — Force-kill and remove a specific service"))
            println(ConsoleFormatter.hint("  purge crashed     — Remove all crashed services"))
            println(ConsoleFormatter.hint("  purge pid:<pid>   — Kill a process by PID"))
            return
        }

        val target = args[0]

        when {
            target.equals("crashed", ignoreCase = true) -> purgeCrashed()
            target.startsWith("pid:", ignoreCase = true) -> purgeByPid(target.substringAfter(":"))
            else -> purgeService(target)
        }
    }

    private suspend fun purgeService(serviceName: String) {
        val service = registry.get(serviceName)
        if (service == null) {
            println(ConsoleFormatter.error("Service '$serviceName' not found."))
            return
        }

        println(ConsoleFormatter.warn("Force-killing service '$serviceName' (state: ${service.state})..."))
        try {
            serviceManager.purgeService(serviceName)
            println(ConsoleFormatter.success("Service '$serviceName' purged."))
        } catch (e: Exception) {
            println(ConsoleFormatter.error("Failed to purge service: ${e.message}"))
        }
    }

    private suspend fun purgeCrashed() {
        val crashed = registry.getAll().filter { it.state == ServiceState.CRASHED }
        if (crashed.isEmpty()) {
            println(ConsoleFormatter.info("No crashed services found."))
            return
        }

        println(ConsoleFormatter.warn("Purging ${crashed.size} crashed service(s)..."))
        var purged = 0
        for (service in crashed) {
            try {
                serviceManager.purgeService(service.name)
                println(ConsoleFormatter.success("  Purged '${service.name}'"))
                purged++
            } catch (e: Exception) {
                println(ConsoleFormatter.error("  Failed to purge '${service.name}': ${e.message}"))
            }
        }
        println(ConsoleFormatter.success("Purged $purged/${crashed.size} crashed service(s)."))
    }

    private fun purgeByPid(pidStr: String) {
        val pid = pidStr.toLongOrNull()
        if (pid == null) {
            println(ConsoleFormatter.error("Invalid PID: $pidStr"))
            return
        }

        val processHandle = try {
            ProcessHandle.of(pid).orElse(null)
        } catch (_: Exception) {
            null
        }

        if (processHandle == null) {
            println(ConsoleFormatter.error("No process found with PID $pid."))
            return
        }

        println(ConsoleFormatter.warn("Force-killing process $pid (${processHandle.info().command().orElse("unknown")})..."))
        processHandle.destroyForcibly()
        println(ConsoleFormatter.success("Sent kill signal to PID $pid."))
    }
}
