package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.cluster.NodeManager
import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.service.ServiceRegistry

class NodesCommand(
    private val nodeManager: NodeManager,
    private val registry: ServiceRegistry
) : Command {
    override val name = "nodes"
    override val description = "Show connected cluster nodes"
    override val usage = "nodes [node-name]"

    override suspend fun execute(args: List<String>) {
        val nodes = nodeManager.getAllNodes()
        if (nodes.isEmpty()) {
            println(ConsoleFormatter.emptyState("No nodes connected."))
            return
        }

        if (args.isNotEmpty()) {
            val node = nodeManager.getNode(args[0])
            if (node == null) {
                println(ConsoleFormatter.error("Node '${args[0]}' not found."))
                return
            }
            // Detailed view
            println(ConsoleFormatter.header("Node: ${node.nodeId}"))
            println(ConsoleFormatter.field("Host", node.host))
            println(ConsoleFormatter.field("Status", if (node.isConnected) ConsoleFormatter.success("online") else ConsoleFormatter.error("offline")))
            println(ConsoleFormatter.field("CPU", "${String.format("%.1f", node.cpuUsage * 100)}%"))
            println(ConsoleFormatter.field("Memory", "${node.memoryUsedMb}MB / ${node.memoryTotalMb}MB"))
            println(ConsoleFormatter.field("Services", "${node.currentServices} / ${node.maxServices}"))
            println(ConsoleFormatter.field("Version", node.agentVersion))
            println(ConsoleFormatter.field("OS", "${node.os} ${node.arch}"))
            val nodeServices = registry.getAll().filter { it.nodeId == node.nodeId }
            if (nodeServices.isNotEmpty()) {
                println(ConsoleFormatter.field("Running", nodeServices.joinToString(", ") { it.name }))
            }
            return
        }

        println(ConsoleFormatter.header("Cluster Nodes"))
        val headers = listOf("NODE", "HOST", "STATUS", "CPU", "MEMORY", "SERVICES")
        val rows = nodes.sortedBy { it.nodeId }.map { node ->
            listOf(
                ConsoleFormatter.colorize(node.nodeId, ConsoleFormatter.BOLD),
                node.host,
                if (node.isConnected) ConsoleFormatter.success("online") else ConsoleFormatter.error("offline"),
                "${String.format("%.0f", node.cpuUsage * 100)}%",
                "${node.memoryUsedMb}/${node.memoryTotalMb}MB",
                "${node.currentServices}/${node.maxServices}"
            )
        }
        println(ConsoleFormatter.formatTable(headers, rows))
        println(ConsoleFormatter.hint("${nodeManager.getOnlineNodeCount()}/${nodeManager.getNodeCount()} online"))
    }
}
