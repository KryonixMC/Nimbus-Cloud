package dev.kryonix.nimbus.console.commands

import dev.kryonix.nimbus.cluster.NodeManager
import dev.kryonix.nimbus.console.Command
import dev.kryonix.nimbus.console.ConsoleFormatter
import dev.kryonix.nimbus.config.NimbusConfig
import dev.kryonix.nimbus.group.GroupManager
import dev.kryonix.nimbus.loadbalancer.TcpLoadBalancer
import dev.kryonix.nimbus.service.ServiceRegistry
import dev.kryonix.nimbus.service.ServiceState

class StatusCommand(
    private val config: NimbusConfig,
    private val registry: ServiceRegistry,
    private val groupManager: GroupManager,
    private val nodeManager: NodeManager? = null,
    private val loadBalancer: TcpLoadBalancer? = null
) : Command {

    override val name = "status"
    override val description = "Show full network status overview"
    override val usage = "status"

    override suspend fun execute(args: List<String>) {
        val allServices = registry.getAll()
        val groups = groupManager.getAllGroups()

        println(ConsoleFormatter.header("Network: ${config.network.name}"))

        // Summary line
        val readyCount = allServices.count { it.state == ServiceState.READY }
        val totalPlayers = allServices.sumOf { it.playerCount }
        println(
            "${ConsoleFormatter.hint("Services:")} ${ConsoleFormatter.success("$readyCount ready")} ${ConsoleFormatter.hint("/ ${allServices.size} total")}    " +
                    "${ConsoleFormatter.hint("Players:")} ${ConsoleFormatter.colorize("$totalPlayers", ConsoleFormatter.BOLD)}"
        )

        // Per-group overview
        if (groups.isEmpty()) {
            println(ConsoleFormatter.emptyState("No groups configured."))
        } else {
            val headers = listOf("GROUP", "TYPE", "INSTANCES", "MIN/MAX", "PLAYERS", "STATUS")
            val rows = groups.sortedBy { it.name }.map { group ->
                val services = registry.getByGroup(group.name)
                val running = services.count {
                    it.state == ServiceState.READY || it.state == ServiceState.STARTING
                }
                val players = services.sumOf { it.playerCount }
                val crashed = services.count { it.state == ServiceState.CRASHED }

                val statusText = when {
                    crashed > 0 -> ConsoleFormatter.error("$crashed crashed")
                    running == 0 -> ConsoleFormatter.warn("idle")
                    running >= group.maxInstances -> ConsoleFormatter.warn("at max")
                    else -> ConsoleFormatter.success("healthy")
                }

                listOf(
                    ConsoleFormatter.colorize(group.name, ConsoleFormatter.BOLD),
                    if (group.isStatic) "STATIC" else "DYNAMIC",
                    "$running/${services.size}",
                    "${group.minInstances}/${group.maxInstances}",
                    players.toString(),
                    statusText
                )
            }

            println(ConsoleFormatter.formatTable(headers, rows))
        }

        // Capacity bar
        val maxServices = config.controller.maxServices
        val usedSlots = allServices.size
        println("${ConsoleFormatter.hint("Capacity:")} ${ConsoleFormatter.progressBar(usedSlots, maxServices)} $usedSlots/$maxServices services")

        // Cluster info (if enabled)
        if (nodeManager != null) {
            println()
            println("${ConsoleFormatter.hint("Cluster:")} ${ConsoleFormatter.success("${nodeManager.getOnlineNodeCount()}")} online / ${nodeManager.getNodeCount()} nodes")
        }

        // Load Balancer info (if enabled)
        if (loadBalancer != null) {
            println("${ConsoleFormatter.hint("Load Balancer:")} ${loadBalancer.activeConnections} active / ${loadBalancer.totalConnections} total connections")
        }

        // Bedrock info (if enabled)
        if (config.bedrock.enabled) {
            println("${ConsoleFormatter.hint("Bedrock:")} ${ConsoleFormatter.success("enabled")} (Geyser + Floodgate, base port ${config.bedrock.basePort})")
        }
    }
}
