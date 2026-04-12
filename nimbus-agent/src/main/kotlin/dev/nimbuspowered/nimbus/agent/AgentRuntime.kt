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
    private val trustManager = TlsHelper.resolveTrustManager(config.agent).also { tm ->
        when {
            config.agent.trustedFingerprint.isNotBlank() ->
                logger.info("TLS: pinning controller cert by SHA-256 fingerprint ({}...)",
                    config.agent.trustedFingerprint.take(23))
            config.agent.truststorePath.isNotBlank() ->
                logger.info("TLS: using custom truststore at {}", config.agent.truststorePath)
            tm == null -> logger.info("TLS: using system default truststore")
        }
    }
    private val controllerRestBaseUrl: String =
        config.agent.controller.replace("ws://", "http://").replace("wss://", "https://").removeSuffix("/cluster")
    private val templateDownloader = TemplateDownloader(
        baseDir.resolve("templates"),
        controllerRestBaseUrl,
        config.agent.token,
        trustManager
    )
    private val stateSyncClient = StateSyncClient(
        controllerRestBaseUrl,
        config.agent.token,
        trustManager
    )
    private val processManager = LocalProcessManager(baseDir, scope, javaResolver, stateStore, stateSyncClient)
    private val client = run {
        val tm = trustManager
        HttpClient(CIO) {
            install(WebSockets)
            engine {
                https {
                    if (tm != null) this.trustManager = tm
                }
            }
        }
    }

    @Volatile private var running = true
    private val shutdownStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private var recoveredServices: List<LocalProcessManager.RecoveredService> = emptyList()

    suspend fun start() {
        // Validate TLS config before doing anything expensive
        if (!validateTlsConfig()) {
            running = false
            return
        }

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
        var consecutiveSslFailures = 0
        while (running) {
            var wasSslFailure = false
            try {
                connectAndRun()
                consecutiveSslFailures = 0
            } catch (e: javax.net.ssl.SSLHandshakeException) {
                wasSslFailure = true
                consecutiveSslFailures++
                logger.error("TLS handshake failed: {}", e.message)
                logger.error("  The controller cert was not trusted.")
                logger.error("  If this is a cert rotation, re-run setup: java -jar nimbus-agent.jar --setup")
                logger.error("  If the wrong fingerprint is pinned, edit agent.toml and clear 'trusted_fingerprint'.")
            } catch (e: java.security.cert.CertificateException) {
                wasSslFailure = true
                consecutiveSslFailures++
                logger.error("Certificate validation failed: {}", e.message)
                logger.error("  Re-run setup if the controller cert was rotated: java -jar nimbus-agent.jar --setup")
            } catch (e: java.net.ConnectException) {
                val url = config.agent.controller
                logger.error("Connection refused: {}", e.message ?: url)
                if ("0.0.0.0" in url) {
                    logger.error("  Hint: '0.0.0.0' is a bind address, not a connect address.")
                    logger.error("        Change 'controller' in agent.toml to the controller's real IP or hostname.")
                } else {
                    logger.error("  Hint: Is the controller running? Is the port open in the firewall?")
                }
            } catch (e: java.net.UnknownHostException) {
                logger.error("Cannot resolve host in controller URL: {}", e.message)
                logger.error("  Hint: Check that the hostname is correct and DNS is working.")
            } catch (e: Exception) {
                logger.error("Connection lost: {}", e.message ?: e::class.simpleName)
            }

            if (!running) break

            val delayMs = when {
                // SSL errors don't fix themselves — slow down and eventually give up.
                consecutiveSslFailures >= 5 -> {
                    logger.error("Too many consecutive TLS failures — aborting agent.")
                    running = false
                    break
                }
                wasSslFailure -> 30_000L
                else -> 5_000L
            }
            logger.info("Reconnecting in {}s...", delayMs / 1000)
            delay(delayMs)
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
                arch = System.getProperty("os.arch"),
                hostname = readHostname(),
                osVersion = System.getProperty("os.version", ""),
                cpuModel = readCpuModel(),
                availableProcessors = Runtime.getRuntime().availableProcessors(),
                systemMemoryTotalMb = getTotalMemoryMb(),
                javaVersion = System.getProperty("java.version", ""),
                javaVendor = System.getProperty("java.vendor", ""),
                publicHost = resolvePublicHost()
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

            // Full service sync: on every (re)auth, tell the controller exactly which
            // services are alive on this node. Covers three cases in one code path:
            //   1. Fresh agent startup with recovered services from state store.
            //   2. Reconnect to the same controller after a transient WS drop.
            //   3. Reconnect after controller restart — controller has lost its in-memory
            //      registry and needs the agent to re-seed it.
            val running = processManager.getRunningServices()
            if (running.isNotEmpty()) {
                for (svc in running) {
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
                logger.info("Synced {} running service(s) with controller", running.size)
            }
            recoveredServices = emptyList()

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
                    // Dedicated services have no template — the state-sync pull in
                    // LocalProcessManager.startService is the only data source. Skip
                    // the template download step entirely. Group-based services still
                    // need their template available even if sync is enabled (for the
                    // first start before canonical exists).
                    val templatesReady = if (msg.isDedicated) {
                        true
                    } else {
                        val templatesToDownload = msg.templateNames.ifEmpty { listOf(msg.templateName) }
                            .filter { it.isNotBlank() }
                        if (templatesToDownload.isEmpty()) {
                            true
                        } else {
                            templateDownloader.ensureTemplates(templatesToDownload, msg.templateHash, msg.software)
                        }
                    }
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

            is ClusterMessage.DiscardSyncWorkdir -> {
                scope.launch {
                    try {
                        processManager.discardSyncWorkdir(msg.serviceName)
                    } catch (e: Exception) {
                        logger.warn("Failed to discard sync workdir for '{}': {}", msg.serviceName, e.message)
                    }
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
                    processCpuLoad = getProcessCpuUsage(),
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

    private fun validateTlsConfig(): Boolean {
        val url = config.agent.controller
        if (!url.startsWith("wss://")) return true // plaintext ws:// — nothing to validate

        val hasFingerprint = config.agent.trustedFingerprint.isNotBlank()
        val hasTruststore = config.agent.truststorePath.isNotBlank()
        val skipVerify = !config.agent.tlsVerify

        if (!hasFingerprint && !hasTruststore && !skipVerify) {
            logger.error("No TLS trust material configured for wss:// controller connection.")
            logger.error("  The agent does not know which cert to trust, so connection would fail silently.")
            logger.error("  Options (pick one):")
            logger.error("    1. Run the setup wizard to pin the controller fingerprint:")
            logger.error("         java -jar nimbus-agent.jar --setup")
            logger.error("    2. Or set truststore_path/truststore_password in agent.toml")
            logger.error("    3. Or set tls_verify = false in agent.toml (DEV ONLY)")
            return false
        }
        return true
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
            (osBean as? com.sun.management.OperatingSystemMXBean)?.cpuLoad ?: -1.0
        } catch (_: Exception) { -1.0 }
    }

    private fun getProcessCpuUsage(): Double {
        return try {
            val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            (osBean as? com.sun.management.OperatingSystemMXBean)?.processCpuLoad ?: -1.0
        } catch (_: Exception) { -1.0 }
    }

    /**
     * Used system memory, in MB. NOT the agent JVM heap — that would be misleading on
     * the Nodes page where we want to see how much physical RAM is in use on the host.
     */
    private fun getUsedMemoryMb(): Long {
        return try {
            val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                as? com.sun.management.OperatingSystemMXBean ?: return 0
            val total = osBean.totalMemorySize
            val free = osBean.freeMemorySize
            ((total - free).coerceAtLeast(0)) / 1024 / 1024
        } catch (_: Exception) { 0 }
    }

    private fun getTotalMemoryMb(): Long {
        return try {
            val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            ((osBean as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize ?: 0) / 1024 / 1024
        } catch (_: Exception) { 0 }
    }

    /**
     * Picks the best reachable IPv4 address for the controller's proxy to use when
     * routing players to backends on this node. Prefers the operator's explicit
     * `[agent] public_host` config; falls back to the first non-loopback, non-APIPA,
     * non-link-local, site-local or global IPv4 address from any up interface.
     *
     * Without this, the controller used `call.request.local.remoteAddress` which on
     * Windows often picks a Hyper-V vEthernet / Tailscale / APIPA (169.254.x.x)
     * address and breaks backend routing for real multi-node deployments.
     */
    private fun resolvePublicHost(): String {
        val configured = config.agent.publicHost.trim()
        if (configured.isNotEmpty()) return configured

        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return ""
            val candidates = mutableListOf<java.net.Inet4Address>()
            for (iface in interfaces.toList()) {
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                val name = iface.displayName.lowercase()
                // Skip virtual/tunnel interfaces that are unlikely to be reachable.
                if ("hyper-v" in name || "vethernet" in name || "vmware" in name ||
                    "virtualbox" in name || "tailscale" in name || "wsl" in name) continue
                for (addr in iface.inetAddresses.toList()) {
                    if (addr !is java.net.Inet4Address) continue
                    if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress) continue
                    candidates += addr
                }
            }
            // Prefer site-local (192.168/10/172.16-31) or global addresses over anything else.
            candidates.firstOrNull { it.isSiteLocalAddress }?.hostAddress
                ?: candidates.firstOrNull()?.hostAddress
                ?: ""
        } catch (e: Exception) {
            logger.debug("Failed to auto-resolve public host: {}", e.message)
            ""
        }
    }

    private fun readHostname(): String {
        return try {
            java.net.InetAddress.getLocalHost().hostName
        } catch (_: Exception) {
            System.getenv("HOSTNAME") ?: System.getenv("COMPUTERNAME") ?: "unknown"
        }
    }

    private fun readCpuModel(): String {
        val cpuInfo = java.nio.file.Path.of("/proc/cpuinfo")
        if (java.nio.file.Files.exists(cpuInfo)) {
            try {
                val line = java.nio.file.Files.lines(cpuInfo).use { stream ->
                    stream.filter { it.startsWith("model name") }.findFirst().orElse(null)
                }
                if (line != null) {
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2) return parts[1].trim()
                }
            } catch (_: Exception) { /* fall through */ }
        }
        return System.getenv("PROCESSOR_IDENTIFIER")
            ?: System.getProperty("os.arch", "unknown")
    }
}
