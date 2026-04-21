package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.module.api.CommandOutput
import dev.nimbuspowered.nimbus.module.api.CompletionMeta
import dev.nimbuspowered.nimbus.module.api.CompletionType
import dev.nimbuspowered.nimbus.module.api.SubcommandMeta
import dev.nimbuspowered.nimbus.service.DedicatedServiceManager
import dev.nimbuspowered.nimbus.service.Service
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceMemoryResolver
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceState
import java.time.Duration
import java.time.Instant

class HealthCommand(
    private val registry: ServiceRegistry,
    private val groupManager: GroupManager,
    private val serviceManager: ServiceManager
) : Command {

    private val dedicatedServiceManager: DedicatedServiceManager?
        get() = serviceManager.dedicatedServiceManager

    private fun memFor(svc: Service) = ServiceMemoryResolver.resolve(svc, groupManager, dedicatedServiceManager)

    override val name = "health"
    override val description = "Show health metrics for running services"
    override val usage = "health [service|group]"
    override val permission = "nimbus.cloud.health"

    override val subcommandMeta: List<SubcommandMeta> get() = listOf(
        SubcommandMeta("", "Show health overview of all services", "health"),
        SubcommandMeta("<service|group>", "Show health for a specific service or group", "health <name>",
            listOf(CompletionMeta(0, CompletionType.GROUP)))
    )

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        val services = resolveServices(args)
        if (services == null) {
            output.error("No service or group '${args[0]}' found.")
            return true
        }
        if (services.isEmpty()) {
            output.info("No services running.")
            return true
        }

        output.header("Service Health")
        for (svc in services.sortedBy { it.name }) {
            val health = if (svc.state != ServiceState.READY) "-"
                else if (svc.healthy) "healthy" else "UNHEALTHY"
            val m = memFor(svc)
            val mem = if (m.maxMb > 0) "${m.usedMb}/${m.maxMb}MB" else "-"
            output.item("${svc.name}: ${svc.state} | TPS ${String.format("%.1f", svc.tps)} | $mem | $health | restarts=${svc.restartCount}")
        }

        val readyServices = services.filter { it.state == ServiceState.READY }
        val unhealthy = readyServices.count { !it.healthy }
        if (unhealthy > 0) {
            output.error("$unhealthy unhealthy service(s)")
        } else if (readyServices.isNotEmpty()) {
            output.success("All ready services healthy")
        }
        return true
    }

    override suspend fun execute(args: List<String>) {
        val services = resolveServices(args)
        if (services == null) {
            println(ConsoleFormatter.error("No service or group '${args[0]}' found."))
            return
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
                memFor(svc).let { formatMemory(it.usedMb, it.maxMb) },
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

    private fun printServiceDetail(svc: dev.nimbuspowered.nimbus.service.Service) {
        println(ConsoleFormatter.header("Health: ${svc.name}"))

        println(ConsoleFormatter.section("Status"))
        println(ConsoleFormatter.field("State", ConsoleFormatter.coloredState(svc.state)))
        println(ConsoleFormatter.field("Health", formatHealthy(svc.healthy, svc.state)))
        println(ConsoleFormatter.field("PID", svc.pid?.toString() ?: ConsoleFormatter.placeholder()))

        println(ConsoleFormatter.section("Performance"))
        println(ConsoleFormatter.field("TPS", formatTps(svc.tps)))
        val m = memFor(svc)
        println(ConsoleFormatter.field("Memory", formatMemoryDetail(m.usedMb, m.maxMb)))
        if (m.maxMb > 0) {
            val pct = (m.usedMb.toDouble() / m.maxMb * 100).toInt()
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

    /** Returns null if filter didn't match, empty list if no services exist. */
    private fun resolveServices(args: List<String>): List<dev.nimbuspowered.nimbus.service.Service>? {
        if (args.isEmpty()) return registry.getAll()
        val filter = args[0]
        val exactService = registry.get(filter)
        if (exactService != null) return listOf(exactService)
        val byGroup = registry.getByGroup(filter)
        if (byGroup.isEmpty()) return null
        return byGroup
    }
}
