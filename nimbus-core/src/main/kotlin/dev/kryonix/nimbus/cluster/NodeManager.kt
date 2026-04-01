package dev.kryonix.nimbus.cluster

import dev.kryonix.nimbus.config.ClusterConfig
import dev.kryonix.nimbus.event.EventBus
import dev.kryonix.nimbus.event.NimbusEvent
import dev.kryonix.nimbus.protocol.ClusterMessage
import dev.kryonix.nimbus.service.ServiceRegistry
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
     */
    private suspend fun handleNodeFailure(nodeId: String) {
        val affectedServices = registry.getAll().filter { it.nodeId == nodeId }
        for (service in affectedServices) {
            service.transitionTo(dev.kryonix.nimbus.service.ServiceState.CRASHED)
            eventBus.emit(NimbusEvent.ServiceCrashed(service.name, -1, service.restartCount))
        }
        unregisterNode(nodeId)
        logger.error("Node '{}' failed — {} service(s) affected", nodeId, affectedServices.size)
    }

    fun getNodeCount(): Int = nodes.size
    fun getOnlineNodeCount(): Int = getOnlineNodes().size
}
