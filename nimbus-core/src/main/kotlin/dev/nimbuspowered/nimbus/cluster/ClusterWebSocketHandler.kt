package dev.nimbuspowered.nimbus.cluster

import dev.nimbuspowered.nimbus.api.NimbusApi
import dev.nimbuspowered.nimbus.config.ClusterConfig
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.service.PortAllocator
import dev.nimbuspowered.nimbus.service.Service
import dev.nimbuspowered.nimbus.protocol.ClusterMessage
import dev.nimbuspowered.nimbus.protocol.clusterJson
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceState
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import org.slf4j.LoggerFactory

class ClusterWebSocketHandler(
    private val config: ClusterConfig,
    private val nodeManager: NodeManager,
    private val registry: ServiceRegistry,
    private val eventBus: EventBus,
    private val portAllocator: PortAllocator? = null,
    private val groupManager: GroupManager? = null,
    val remoteFileProxy: RemoteFileProxy = RemoteFileProxy()
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

                // Version-check BEFORE token-check so a wrong-version agent gets a precise
                // diagnostic instead of a generic "Invalid auth token" when both are off.
                val agentProto = authMsg.protocolVersion
                val ctrlProto = ClusterMessage.CURRENT_PROTOCOL_VERSION
                if (agentProto != ctrlProto) {
                    val reason = "protocol version mismatch: agent=$agentProto, controller=$ctrlProto"
                    logger.warn("Rejecting cluster handshake from '{}': {}", authMsg.nodeName, reason)
                    send(Frame.Text(clusterJson.encodeToString(ClusterMessage.serializer(),
                        ClusterMessage.AuthResponse(
                            accepted = false,
                            reason = reason,
                            protocolVersion = ctrlProto
                        ))))
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Protocol mismatch"))
                    return@webSocket
                }

                // C5 fix: reject connections when cluster token is blank
                if (config.token.isBlank() || !NimbusApi.timingSafeEquals(authMsg.token, config.token)) {
                    send(Frame.Text(clusterJson.encodeToString(ClusterMessage.serializer(),
                        ClusterMessage.AuthResponse(false, reason = "Invalid auth token"))))
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Auth failed"))
                    return@webSocket
                }

                nodeId = authMsg.nodeName
                // Prefer the agent's self-reported public_host (filtered for a routable
                // IPv4) over the socket-derived peer address — the latter often picks
                // a Hyper-V vEthernet or APIPA interface on Windows, which breaks
                // backend routing from the proxy.
                val host = authMsg.publicHost.takeIf { it.isNotBlank() }
                    ?: (call.request.local.remoteAddress ?: "unknown")

                // Check for reconnection
                val existingNode = nodeManager.getNode(nodeId)
                if (existingNode != null) {
                    // Clear stale remote handles from previous connection
                    val staleHandles = existingNode.remoteHandles.keys.toList()
                    existingNode.remoteHandles.clear()
                    if (staleHandles.isNotEmpty()) {
                        logger.info("Cleared {} stale remote handles from reconnecting node '{}'", staleHandles.size, nodeId)
                    }
                    existingNode.reconnect(this)
                    existingNode.applyAuthInfo(authMsg)
                    logger.info("Node '{}' reconnected from {}", nodeId, host)

                    // Reconcile registry against the agent's authoritative list: any
                    // service we thought was on this node but the agent doesn't claim
                    // is gone (agent restart with wiped state, orphan sweep killed it,
                    // or manual cleanup). Purge them so the slot frees up and scaling
                    // can re-place on another node.
                    val agentHas = authMsg.runningServices.toSet()
                    val stale = registry.getAll().filter { it.nodeId == nodeId && it.name !in agentHas }
                    for (svc in stale) {
                        logger.warn("Reconcile: purging '{}' — controller had it on '{}', agent doesn't", svc.name, nodeId)
                        registry.unregister(svc.name)
                        portAllocator?.release(svc.port)
                    }
                } else {
                    val connection = NodeConnection(
                        nodeId = nodeId,
                        host = host,
                        maxMemory = authMsg.maxMemory,
                        maxServices = authMsg.maxServices,
                        session = this
                    )
                    connection.applyAuthInfo(authMsg)
                    nodeManager.registerNode(connection)
                }

                send(Frame.Text(clusterJson.encodeToString(ClusterMessage.serializer(),
                    ClusterMessage.AuthResponse(
                        accepted = true,
                        nodeId = nodeId,
                        protocolVersion = ClusterMessage.CURRENT_PROTOCOL_VERSION
                    ))))

                // Message loop
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        try {
                            val msg = clusterJson.decodeFromString(ClusterMessage.serializer(), frame.readText())
                            handleAgentMessage(nodeId, msg)
                        } catch (e: kotlinx.serialization.SerializationException) {
                            logger.warn("Malformed message from node '{}': {}", nodeId, e.message)
                        } catch (e: Exception) {
                            logger.error("Error handling message from node '{}': {}", nodeId, e.message, e)
                        }
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
                        eventBus.emit(dev.nimbuspowered.nimbus.event.NimbusEvent.NodeDisconnected(nodeId))
                        // Schedule a failure check: if the node doesn't reconnect within
                        // node_timeout, its services will be marked CRASHED so the scaling
                        // engine can respawn them elsewhere.
                        nodeManager.scheduleFailureCheck(nodeId)
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
                        // Cache agent-reported memory for remote services (controller can't /proc them)
                        if (sh.memoryUsedMb > 0) {
                            service.memoryUsedMb = sh.memoryUsedMb
                        }
                    }
                }
            }
            is ClusterMessage.ServiceStateChanged -> {
                val existing = registry.get(message.serviceName)
                val newState = ServiceState.valueOf(message.state)

                // Reconcile stale terminal state: after a controller restart or transient
                // disconnect the controller may still see a service as CRASHED/STOPPED
                // while the agent actually has it running. When the agent re-syncs and
                // reports READY/STARTING, unregister the stale entry and let the
                // "unknown service" branch below re-register it fresh.
                val service = if (
                    existing != null &&
                    (existing.state == ServiceState.CRASHED || existing.state == ServiceState.STOPPED) &&
                    (newState == ServiceState.READY || newState == ServiceState.STARTING)
                ) {
                    logger.info("Reconciling ghost entry '{}': controller had {} but agent reports {}",
                        message.serviceName, existing.state, newState)
                    registry.unregister(message.serviceName)
                    null
                } else existing

                if (service != null) {
                    val actuallyTransitioned = service.transitionTo(newState)
                    service.pid = message.pid

                    // Forward to remote handle for waitForReady etc (always, even if
                    // the state machine rejected the transition — the handle still
                    // needs to unblock any waiters)
                    val handle = node.remoteHandles[message.serviceName]
                    handle?.onStateChanged(message.state, message.pid)

                    // Only emit events if the state actually changed. Prevents duplicate
                    // CRASHED events when multiple paths (heartbeat timeout, WS close,
                    // exit monitor) all notice the same failure.
                    if (actuallyTransitioned) {
                        when (newState) {
                            ServiceState.STOPPED -> eventBus.emit(
                                dev.nimbuspowered.nimbus.event.NimbusEvent.ServiceStopped(message.serviceName))
                            ServiceState.CRASHED -> eventBus.emit(
                                dev.nimbuspowered.nimbus.event.NimbusEvent.ServiceCrashed(message.serviceName, -1, 0))
                            else -> {}
                        }
                    }
                } else if (message.state == "READY" || message.state == "STARTING") {
                    val group = groupManager?.getGroup(message.groupName)
                    if (group != null) {
                        val servicesDir = java.nio.file.Path.of("services", "temp")
                        val recoveredService = Service(
                            name = message.serviceName,
                            groupName = message.groupName,
                            port = message.port,
                            host = node.host,
                            nodeId = nodeId,
                            workingDirectory = servicesDir.resolve(message.serviceName),
                            initialState = ServiceState.valueOf(message.state)
                        )
                        recoveredService.pid = message.pid
                        registry.register(recoveredService)
                        portAllocator?.reserve(message.port)
                        val remoteHandle = RemoteServiceHandle(message.serviceName, node)
                        node.remoteHandles[message.serviceName] = remoteHandle
                        logger.info("Recovered remote service '{}' from node '{}' (PID {}, port {})",
                            message.serviceName, nodeId, message.pid, message.port)
                    } else {
                        logger.warn("Ignoring recovered service '{}' — group '{}' not found", message.serviceName, message.groupName)
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
            // Remote file management responses
            is ClusterMessage.FileListResponse -> remoteFileProxy.onFileListResponse(message)
            is ClusterMessage.FileReadResponse -> remoteFileProxy.onFileReadResponse(message)
            is ClusterMessage.FileWriteResponse -> remoteFileProxy.onFileWriteResponse(message)
            is ClusterMessage.FileDeleteResponse -> remoteFileProxy.onFileDeleteResponse(message)
            else -> {
                logger.warn("Unexpected message type from node '{}': {}", nodeId, message::class.simpleName)
            }
        }
    }
}
