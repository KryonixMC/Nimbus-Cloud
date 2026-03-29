package dev.nimbus.agent

import dev.nimbus.protocol.ClusterMessage
import dev.nimbus.protocol.ServiceHeartbeat
import dev.nimbus.protocol.clusterJson
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class AgentRuntime(
    private val config: AgentConfig,
    private val baseDir: Path
) {
    private val logger = LoggerFactory.getLogger(AgentRuntime::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val javaResolver = JavaResolver(config.java.toMap(), baseDir)
    private val processManager = LocalProcessManager(baseDir, scope, javaResolver)
    private val templateDownloader = TemplateDownloader(
        baseDir.resolve("templates"),
        config.agent.controller.replace("ws://", "http://").replace("wss://", "https://").removeSuffix("/cluster"),
        config.agent.token
    )
    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    @Volatile private var running = true
    private val shutdownStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun start() {
        // Ensure directories
        val templatesDir = baseDir.resolve("templates")
        val servicesDir = baseDir.resolve("services")
        val tempDir = servicesDir.resolve("temp")
        listOf(templatesDir, servicesDir, tempDir).forEach {
            if (!it.exists()) it.createDirectories()
        }

        // Clean temp
        if (tempDir.exists()) {
            tempDir.toFile().deleteRecursively()
            tempDir.createDirectories()
        }

        // Connection loop with reconnection
        while (running) {
            try {
                connectAndRun()
            } catch (e: Exception) {
                logger.error("Connection lost: {}. Reconnecting in 5s...", e.message)
            }
            if (running) delay(5000)
        }
    }

    private suspend fun connectAndRun() {
        logger.info("Connecting to controller: {}", config.agent.controller)

        client.webSocket(config.agent.controller) {
            // Send auth
            val authRequest = ClusterMessage.AuthRequest(
                token = config.agent.token,
                nodeName = config.agent.nodeName,
                maxMemory = config.agent.maxMemory,
                maxServices = config.agent.maxServices,
                currentServices = processManager.runningCount(),
                agentVersion = AgentRuntime::class.java.`package`?.implementationVersion ?: "dev",
                os = System.getProperty("os.name"),
                arch = System.getProperty("os.arch")
            )
            send(Frame.Text(clusterJson.encodeToString(ClusterMessage.serializer(), authRequest)))

            // Receive auth response
            val authFrame = incoming.receive() as? Frame.Text
                ?: throw Exception("Invalid auth response")
            val authResponse = clusterJson.decodeFromString(ClusterMessage.serializer(), authFrame.readText())
            if (authResponse is ClusterMessage.AuthResponse && !authResponse.accepted) {
                throw Exception("Auth rejected: ${authResponse.reason}")
            }
            logger.info("Authenticated with controller as '{}'", config.agent.nodeName)

            // Message loop
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val msg = clusterJson.decodeFromString(ClusterMessage.serializer(), frame.readText())
                    handleMessage(msg, this)
                }
            }
        }
    }

    private suspend fun handleMessage(msg: ClusterMessage, session: DefaultClientWebSocketSession) {
        when (msg) {
            is ClusterMessage.StartService -> {
                scope.launch {
                    // Ensure template is available
                    val templateReady = templateDownloader.ensureTemplate(msg.templateName, msg.templateHash)
                    if (!templateReady) {
                        logger.error("Cannot start '{}': template '{}' download failed", msg.serviceName, msg.templateName)
                        session.send(Frame.Text(clusterJson.encodeToString(ClusterMessage.serializer(),
                            ClusterMessage.ServiceStateChanged(
                                serviceName = msg.serviceName,
                                groupName = msg.groupName,
                                state = "CRASHED",
                                port = msg.port
                            )
                        )))
                        return@launch
                    }

                    // Start the service locally
                    val result = processManager.startService(msg)

                    // Report state back
                    session.send(Frame.Text(clusterJson.encodeToString(ClusterMessage.serializer(),
                        ClusterMessage.ServiceStateChanged(
                            serviceName = msg.serviceName,
                            groupName = msg.groupName,
                            state = if (result) "STARTING" else "CRASHED",
                            port = msg.port
                        )
                    )))

                    if (result) {
                        // Monitor process and forward stdout
                        val handle = processManager.getHandle(msg.serviceName)
                        if (handle != null) {
                            // Forward stdout
                            launch {
                                handle.stdoutLines.collect { line ->
                                    try {
                                        session.send(Frame.Text(clusterJson.encodeToString(
                                            ClusterMessage.serializer(),
                                            ClusterMessage.ServiceStdout(msg.serviceName, line)
                                        )))
                                    } catch (_: Exception) {}
                                }
                            }
                            // Monitor for ready
                            launch {
                                val ready = handle.waitForReady(
                                    kotlin.time.Duration.parse("${msg.readyTimeoutSeconds}s"))
                                session.send(Frame.Text(clusterJson.encodeToString(
                                    ClusterMessage.serializer(),
                                    ClusterMessage.ServiceStateChanged(
                                        serviceName = msg.serviceName,
                                        groupName = msg.groupName,
                                        state = if (ready) "READY" else "CRASHED",
                                        port = msg.port,
                                        pid = handle.pid() ?: 0
                                    )
                                )))
                            }
                            // Monitor exit
                            launch {
                                handle.awaitExit()
                                val exitCode = handle.exitCode() ?: -1

                                session.send(Frame.Text(clusterJson.encodeToString(
                                    ClusterMessage.serializer(),
                                    ClusterMessage.ServiceStateChanged(
                                        serviceName = msg.serviceName,
                                        groupName = msg.groupName,
                                        state = if (exitCode == 0) "STOPPED" else "CRASHED",
                                        port = msg.port
                                    )
                                )))
                                processManager.cleanup(msg.serviceName, msg.isStatic)
                            }
                        }
                    }
                }
            }

            is ClusterMessage.StopService -> {
                scope.launch {
                    processManager.stopService(msg.serviceName, msg.timeoutSeconds)
                }
            }

            is ClusterMessage.SendCommand -> {
                scope.launch {
                    val success = processManager.sendCommand(msg.serviceName, msg.command)
                    session.send(Frame.Text(clusterJson.encodeToString(ClusterMessage.serializer(),
                        ClusterMessage.CommandResult(msg.serviceName, success)
                    )))
                }
            }

            is ClusterMessage.HeartbeatRequest -> {
                val response = ClusterMessage.HeartbeatResponse(
                    timestamp = System.currentTimeMillis(),
                    cpuUsage = getSystemCpuUsage(),
                    memoryUsedMb = getUsedMemoryMb(),
                    memoryTotalMb = getTotalMemoryMb(),
                    services = processManager.getServiceHeartbeats()
                )
                session.send(Frame.Text(clusterJson.encodeToString(ClusterMessage.serializer(), response)))
            }

            is ClusterMessage.ShutdownAgent -> {
                logger.info("Shutdown requested by controller: {}", msg.reason)
                shutdown()
            }

            else -> {
                logger.warn("Unhandled message type: {}", msg::class.simpleName)
            }
        }
    }

    /**
     * Gracefully stops all running services and shuts down the agent.
     * Called from shutdown hook (Ctrl+C / SIGTERM).
     */
    suspend fun shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) return
        running = false
        logger.info("Stopping all services...")
        processManager.stopAll()
        javaResolver.close()
        client.close()
        scope.cancel()
        logger.info("Agent stopped.")
    }

    private fun getSystemCpuUsage(): Double {
        return try {
            val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            (osBean as? com.sun.management.OperatingSystemMXBean)?.cpuLoad ?: 0.0
        } catch (_: Exception) { 0.0 }
    }

    private fun getUsedMemoryMb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
    }

    private fun getTotalMemoryMb(): Long {
        return try {
            val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            ((osBean as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize ?: 0) / 1024 / 1024
        } catch (_: Exception) { 0 }
    }
}
