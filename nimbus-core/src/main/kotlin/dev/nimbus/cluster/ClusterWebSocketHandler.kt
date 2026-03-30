package dev.nimbus.cluster

import dev.nimbus.api.NimbusApi
import dev.nimbus.config.ClusterConfig
import dev.nimbus.event.EventBus
import dev.nimbus.protocol.ClusterMessage
import dev.nimbus.protocol.clusterJson
import dev.nimbus.service.ServiceRegistry
import dev.nimbus.service.ServiceState
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import org.slf4j.LoggerFactory

class ClusterWebSocketHandler(
    private val config: ClusterConfig,
    private val nodeManager: NodeManager,
    private val registry: ServiceRegistry,
    private val eventBus: EventBus
) {
    private val logger = LoggerFactory.getLogger(ClusterWebSocketHandler::class.java)

    fun Route.clusterRoutes() {
        webSocket("/cluster") {
            var nodeId: String? = null
            try {
                // First message must be AuthRequest
                val authFrame = incoming.receive() as? Frame.Text
                    ?: return@webSocket close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Expected text frame"))

                val authMsg = clusterJson.decodeFromString(ClusterMessage.serializer(), authFrame.readText())
                if (authMsg !is ClusterMessage.AuthRequest) {
                    send(Frame.Text(clusterJson.encodeToString(ClusterMessage.serializer(),
                        ClusterMessage.AuthResponse(false, reason = "First message must be AUTH_REQUEST"))))
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid handshake"))
                    return@webSocket
                }

                // Validate token
                if (!NimbusApi.timingSafeEquals(authMsg.token, config.token)) {
                    send(Frame.Text(clusterJson.encodeToString(ClusterMessage.serializer(),
                        ClusterMessage.AuthResponse(false, reason = "Invalid auth token"))))
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Auth failed"))
                    return@webSocket
                }

                nodeId = authMsg.nodeName
                val host = (call.request.local.remoteAddress ?: "unknown")

                // Check for reconnection
                val existingNode = nodeManager.getNode(nodeId)
                if (existingNode != null) {
                    existingNode.reconnect(this)
                    existingNode.agentVersion = authMsg.agentVersion
                    logger.info("Node '{}' reconnected from {}", nodeId, host)
                } else {
                    val connection = NodeConnection(
                        nodeId = nodeId,
                        host = host,
                        maxMemory = authMsg.maxMemory,
                        maxServices = authMsg.maxServices,
                        session = this
                    )
                    connection.agentVersion = authMsg.agentVersion
                    connection.os = authMsg.os
                    connection.arch = authMsg.arch
                    nodeManager.registerNode(connection)
                }

                send(Frame.Text(clusterJson.encodeToString(ClusterMessage.serializer(),
                    ClusterMessage.AuthResponse(true, nodeId = nodeId))))

                // Message loop
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val msg = clusterJson.decodeFromString(ClusterMessage.serializer(), frame.readText())
                        handleAgentMessage(nodeId, msg)
                    }
                }

            } catch (_: ClosedReceiveChannelException) {
                logger.info("Node '{}' disconnected", nodeId ?: "unknown")
            } catch (e: Exception) {
                logger.error("Error in cluster WS for node '{}': {}", nodeId ?: "unknown", e.message)
            } finally {
                if (nodeId != null) {
                    val node = nodeManager.getNode(nodeId)
                    if (node != null) {
                        node.markDisconnected()
                        // Emit disconnect event immediately (node stays registered for reconnection)
                        eventBus.emit(dev.nimbus.event.NimbusEvent.NodeDisconnected(nodeId))
                    }
                }
            }
        }
    }

    private suspend fun handleAgentMessage(nodeId: String, message: ClusterMessage) {
        val node = nodeManager.getNode(nodeId) ?: return

        when (message) {
            is ClusterMessage.HeartbeatResponse -> {
                node.updateHeartbeat(message)
                // Update state from heartbeat — but don't overwrite playerCount if SDK reported recently
                for (sh in message.services) {
                    val service = registry.get(sh.serviceName)
                    if (service != null) {
                        service.customState = sh.customState
                        // Only update playerCount from heartbeat if agent actually provides real data
                        // (agents that don't ping send 0, which would overwrite SDK-reported counts)
                        if (sh.playerCount > 0) {
                            service.playerCount = sh.playerCount
                            service.lastPlayerCountUpdate = java.time.Instant.now()
                        }
                    }
                }
            }
            is ClusterMessage.ServiceStateChanged -> {
                val service = registry.get(message.serviceName)
                if (service != null) {
                    val newState = ServiceState.valueOf(message.state)
                    service.transitionTo(newState)
                    service.pid = message.pid

                    // Forward to remote handle for waitForReady etc
                    val handle = node.remoteHandles[message.serviceName]
                    handle?.onStateChanged(message.state, message.pid)

                    // Emit events (READY is emitted by ServiceManager after waitForReady)
                    when (newState) {
                        ServiceState.STOPPED -> eventBus.emit(
                            dev.nimbus.event.NimbusEvent.ServiceStopped(message.serviceName))
                        ServiceState.CRASHED -> eventBus.emit(
                            dev.nimbus.event.NimbusEvent.ServiceCrashed(message.serviceName, -1, 0))
                        else -> {}
                    }
                }
            }
            is ClusterMessage.ServiceStdout -> {
                val handle = node.remoteHandles[message.serviceName]
                handle?.onStdoutLine(message.line)
            }
            is ClusterMessage.ServicePlayerCount -> {
                val service = registry.get(message.serviceName)
                if (service != null) {
                    service.playerCount = message.playerCount
                    service.lastPlayerCountUpdate = java.time.Instant.now()
                }
            }
            is ClusterMessage.CommandResult -> {
                if (!message.success) {
                    logger.warn("Command failed on '{}': {}", message.serviceName, message.error)
                }
            }
            is ClusterMessage.TemplateRequest -> {
                // Agent needs a template — send download URL
                // Handled by TemplateRoutes
            }
            is ClusterMessage.LogMessage -> {
                logger.info("[{}] {}: {}", nodeId, message.level, message.message)
            }
            else -> {
                logger.warn("Unexpected message type from node '{}': {}", nodeId, message::class.simpleName)
            }
        }
    }
}
