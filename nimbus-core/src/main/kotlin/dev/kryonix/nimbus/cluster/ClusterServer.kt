package dev.kryonix.nimbus.cluster

import dev.kryonix.nimbus.api.routes.templateRoutes
import dev.kryonix.nimbus.config.ClusterConfig
import dev.kryonix.nimbus.event.EventBus
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Standalone Ktor server for the cluster WebSocket endpoint + template downloads.
 * Runs on [ClusterConfig.agentPort], separate from the REST API.
 */
class ClusterServer(
    private val config: ClusterConfig,
    private val handler: ClusterWebSocketHandler,
    private val templatesDir: Path,
    private val eventBus: EventBus,
    private val scope: CoroutineScope
) {
    private val logger = LoggerFactory.getLogger(ClusterServer::class.java)

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    val isRunning: Boolean get() = server != null

    fun start() {
        if (server != null) {
            logger.warn("Cluster server is already running")
            return
        }

        try {
            server = embeddedServer(CIO, port = config.agentPort, host = config.bind) {
                install(WebSockets) {
                    pingPeriod = kotlin.time.Duration.parse("15s")
                    timeout = kotlin.time.Duration.parse("30s")
                    maxFrameSize = 65536
                }

                install(ContentNegotiation) {
                    json(Json { encodeDefaults = true })
                }

                routing {
                    with(handler) { clusterRoutes() }
                    // Template download/hash endpoints for agents (auth via ?token= query param)
                    templateRoutes(templatesDir, config.token)
                }
            }

            server?.start(wait = false)
            logger.info("Cluster WebSocket server started on {}:{}", config.bind, config.agentPort)
        } catch (e: Exception) {
            logger.error("Failed to start cluster server on {}:{}: {}", config.bind, config.agentPort, e.message)
            server = null
        }
    }

    fun stop() {
        server?.stop(1000, 5000)
        server = null
        logger.info("Cluster WebSocket server stopped")
    }
}
