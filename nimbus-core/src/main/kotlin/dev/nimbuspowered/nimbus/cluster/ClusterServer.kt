package dev.nimbuspowered.nimbus.cluster

import dev.nimbuspowered.nimbus.api.routes.templateRoutes
import dev.nimbuspowered.nimbus.config.ClusterConfig
import dev.nimbuspowered.nimbus.event.EventBus
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
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
 * Uses the Netty engine for native TLS support via [sslConnector].
 */
class ClusterServer(
    private val config: ClusterConfig,
    private val handler: ClusterWebSocketHandler,
    private val templatesDir: Path,
    private val eventBus: EventBus,
    private val scope: CoroutineScope
) {
    private val logger = LoggerFactory.getLogger(ClusterServer::class.java)

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    val isRunning: Boolean get() = server != null

    fun start() {
        if (server != null) {
            logger.warn("Cluster server is already running")
            return
        }

        if (config.tlsEnabled) {
            startWithTls()
        } else {
            startPlaintext()
            val isLoopback = config.bind == "127.0.0.1" || config.bind == "localhost"
            if (!isLoopback) {
                logger.warn("Cluster server is bound to '{}' without TLS — agent tokens and secrets are transmitted in plaintext!", config.bind)
                logger.warn("Enable TLS in [cluster] config for production multi-node setups.")
            }
        }
    }

    private fun startWithTls() {
        // Resolve keystore path: configured path, or default to config/cluster.jks
        val keystorePath = if (config.keystorePath.isNotBlank()) {
            Path.of(config.keystorePath)
        } else {
            Path.of("config", "cluster.jks")
        }

        val password = resolveKeystorePassword()
        val (keyStore, effectivePassword) = try {
            TlsHelper.ensureKeyStore(keystorePath, password, config.bind)
        } catch (e: Exception) {
            logger.error("Failed to load/generate keystore at '{}': {}", keystorePath, e.message)
            return
        }

        val alias = keyStore.aliases().nextElement()

        try {
            server = embeddedServer(Netty, configure = {
                sslConnector(
                    keyStore = keyStore,
                    keyAlias = alias,
                    keyStorePassword = { effectivePassword.toCharArray() },
                    privateKeyPassword = { effectivePassword.toCharArray() }
                ) {
                    host = config.bind
                    port = config.agentPort
                }
            }) {
                installPlugins()
            }

            server?.start(wait = false)
            logger.info("Cluster WebSocket server started on wss://{}:{} (TLS)", config.bind, config.agentPort)
        } catch (e: Exception) {
            logger.error("Failed to start cluster server with TLS on {}:{}: {}", config.bind, config.agentPort, e.message)
            server = null
        }
    }

    private fun startPlaintext() {
        try {
            server = embeddedServer(Netty, port = config.agentPort, host = config.bind) {
                installPlugins()
            }

            server?.start(wait = false)
            logger.info("Cluster WebSocket server started on ws://{}:{}", config.bind, config.agentPort)
        } catch (e: Exception) {
            logger.error("Failed to start cluster server on {}:{}: {}", config.bind, config.agentPort, e.message)
            server = null
        }
    }

    private fun Application.installPlugins() {
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
            templateRoutes(templatesDir, config.token)
        }
    }

    private fun resolveKeystorePassword(): String {
        return System.getenv("NIMBUS_CLUSTER_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
            ?: config.keystorePassword
    }

    fun stop() {
        server?.stop(1000, 5000)
        server = null
        logger.info("Cluster WebSocket server stopped")
    }
}
