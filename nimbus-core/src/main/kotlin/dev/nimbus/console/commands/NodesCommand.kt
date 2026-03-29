package dev.nimbus.console.commands

import dev.nimbus.cluster.NodeManager
import dev.nimbus.console.Command
import dev.nimbus.console.ConsoleFormatter
import dev.nimbus.service.ServiceRegistry

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
            println(ConsoleFormatter.warn("No nodes connected."))
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
            println("  Host:       ${node.host}")
            println("  Status:     ${if (node.isConnected) ConsoleFormatter.success("online") else ConsoleFormatter.error("offline")}")
            println("  CPU:        ${String.format("%.1f", node.cpuUsage * 100)}%")
            println("  Memory:     ${node.memoryUsedMb}MB / ${node.memoryTotalMb}MB")
            println("  Services:   ${node.currentServices} / ${node.maxServices}")
            println("  Version:    ${node.agentVersion}")
            println("  OS:         ${node.os} ${node.arch}")
            val nodeServices = registry.getAll().filter { it.nodeId == node.nodeId }
            if (nodeServices.isNotEmpty()) {
                println("  Running:    ${nodeServices.joinToString(", ") { it.name }}")
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
        println(ConsoleFormatter.colorize(
            "${nodeManager.getOnlineNodeCount()}/${nodeManager.getNodeCount()} online", ConsoleFormatter.DIM))
    }
}
