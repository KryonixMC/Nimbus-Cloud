package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.cluster.NodeManager
import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.module.CommandOutput
import dev.nimbuspowered.nimbus.service.ServiceRegistry

class NodesCommand(
    private val nodeManager: NodeManager,
    private val registry: ServiceRegistry
) : Command {
    override val name = "nodes"
    override val description = "Show connected cluster nodes"
    override val usage = "nodes [node-name]"

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        val nodes = nodeManager.getAllNodes()
        if (nodes.isEmpty()) {
            output.info("No nodes connected.")
            return true
        }

        if (args.isNotEmpty()) {
            val node = nodeManager.getNode(args[0])
            if (node == null) {
                output.error("Node '${args[0]}' not found.")
                return true
            }
            // Detailed view
            output.header("Node: ${node.nodeId}")
            output.text(ConsoleFormatter.field("Host", node.host))
            output.text(ConsoleFormatter.field("Status", if (node.isConnected) ConsoleFormatter.success("online") else ConsoleFormatter.error("offline")))
            output.text(ConsoleFormatter.field("CPU", "${String.format("%.1f", node.cpuUsage * 100)}%"))
            output.text(ConsoleFormatter.field("Memory", "${node.memoryUsedMb}MB / ${node.memoryTotalMb}MB"))
            output.text(ConsoleFormatter.field("Services", "${node.currentServices} / ${node.maxServices}"))
            output.text(ConsoleFormatter.field("Version", node.agentVersion))
            output.text(ConsoleFormatter.field("OS", "${node.os} ${node.arch}"))
            val nodeServices = registry.getAll().filter { it.nodeId == node.nodeId }
            if (nodeServices.isNotEmpty()) {
                output.text(ConsoleFormatter.field("Running", nodeServices.joinToString(", ") { it.name }))
            }
            return true
        }

        output.header("Cluster Nodes")
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
        output.text(ConsoleFormatter.formatTable(headers, rows))
        output.info("${nodeManager.getOnlineNodeCount()}/${nodeManager.getNodeCount()} online")
        return true
    }

    override suspend fun execute(args: List<String>) {
        execute(args, ConsoleOutput())
    }
}
