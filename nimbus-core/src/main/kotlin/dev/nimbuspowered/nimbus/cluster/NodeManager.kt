package dev.nimbuspowered.nimbus.cluster

import dev.nimbuspowered.nimbus.config.ClusterConfig
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.protocol.ClusterMessage
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class NodeManager(
    private val config: ClusterConfig,
    private val registry: ServiceRegistry,
    private val eventBus: EventBus,
    private val scope: CoroutineScope
) {
    private val logger = LoggerFactory.getLogger(NodeManager::class.java)
    private val nodes = ConcurrentHashMap<String, NodeConnection>()

    /**
     * Late-bound failover hook. Invoked after a node is marked failed with the list
     * of service names that were running on it. ServiceManager wires this to
     * handle re-placement of restart-on-crash services on surviving nodes.
     */
    var onNodeFailover: (suspend (nodeId: String, affectedServiceNames: List<String>) -> Unit) = { _, _ -> }

    val placementStrategy: PlacementStrategy = when (config.placementStrategy.lowercase()) {
        "least-memory" -> LeastMemoryPlacement()
        "round-robin" -> RoundRobinPlacement()
        else -> LeastServicesPlacement()
    }

    fun registerNode(connection: NodeConnection) {
        nodes[connection.nodeId] = connection
        logger.info("Node '{}' registered (maxMem={}, maxServices={})",
            connection.nodeId, connection.maxMemory, connection.maxServices)
        scope.launch {
            eventBus.emit(NimbusEvent.NodeConnected(connection.nodeId, connection.host))
        }
    }

    fun unregisterNode(nodeId: String) {
        nodes.remove(nodeId)
        logger.warn("Node '{}' unregistered", nodeId)
        scope.launch {
            eventBus.emit(NimbusEvent.NodeDisconnected(nodeId))
        }
    }

    fun getNode(nodeId: String): NodeConnection? = nodes[nodeId]

    fun getAllNodes(): List<NodeConnection> = nodes.values.toList()

    fun getOnlineNodes(): List<NodeConnection> = nodes.values.filter { it.isConnected }

    /**
     * Selects the best node for placing a new service.
     * Returns null if no suitable node is available (all at capacity or offline).
     */
    fun selectNode(memoryRequired: String): NodeConnection? {
        val available = getOnlineNodes().filter { node ->
            node.currentServices < node.maxServices &&
                node.hasMemoryFor(memoryRequired)
        }
        if (available.isEmpty()) return null
        return placementStrategy.select(available)
    }

    /**
     * Starts the heartbeat loop. Sends heartbeat requests to all nodes
     * and marks nodes as dead if they don't respond within nodeTimeout.
     */
    fun startHeartbeatLoop(): Job = scope.launch {
        while (isActive) {
            delay(config.heartbeatInterval)
            val now = System.currentTimeMillis()
            for (node in getAllNodes()) {
                if (!node.isConnected) continue

                // Check if node has timed out
                if (now - node.lastHeartbeat > config.nodeTimeout) {
                    logger.warn("Node '{}' timed out (last heartbeat: {}ms ago)",
                        node.nodeId, now - node.lastHeartbeat)
                    node.markDisconnected()
                    handleNodeFailure(node.nodeId)
                    continue
                }

                try {
                    node.send(ClusterMessage.HeartbeatRequest(now))
                } catch (e: Exception) {
                    logger.warn("Failed to send heartbeat to '{}': {}", node.nodeId, e.message)
                }
            }
        }
    }

    /**
     * Handles a node failure. Services on the failed node are marked CRASHED
     * and the scaling engine will restart them (possibly on different nodes).
     * Idempotent — safe to call multiple times for the same node.
     */
    private suspend fun handleNodeFailure(nodeId: String) {
        // Idempotency guard: if the node was already removed, nothing to do.
        if (nodes[nodeId] == null) return
        val affectedServices = registry.getAll().filter { it.nodeId == nodeId }
        val affectedNames = affectedServices.map { it.name }
        for (service in affectedServices) {
            if (service.state != dev.nimbuspowered.nimbus.service.ServiceState.CRASHED) {
                service.transitionTo(dev.nimbuspowered.nimbus.service.ServiceState.CRASHED)
                eventBus.emit(NimbusEvent.ServiceCrashed(service.name, -1, service.restartCount))
            }
        }
        unregisterNode(nodeId)
        logger.error("Node '{}' failed — {} service(s) affected", nodeId, affectedServices.size)
        // Hand off to ServiceManager for re-placement on surviving nodes.
        try {
            onNodeFailover(nodeId, affectedNames)
        } catch (e: Exception) {
            logger.error("Failover handler for node '{}' threw", nodeId, e)
        }
    }

    /**
     * Called from the WS close handler when a node's WebSocket drops (clean or error).
     * Schedules a delayed failure check: if the node hasn't reconnected within
     * [ClusterConfig.nodeTimeout] ms, its services are marked CRASHED.
     *
     * This complements the heartbeat-timeout path in [startHeartbeatLoop] for the
     * case where an agent process is killed hard — the WS closes but no heartbeat
     * timeout is observed because the loop skips disconnected nodes.
     */
    fun scheduleFailureCheck(nodeId: String) {
        scope.launch {
            delay(config.nodeTimeout)
            val node = nodes[nodeId]
            if (node != null && !node.isConnected) {
                logger.warn("Node '{}' did not reconnect within {}ms — marking services CRASHED",
                    nodeId, config.nodeTimeout)
                handleNodeFailure(nodeId)
            }
        }
    }

    fun getNodeCount(): Int = nodes.size
    fun getOnlineNodeCount(): Int = getOnlineNodes().size
}
