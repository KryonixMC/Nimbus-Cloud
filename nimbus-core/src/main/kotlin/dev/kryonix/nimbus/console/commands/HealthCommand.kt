package dev.kryonix.nimbus.console.commands

import dev.kryonix.nimbus.console.Command
import dev.kryonix.nimbus.console.ConsoleFormatter
import dev.kryonix.nimbus.service.ServiceRegistry
import dev.kryonix.nimbus.service.ServiceState
import java.time.Duration
import java.time.Instant

class HealthCommand(
    private val registry: ServiceRegistry
) : Command {

    override val name = "health"
    override val description = "Show health metrics for running services"
    override val usage = "health [service|group]"

    override suspend fun execute(args: List<String>) {
        val services = if (args.isNotEmpty()) {
            val filter = args[0]
            // Try exact service match first, then group match
            val exactService = registry.get(filter)
            if (exactService != null) {
                listOf(exactService)
            } else {
                val byGroup = registry.getByGroup(filter)
                if (byGroup.isEmpty()) {
                    println(ConsoleFormatter.error("No service or group '$filter' found."))
                    return
                }
                byGroup
            }
        } else {
            registry.getAll()
        }

        if (services.isEmpty()) {
            println(ConsoleFormatter.emptyState("No services running."))
            return
        }

        // Detail view for a single service
        if (services.size == 1 && args.isNotEmpty() && registry.get(args[0]) != null) {
            printServiceDetail(services.first())
            return
        }

        // Table view for multiple services
        val headers = listOf("NAME", "STATE", "TPS", "MEMORY", "HEALTH", "RESTARTS", "UPTIME")
        val rows = services.sortedBy { it.name }.map { svc ->
            listOf(
                ConsoleFormatter.colorize(svc.name, ConsoleFormatter.BOLD),
                ConsoleFormatter.coloredState(svc.state),
                formatTps(svc.tps),
                formatMemory(svc.memoryUsedMb, svc.memoryMaxMb),
                formatHealthy(svc.healthy, svc.state),
                formatRestarts(svc.restartCount),
                ConsoleFormatter.formatUptime(svc.startedAt)
            )
        }

        println(ConsoleFormatter.header("Service Health"))
        println(ConsoleFormatter.formatTable(headers, rows))
        println(ConsoleFormatter.count(services.size, "service"))

        // Summary line
        val readyServices = services.filter { it.state == ServiceState.READY }
        val unhealthy = readyServices.count { !it.healthy }
        if (unhealthy > 0) {
            println(ConsoleFormatter.warnLine("$unhealthy ${if (unhealthy == 1) "service" else "services"} unhealthy"))
        } else if (readyServices.isNotEmpty()) {
            println(ConsoleFormatter.successLine("All ready services healthy"))
        }
    }

    private fun printServiceDetail(svc: dev.kryonix.nimbus.service.Service) {
        println(ConsoleFormatter.header("Health: ${svc.name}"))

        println(ConsoleFormatter.section("Status"))
        println(ConsoleFormatter.field("State", ConsoleFormatter.coloredState(svc.state)))
        println(ConsoleFormatter.field("Health", formatHealthy(svc.healthy, svc.state)))
        println(ConsoleFormatter.field("PID", svc.pid?.toString() ?: ConsoleFormatter.placeholder()))

        println(ConsoleFormatter.section("Performance"))
        println(ConsoleFormatter.field("TPS", formatTps(svc.tps)))
        println(ConsoleFormatter.field("Memory", formatMemoryDetail(svc.memoryUsedMb, svc.memoryMaxMb)))
        if (svc.memoryMaxMb > 0) {
            val pct = (svc.memoryUsedMb.toDouble() / svc.memoryMaxMb * 100).toInt()
            println(ConsoleFormatter.field("", "${ConsoleFormatter.progressBar(pct, 100)} $pct%"))
        }

        println(ConsoleFormatter.section("Lifecycle"))
        println(ConsoleFormatter.field("Uptime", ConsoleFormatter.formatUptime(svc.startedAt)))
        println(ConsoleFormatter.field("Restarts", formatRestarts(svc.restartCount)))
        println(ConsoleFormatter.field("Players", "${svc.playerCount}"))

        if (svc.lastHealthReport != null) {
            val ago = Duration.between(svc.lastHealthReport, Instant.now()).seconds
            println(ConsoleFormatter.field("Last Report", "${ago}s ago"))
        } else {
            println(ConsoleFormatter.field("Last Report", ConsoleFormatter.placeholder("no SDK reports")))
        }
    }

    private fun formatTps(tps: Double): String {
        val color = when {
            tps >= 19.5 -> ConsoleFormatter.GREEN
            tps >= 15.0 -> ConsoleFormatter.YELLOW
            else -> ConsoleFormatter.RED
        }
        return ConsoleFormatter.colorize(String.format("%.1f", tps), color)
    }

    private fun formatMemory(usedMb: Long, maxMb: Long): String {
        if (maxMb == 0L) return ConsoleFormatter.placeholder()
        val pct = (usedMb.toDouble() / maxMb * 100).toInt()
        val color = when {
            pct >= 90 -> ConsoleFormatter.RED
            pct >= 70 -> ConsoleFormatter.YELLOW
            else -> ConsoleFormatter.GREEN
        }
        return ConsoleFormatter.colorize("${usedMb}/${maxMb}MB", color)
    }

    private fun formatMemoryDetail(usedMb: Long, maxMb: Long): String {
        if (maxMb == 0L) return ConsoleFormatter.placeholder("no data")
        return "${usedMb}MB / ${maxMb}MB"
    }

    private fun formatHealthy(healthy: Boolean, state: ServiceState): String {
        if (state != ServiceState.READY) return ConsoleFormatter.placeholder()
        return if (healthy) {
            ConsoleFormatter.success("healthy")
        } else {
            ConsoleFormatter.error("unhealthy")
        }
    }

    private fun formatRestarts(count: Int): String {
        return if (count == 0) {
            ConsoleFormatter.colorize("0", ConsoleFormatter.DIM)
        } else {
            ConsoleFormatter.warn(count.toString())
        }
    }
}
