package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.module.CommandOutput
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

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        if (args.isEmpty()) {
            output.error("Usage: $usage")
            output.info("  purge <service>   — Force-kill and remove a specific service")
            output.info("  purge crashed     — Remove all crashed services")
            output.info("  purge pid:<pid>   — Kill a process by PID")
            return true
        }

        val target = args[0]

        when {
            target.equals("crashed", ignoreCase = true) -> {
                val crashed = registry.getAll().filter { it.state == ServiceState.CRASHED }
                if (crashed.isEmpty()) {
                    output.info("No crashed services found.")
                    return true
                }

                output.info("Purging ${crashed.size} crashed service(s)...")
                var purged = 0
                for (service in crashed) {
                    try {
                        serviceManager.purgeService(service.name)
                        output.success("  Purged '${service.name}'")
                        purged++
                    } catch (e: Exception) {
                        output.error("  Failed to purge '${service.name}': ${e.message}")
                    }
                }
                output.success("Purged $purged/${crashed.size} crashed service(s).")
            }
            target.startsWith("pid:", ignoreCase = true) -> {
                val pidStr = target.substringAfter(":")
                val pid = pidStr.toLongOrNull()
                if (pid == null) {
                    output.error("Invalid PID: $pidStr")
                    return true
                }

                val processHandle = try {
                    ProcessHandle.of(pid).orElse(null)
                } catch (_: Exception) {
                    null
                }

                if (processHandle == null) {
                    output.error("No process found with PID $pid.")
                    return true
                }

                output.info("Force-killing process $pid (${processHandle.info().command().orElse("unknown")})...")
                processHandle.destroyForcibly()
                output.success("Sent kill signal to PID $pid.")
            }
            else -> {
                val service = registry.get(target)
                if (service == null) {
                    output.error("Service '$target' not found.")
                    return true
                }

                output.info("Force-killing service '$target' (state: ${service.state})...")
                try {
                    serviceManager.purgeService(target)
                    output.success("Service '$target' purged.")
                } catch (e: Exception) {
                    output.error("Failed to purge service: ${e.message}")
                }
            }
        }
        return true
    }

    override suspend fun execute(args: List<String>) {
        execute(args, ConsoleOutput())
    }
}
