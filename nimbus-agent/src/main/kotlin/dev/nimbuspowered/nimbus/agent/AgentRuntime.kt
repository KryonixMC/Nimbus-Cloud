package dev.nimbuspowered.nimbus.agent

import dev.nimbuspowered.nimbus.protocol.ClusterMessage
import dev.nimbuspowered.nimbus.protocol.ServiceHeartbeat
import dev.nimbuspowered.nimbus.protocol.clusterJson
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
    private val stateStore = AgentStateStore(baseDir)
    private val processManager = LocalProcessManager(baseDir, scope, javaResolver, stateStore)
    private val templateDownloader = TemplateDownloader(
        baseDir.resolve("templates"),
        config.agent.controller.replace("ws://", "http://").replace("wss://", "https://").removeSuffix("/cluster"),
        config.agent.token
    )
    private val client = HttpClient(CIO) {
        install(WebSockets)
        engine {
            https {
                if (!config.agent.tlsVerify) {
                    // Dev mode: trust all certificates (self-signed)
                    trustManager = TlsHelper.trustAllManager()
                    logger.warn("TLS verification disabled — accepting all certificates (dev only)")
                } else if (config.agent.truststorePath.isNotBlank()) {
                    // Custom trust store
                    val ts = TlsHelper.loadTrustStore(config.agent.truststorePath, config.agent.truststorePassword)
                    trustManager = TlsHelper.trustManagerFor(ts)
                }
                // else: use system default trust store
            }
        }
    }

    @Volatile private var running = true
    private val shutdownStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private var recoveredServices: List<LocalProcessManager.RecoveredService> = emptyList()

    suspend fun start() {
        // Ensure directories
        val templatesDir = baseDir.resolve("templates")
        val servicesDir = baseDir.resolve("services")
        val tempDir = servicesDir.resolve("temp")
        listOf(templatesDir, servicesDir, tempDir).forEach {
            if (!it.exists()) it.createDirectories()
        }

        // Recover persisted services before cleaning temp
        val (recovered, protectedDirs) = processManager.recoverServices()
        recoveredServices = recovered

        // Clean temp, but skip directories of recovered services
        if (tempDir.exists()) {
            tempDir.toFile().listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.toPath().toAbsolutePath() !in protectedDirs) {
                    dir.deleteRecursively()
                }
            }
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

            // Notify controller about recovered services
            if (recoveredServices.isNotEmpty()) {
                for (svc in recoveredServices) {
                    send(Frame.Text(clusterJson.encodeToString(ClusterMessage.serializer(),
                        ClusterMessage.ServiceStateChanged(
                            serviceName = svc.serviceName,
                            groupName = svc.groupName,
                            state = "READY",
                            port = svc.port,
                            pid = svc.pid
                        )
                    )))
                }
                logger.info("Notified controller about {} recovered service(s)", recoveredServices.size)
                recoveredServices = emptyList()
            }

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
                    // Ensure all templates in the stack are available
                    val templatesToDownload = msg.templateNames.ifEmpty { listOf(msg.templateName) }
                    val templatesReady = templateDownloader.ensureTemplates(templatesToDownload, msg.templateHash, msg.software)
                    if (!templatesReady) {
                        logger.error("Cannot start '{}': template download failed", msg.serviceName)
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

            // Remote file management
            is ClusterMessage.FileListRequest -> {
                scope.launch {
                    val response = handleFileList(msg)
                    session.send(Frame.Text(clusterJson.encodeToString(ClusterMessage.serializer(), response)))
                }
            }
            is ClusterMessage.FileReadRequest -> {
                scope.launch {
                    val response = handleFileRead(msg)
                    session.send(Frame.Text(clusterJson.encodeToString(ClusterMessage.serializer(), response)))
                }
            }
            is ClusterMessage.FileWriteRequest -> {
                scope.launch {
                    val response = handleFileWrite(msg)
                    session.send(Frame.Text(clusterJson.encodeToString(ClusterMessage.serializer(), response)))
                }
            }
            is ClusterMessage.FileDeleteRequest -> {
                scope.launch {
                    val response = handleFileDelete(msg)
                    session.send(Frame.Text(clusterJson.encodeToString(ClusterMessage.serializer(), response)))
                }
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

    // ── Remote File Management ────────────────────────

    private fun resolveServiceFilePath(serviceName: String, path: String): java.nio.file.Path? {
        val workDir = processManager.getWorkDir(serviceName) ?: return null
        if (path.contains("..")) return null
        val resolved = workDir.resolve(path).normalize()
        if (!resolved.startsWith(workDir)) return null
        return resolved
    }

    private fun handleFileList(msg: ClusterMessage.FileListRequest): ClusterMessage.FileListResponse {
        val resolved = resolveServiceFilePath(msg.serviceName, msg.path)
            ?: return ClusterMessage.FileListResponse(msg.requestId, error = "Invalid path or service not found")

        if (!resolved.exists()) {
            return ClusterMessage.FileListResponse(msg.requestId, error = "Path not found")
        }
        if (!java.nio.file.Files.isDirectory(resolved)) {
            return ClusterMessage.FileListResponse(msg.requestId, error = "Not a directory")
        }

        val entries = java.nio.file.Files.list(resolved).use { stream ->
            stream.map { entry ->
                dev.nimbuspowered.nimbus.protocol.RemoteFileEntry(
                    name = entry.fileName.toString(),
                    path = processManager.getWorkDir(msg.serviceName)!!.relativize(entry).toString().replace('\\', '/'),
                    isDirectory = java.nio.file.Files.isDirectory(entry),
                    size = if (java.nio.file.Files.isRegularFile(entry)) java.nio.file.Files.size(entry) else 0,
                    lastModified = java.nio.file.Files.getLastModifiedTime(entry).toInstant().toString()
                )
            }.toList()
        }
        return ClusterMessage.FileListResponse(msg.requestId, entries)
    }

    private fun handleFileRead(msg: ClusterMessage.FileReadRequest): ClusterMessage.FileReadResponse {
        val resolved = resolveServiceFilePath(msg.serviceName, msg.path)
            ?: return ClusterMessage.FileReadResponse(msg.requestId, error = "Invalid path or service not found")

        if (!resolved.exists()) {
            return ClusterMessage.FileReadResponse(msg.requestId, error = "File not found")
        }
        if (java.nio.file.Files.isDirectory(resolved)) {
            return ClusterMessage.FileReadResponse(msg.requestId, error = "Cannot read directory")
        }

        return try {
            val content = java.nio.file.Files.readString(resolved)
            ClusterMessage.FileReadResponse(msg.requestId, content, java.nio.file.Files.size(resolved))
        } catch (e: Exception) {
            ClusterMessage.FileReadResponse(msg.requestId, error = "Failed to read file: ${e.message}")
        }
    }

    private fun handleFileWrite(msg: ClusterMessage.FileWriteRequest): ClusterMessage.FileWriteResponse {
        val resolved = resolveServiceFilePath(msg.serviceName, msg.path)
            ?: return ClusterMessage.FileWriteResponse(msg.requestId, false, "Invalid path or service not found")

        return try {
            resolved.parent?.let { java.nio.file.Files.createDirectories(it) }
            java.nio.file.Files.writeString(resolved, msg.content)
            ClusterMessage.FileWriteResponse(msg.requestId, true)
        } catch (e: Exception) {
            ClusterMessage.FileWriteResponse(msg.requestId, false, "Failed to write file: ${e.message}")
        }
    }

    private fun handleFileDelete(msg: ClusterMessage.FileDeleteRequest): ClusterMessage.FileDeleteResponse {
        val resolved = resolveServiceFilePath(msg.serviceName, msg.path)
            ?: return ClusterMessage.FileDeleteResponse(msg.requestId, false, "Invalid path or service not found")

        if (!resolved.exists()) {
            return ClusterMessage.FileDeleteResponse(msg.requestId, false, "Path not found")
        }

        return try {
            if (java.nio.file.Files.isDirectory(resolved)) {
                resolved.toFile().deleteRecursively()
            } else {
                java.nio.file.Files.delete(resolved)
            }
            ClusterMessage.FileDeleteResponse(msg.requestId, true)
        } catch (e: Exception) {
            ClusterMessage.FileDeleteResponse(msg.requestId, false, "Failed to delete: ${e.message}")
        }
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
