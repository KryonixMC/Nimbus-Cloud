package dev.nimbuspowered.nimbus.cluster

import dev.nimbuspowered.nimbus.config.ClusterConfig
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.protocol.ClusterMessage
import dev.nimbuspowered.nimbus.protocol.clusterJson
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T4–T7 from the protocol-version handshake test plan. Drives the WS handshake
 * via ktor testApplication and asserts controller behavior on protocol-version
 * and token matrix.
 */
class ClusterWebSocketHandlerTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private fun ApplicationTestBuilder.setupCluster(token: String = "secret"): NodeManager {
        val config = ClusterConfig(token = token)
        val registry = ServiceRegistry()
        val eventBus = EventBus(scope)
        val nodeManager = NodeManager(config, registry, eventBus, scope)
        val handler = ClusterWebSocketHandler(config, nodeManager, registry, eventBus)

        install(io.ktor.server.websocket.WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(clusterJson)
        }
        routing {
            with(handler) { clusterRoutes() }
        }
        return nodeManager
    }

    private suspend fun DefaultClientWebSocketSession.sendMsg(msg: ClusterMessage) {
        send(Frame.Text(clusterJson.encodeToString(ClusterMessage.serializer(), msg)))
    }

    private suspend fun DefaultClientWebSocketSession.receiveAuthResponse(): ClusterMessage.AuthResponse {
        val frame = incoming.receive() as Frame.Text
        return clusterJson.decodeFromString(
            ClusterMessage.serializer(), frame.readText()
        ) as ClusterMessage.AuthResponse
    }

    @Test
    fun `T4 wrong agent protocolVersion rejected with mismatch reason`() = testApplication {
        val nodeManager = setupCluster(token = "secret")
        val client = createClient { install(io.ktor.client.plugins.websocket.WebSockets) }

        client.webSocket("/cluster") {
            sendMsg(
                ClusterMessage.AuthRequest(
                    token = "secret",
                    nodeName = "worker-bad",
                    maxMemory = "1G",
                    maxServices = 1,
                    protocolVersion = 99
                )
            )
            val response = receiveAuthResponse()
            assertFalse(response.accepted)
            assertTrue(
                response.reason.startsWith("protocol version mismatch: agent=99, controller="),
                "unexpected reason: ${response.reason}"
            )
            assertEquals(ClusterMessage.CURRENT_PROTOCOL_VERSION, response.protocolVersion)
        }
        // Version mismatch must not register the node.
        assertNull(nodeManager.getNode("worker-bad"))
    }

    @Test
    fun `T5 correct protocolVersion and valid token is accepted`() = testApplication {
        val nodeManager = setupCluster(token = "secret")
        val client = createClient { install(io.ktor.client.plugins.websocket.WebSockets) }

        client.webSocket("/cluster") {
            sendMsg(
                ClusterMessage.AuthRequest(
                    token = "secret",
                    nodeName = "worker-ok",
                    maxMemory = "1G",
                    maxServices = 1,
                    protocolVersion = ClusterMessage.CURRENT_PROTOCOL_VERSION
                )
            )
            val response = receiveAuthResponse()
            assertTrue(response.accepted, "reason=${response.reason}")
            assertEquals("worker-ok", response.nodeId)
            assertEquals(ClusterMessage.CURRENT_PROTOCOL_VERSION, response.protocolVersion)
            close(CloseReason(CloseReason.Codes.NORMAL, "bye"))
        }
        assertNotNull(nodeManager.getNode("worker-ok"))
    }

    @Test
    fun `T6 correct protocolVersion but wrong token rejected with token reason`() = testApplication {
        val nodeManager = setupCluster(token = "secret")
        val client = createClient { install(io.ktor.client.plugins.websocket.WebSockets) }

        client.webSocket("/cluster") {
            sendMsg(
                ClusterMessage.AuthRequest(
                    token = "WRONG",
                    nodeName = "worker-bad-token",
                    maxMemory = "1G",
                    maxServices = 1,
                    protocolVersion = ClusterMessage.CURRENT_PROTOCOL_VERSION
                )
            )
            val response = receiveAuthResponse()
            assertFalse(response.accepted)
            assertEquals("Invalid auth token", response.reason)
        }
        assertNull(nodeManager.getNode("worker-bad-token"))
    }

    @Test
    fun `T7 both wrong - version-check wins over token-check`() = testApplication {
        val nodeManager = setupCluster(token = "secret")
        val client = createClient { install(io.ktor.client.plugins.websocket.WebSockets) }

        client.webSocket("/cluster") {
            sendMsg(
                ClusterMessage.AuthRequest(
                    token = "WRONG",
                    nodeName = "worker-both-wrong",
                    maxMemory = "1G",
                    maxServices = 1,
                    protocolVersion = 42
                )
            )
            val response = receiveAuthResponse()
            assertFalse(response.accepted)
            // Ordering is load-bearing: version-check must run first so the
            // operator sees the real reason, not a misleading token error.
            assertTrue(
                response.reason.startsWith("protocol version mismatch: agent=42, controller="),
                "expected version-mismatch reason first, got: ${response.reason}"
            )
        }
        assertNull(nodeManager.getNode("worker-both-wrong"))
    }
}
