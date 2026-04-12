package dev.nimbuspowered.nimbus.agent

import dev.nimbuspowered.nimbus.protocol.ClusterMessage
import dev.nimbuspowered.nimbus.protocol.ServiceHeartbeat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.seconds

class LocalProcessManager(
    private val baseDir: Path,
    private val scope: CoroutineScope,
    private val javaResolver: JavaResolver,
    private val stateStore: AgentStateStore,
    private val stateSyncClient: StateSyncClient? = null
) {
    private val logger = LoggerFactory.getLogger(LocalProcessManager::class.java)
    private val handles = ConcurrentHashMap<String, LocalProcessHandle>()
    private val workDirs = ConcurrentHashMap<String, Path>()
    private val staticServices = ConcurrentHashMap.newKeySet<String>()

    fun runningCount(): Int = handles.count { it.value.isAlive() }

    suspend fun startService(msg: ClusterMessage.StartService): Boolean {
        return try {
            val templatesDir = baseDir.resolve("templates")
            val servicesDir = baseDir.resolve("services")
            val resolvedTemplates = msg.templateNames.ifEmpty { listOf(msg.templateName) }

            // Work dir layout by service type:
            //   dedicated        → services/dedicated/<name>/ (stable, pulled from canonical)
            //   sync-enabled     → services/sync/<name>/      (stable, pulled/pushed)
            //   static           → services/static/<name>/    (stable, template-copied)
            //   else (dynamic)   → services/temp/<name>_<uuid>/ (ephemeral, template-copied)
            val workDir = when {
                msg.isDedicated -> servicesDir.resolve("dedicated").resolve(msg.serviceName)
                msg.syncEnabled -> servicesDir.resolve("sync").resolve(msg.serviceName)
                msg.isStatic -> servicesDir.resolve("static").resolve(msg.serviceName)
                else -> {
                    val uuid = UUID.randomUUID().toString().replace("-", "").take(8)
                    servicesDir.resolve("temp").resolve("${msg.serviceName}_$uuid")
                }
            }
            workDir.createDirectories()

            // State sync pull: if enabled, try to pull canonical state first.
            // Dedicated services MUST pull — there's no template to fall back to,
            // so if the pull returns false (empty canonical), error out.
            val pulledFromCanonical = if (msg.syncEnabled && stateSyncClient != null) {
                try {
                    stateSyncClient.pull(msg.serviceName, workDir, msg.syncExcludes)
                } catch (e: Exception) {
                    logger.error("State sync pull failed for '{}': {}", msg.serviceName, e.message)
                    false
                }
            } else false

            if (msg.isDedicated && !pulledFromCanonical) {
                logger.error(
                    "Dedicated service '{}' cannot start: controller has no canonical state. " +
                    "The initial dedicated/{}/ must exist on the controller before remote placement.",
                    msg.serviceName, msg.serviceName
                )
                return false
            }

            // Copy template stack if we didn't pull from canonical.
            // Dedicated never uses templates. For group-sync services, template copy
            // runs only on the first start ever.
            if (!pulledFromCanonical && !msg.isDedicated) {
                val primaryDir = templatesDir.resolve(resolvedTemplates.first())
                copyTemplate(primaryDir, workDir, preserveExisting = msg.isStatic || msg.syncEnabled)
                for (tmpl in resolvedTemplates.drop(1)) {
                    val overlayDir = templatesDir.resolve(tmpl)
                    if (overlayDir.exists()) {
                        copyTemplate(overlayDir, workDir, preserveExisting = false)
                    }
                }
            }

            // Patch server.properties port + online-mode
            patchServerPort(workDir, msg.port)

            // Patch Velocity forwarding config
            if (msg.software != "VELOCITY" && msg.forwardingSecret.isNotEmpty()) {
                patchForwarding(workDir, msg.forwardingMode, msg.forwardingSecret, msg.software)
            }

            // Generate Geyser config for Bedrock-enabled proxy services
            if (msg.bedrockEnabled && msg.bedrockPort > 0 && msg.software == "VELOCITY") {
                val geyserDir = workDir.resolve("plugins").resolve("Geyser-Velocity")
                if (!geyserDir.exists()) java.nio.file.Files.createDirectories(geyserDir)
                java.nio.file.Files.writeString(geyserDir.resolve("config.yml"), buildString {
                    appendLine("bedrock:")
                    appendLine("  address: 0.0.0.0")
                    appendLine("  port: ${msg.bedrockPort}")
                    appendLine("remote:")
                    appendLine("  address: 127.0.0.1")
                    appendLine("  port: ${msg.port}")
                    appendLine("  auth-type: floodgate")
                    appendLine("passthrough-motd: true")
                    appendLine("passthrough-player-counts: true")
                    appendLine("passthrough-protocol-name: true")
                })
                logger.info("Generated Geyser config: Bedrock UDP {} → Java TCP {}", msg.bedrockPort, msg.port)
            }

            // Build command
            val command = buildCommand(msg)

            val handle = LocalProcessHandle()
            if (msg.readyPattern.isNotEmpty()) {
                handle.setReadyPattern(Regex(msg.readyPattern))
            }
            handle.start(workDir, command)

            handles[msg.serviceName] = handle
            workDirs[msg.serviceName] = workDir
            if (msg.isStatic) staticServices.add(msg.serviceName)

            // Persist state for crash recovery (includes sync metadata so reconnected
            // agents know to push on stop even across restarts)
            stateStore.addService(PersistedService(
                serviceName = msg.serviceName,
                groupName = msg.groupName,
                port = msg.port,
                pid = handle.pid() ?: 0,
                workDir = workDir.toAbsolutePath().toString(),
                isStatic = msg.isStatic,
                templateName = msg.templateName,
                software = msg.software,
                memory = msg.memory,
                startedAtEpochMs = System.currentTimeMillis(),
                syncEnabled = msg.syncEnabled,
                syncExcludes = msg.syncExcludes,
                isDedicated = msg.isDedicated
            ))

            logger.info("Started service '{}' on port {}", msg.serviceName, msg.port)
            true
        } catch (e: Exception) {
            logger.error("Failed to start service '{}': {}", msg.serviceName, e.message)
            false
        }
    }

    suspend fun stopService(serviceName: String, timeoutSeconds: Int) {
        val handle = handles[serviceName] ?: return
        handle.stopGracefully(timeoutSeconds.seconds)
        handle.destroy()

        // State sync push before we forget the service's metadata
        pushStateIfEnabled(serviceName)

        handles.remove(serviceName)
        stateStore.removeService(serviceName)
    }

    suspend fun sendCommand(serviceName: String, command: String): Boolean {
        val handle = handles[serviceName] ?: return false
        // Sanitize: strip newlines to prevent command injection via stdin
        val sanitized = command.replace("\r", "").replace("\n", "")
        return try {
            handle.sendCommand(sanitized)
            true
        } catch (e: Exception) {
            logger.error("Failed to send command to '{}': {}", serviceName, e.message)
            false
        }
    }

    fun getHandle(serviceName: String): LocalProcessHandle? = handles[serviceName]

    fun getWorkDir(serviceName: String): Path? = workDirs[serviceName]

    fun getStaticServiceWorkDirs(): Map<String, Path> {
        return staticServices.mapNotNull { name ->
            workDirs[name]?.let { name to it }
        }.toMap()
    }

    /**
     * Returns all currently-running services with enough metadata for the controller
     * to rebuild its registry after a restart. Pulled from the persisted state store
     * (for groupName / port) joined with the live [handles] map (for liveness / pid).
     *
     * Called from the runtime after every (re)authentication with the controller.
     */
    fun getRunningServices(): List<RecoveredService> {
        val persisted = stateStore.load().services.associateBy { it.serviceName }
        return handles.mapNotNull { (name, handle) ->
            if (!handle.isAlive()) return@mapNotNull null
            val meta = persisted[name] ?: return@mapNotNull null
            RecoveredService(
                serviceName = name,
                groupName = meta.groupName,
                port = meta.port,
                pid = handle.pid() ?: meta.pid
            )
        }
    }

    fun getServiceHeartbeats(): List<ServiceHeartbeat> {
        return handles.map { (name, handle) ->
            val pid = handle.pid() ?: 0
            ServiceHeartbeat(
                serviceName = name,
                groupName = "",  // Agent doesn't track group names, controller knows
                state = if (handle.isAlive()) "READY" else "STOPPED",
                port = 0,
                pid = pid,
                playerCount = 0, // Agent doesn't ping, controller's scaling engine does
                memoryUsedMb = if (pid > 0) readRssMb(pid) else 0
            )
        }
    }

    private val agentIsWindows by lazy {
        System.getProperty("os.name")?.lowercase()?.contains("windows") == true
    }

    /**
     * Resident set size (RSS) for a process, in MB.
     *
     *  - Linux / WSL: reads `/proc/<pid>/status` (VmRSS field).
     *  - Windows: shells out to `tasklist /FI "PID eq <pid>" /FO CSV /NH`.
     *  - Other OSes: returns 0.
     *
     * Same two-path implementation as the controller's ProcessMemoryReader —
     * the agent module can't depend on core so it's duplicated here.
     */
    private fun readRssMb(pid: Long): Long {
        if (pid <= 0) return 0
        val procStatus = java.nio.file.Paths.get("/proc/$pid/status")
        if (java.nio.file.Files.exists(procStatus)) {
            return try {
                var rssKb = 0L
                java.nio.file.Files.lines(procStatus).use { lines ->
                    lines.filter { it.startsWith("VmRSS:") }.findFirst().ifPresent { line ->
                        val parsed = line.substringAfter("VmRSS:").trim().removeSuffix("kB").trim().toLongOrNull()
                        if (parsed != null) rssKb = parsed
                    }
                }
                rssKb / 1024
            } catch (_: Exception) {
                0
            }
        }
        if (agentIsWindows) return readRssMbFromTasklist(pid)
        return 0
    }

    private fun readRssMbFromTasklist(pid: Long): Long {
        return try {
            val process = ProcessBuilder("tasklist", "/FI", "PID eq $pid", "/FO", "CSV", "/NH")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return 0
            }
            if (output.isEmpty() || output.startsWith("INFO")) return 0
            // CSV format: "java.exe","12345","Console","1","1,234,567 K"
            val fields = output.split(Regex(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)"))
                .map { it.trim().removePrefix("\"").removeSuffix("\"") }
            val memField = fields.lastOrNull() ?: return 0
            val kb = memField.removeSuffix("K").trim().replace(",", "").replace(".", "").toLongOrNull() ?: return 0
            kb / 1024
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Deletes a cached sync workdir for a service no longer hosted on this agent.
     * Called by the controller via [ClusterMessage.DiscardSyncWorkdir] after a
     * migration or failover re-placement, so the source node doesn't hoard stale
     * state. Safe if the workdir is already gone or the service is still running
     * (in which case we bail out).
     */
    fun discardSyncWorkdir(serviceName: String) {
        if (handles.containsKey(serviceName)) {
            logger.warn("Refusing to discard workdir for '{}': service is still running", serviceName)
            return
        }
        val workDir = workDirs.remove(serviceName) ?: run {
            // Fall back to the canonical sync location convention.
            val candidate = baseDir.resolve("services").resolve("sync").resolve(serviceName)
            if (candidate.exists()) candidate else return
        }
        if (!workDir.exists()) return
        try {
            Files.walk(workDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
            logger.info("Discarded stale sync workdir for '{}' ({})", serviceName, workDir)
        } catch (e: Exception) {
            logger.warn("Failed to discard sync workdir for '{}': {}", serviceName, e.message)
        }
    }

    fun cleanup(serviceName: String, isStatic: Boolean) {
        // State sync push: fires on clean exit (player typed /stop, process died normally).
        // We push BEFORE removing state because we need the workDir + syncExcludes.
        pushStateIfEnabled(serviceName)

        handles.remove(serviceName)
        stateStore.removeService(serviceName)
        if (!isStatic) {
            val workDir = workDirs.remove(serviceName)
            if (workDir != null && workDir.exists()) {
                try {
                    Files.walk(workDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
                } catch (e: Exception) {
                    logger.warn("Failed to clean up work dir: {}", e.message)
                }
            }
        } else {
            // For static/sync services we keep the workDir as a cache for next start
            workDirs.remove(serviceName)
        }
    }

    /**
     * Pushes the current workdir back to the controller's canonical store if sync is
     * enabled for this service. Called from both explicit stop and clean-exit paths.
     * Errors are logged but don't throw — the caller shouldn't care whether push
     * succeeded.
     */
    private fun pushStateIfEnabled(serviceName: String) {
        val persisted = stateStore.load().services.firstOrNull { it.serviceName == serviceName } ?: return
        if (!persisted.syncEnabled || stateSyncClient == null) return
        val workDir = workDirs[serviceName] ?: java.nio.file.Path.of(persisted.workDir)
        if (!workDir.exists()) {
            logger.warn("State sync push '{}' skipped: workDir does not exist", serviceName)
            return
        }
        try {
            logger.info("State sync push '{}' to controller...", serviceName)
            stateSyncClient.push(serviceName, workDir, persisted.syncExcludes)
            logger.info("State sync push '{}' complete", serviceName)
        } catch (e: Exception) {
            logger.error("State sync push '{}' FAILED: {} — canonical copy is STALE on controller",
                serviceName, e.message, e)
        }
    }

    data class RecoveredService(
        val serviceName: String,
        val groupName: String,
        val port: Int,
        val pid: Long
    )

    /**
     * Attempts to recover services from persisted state after an agent restart.
     * Checks if each persisted process is still alive and adopts it if so.
     * Returns the set of work directory paths that belong to recovered services
     * (so the caller can skip cleaning them during temp-dir wipe).
     */
    fun recoverServices(): Pair<List<RecoveredService>, Set<Path>> {
        val state = stateStore.load()
        if (state.services.isEmpty()) return emptyList<RecoveredService>() to emptySet()

        logger.info("Found {} persisted service(s), attempting recovery...", state.services.size)
        val recovered = mutableListOf<RecoveredService>()
        val protectedDirs = mutableSetOf<Path>()

        for (persisted in state.services) {
            val handle = LocalProcessHandle.adopt(persisted.pid, persisted.serviceName)
            if (handle != null) {
                handles[persisted.serviceName] = handle
                val workDir = Path.of(persisted.workDir)
                workDirs[persisted.serviceName] = workDir
                if (persisted.isStatic) staticServices.add(persisted.serviceName)
                protectedDirs.add(workDir)
                recovered.add(RecoveredService(
                    serviceName = persisted.serviceName,
                    groupName = persisted.groupName,
                    port = persisted.port,
                    pid = persisted.pid
                ))
                logger.info("Recovered service '{}' (PID {})", persisted.serviceName, persisted.pid)
            } else {
                logger.info("Service '{}' (PID {}) is no longer alive — removing from state",
                    persisted.serviceName, persisted.pid)
                stateStore.removeService(persisted.serviceName)
            }
        }

        logger.info("Recovered {}/{} service(s)", recovered.size, state.services.size)
        return recovered to protectedDirs
    }

    suspend fun stopAll() {
        for ((name, handle) in handles) {
            try {
                handle.stopGracefully(30.seconds)
                handle.destroy()
            } catch (e: Exception) {
                logger.error("Error stopping '{}': {}", name, e.message)
            }
        }
        handles.clear()
        stateStore.clear()
    }

    private fun copyTemplate(source: Path, target: Path, preserveExisting: Boolean) {
        if (!source.exists()) return
        Files.walk(source).use { stream ->
            stream.forEach { src ->
                val dest = target.resolve(source.relativize(src))
                if (Files.isDirectory(src)) {
                    dest.createDirectories()
                } else if (!preserveExisting || !dest.exists()) {
                    Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun patchServerPort(workDir: Path, port: Int) {
        val props = workDir.resolve("server.properties")
        if (props.exists()) {
            var hasOnlineMode = false
            val lines = Files.readAllLines(props).map { line ->
                when {
                    line.trimStart().startsWith("server-port") -> "server-port=$port"
                    line.trimStart().startsWith("online-mode") -> { hasOnlineMode = true; "online-mode=false" }
                    else -> line
                }
            }.toMutableList()
            if (!hasOnlineMode) lines.add("online-mode=false")
            Files.write(props, lines)
        } else {
            Files.writeString(props, "server-port=$port\nonline-mode=false\n")
        }
    }

    /**
     * Patches Velocity forwarding configuration for Paper, Fabric, and Forge servers.
     */
    private fun patchForwarding(workDir: Path, mode: String, secret: String, software: String) {
        if (mode == "modern") {
            // Write forwarding.secret file
            val secretFile = workDir.resolve("forwarding.secret")
            Files.writeString(secretFile, secret)

            // Paper: patch config/paper-global.yml (1.19+) or paper.yml (older)
            val paperGlobal = workDir.resolve("config").resolve("paper-global.yml")
            val paperYml = workDir.resolve("paper.yml")

            if (paperGlobal.exists()) {
                patchPaperGlobalYml(paperGlobal, secret)
            } else if (paperYml.exists()) {
                patchPaperYml(paperYml, secret)
            } else if (software in listOf("PAPER", "PURPUR", "FOLIA")) {
                // Pre-create config/paper-global.yml for fresh servers
                workDir.resolve("config").createDirectories()
                Files.writeString(paperGlobal, """
                    |_version: 29
                    |proxies:
                    |  velocity:
                    |    enabled: true
                    |    online-mode: true
                    |    secret: $secret
                """.trimMargin() + "\n")
            }

            // Fabric: patch FabricProxy-Lite config
            if (software == "FABRIC") {
                patchFabricProxy(workDir, secret)
            }

            // Forge/NeoForge: patch proxy-compatible-forge config
            if (software in listOf("FORGE", "NEOFORGE")) {
                patchForgeProxy(workDir, secret)
            }
        } else {
            // Legacy (BungeeCord) forwarding
            val spigotYml = workDir.resolve("spigot.yml")
            if (spigotYml.exists()) {
                val lines = Files.readAllLines(spigotYml).map { line ->
                    if (line.trimStart().startsWith("bungeecord:")) "  bungeecord: true" else line
                }
                Files.write(spigotYml, lines)
            }
        }
        logger.debug("Patched forwarding config (mode={}, software={})", mode, software)
    }

    private fun patchPaperGlobalYml(file: Path, secret: String) {
        val lines = Files.readAllLines(file).toMutableList()
        for (i in lines.indices) {
            val trimmed = lines[i].trimStart()
            when {
                trimmed.startsWith("enabled:") && i > 0 && lines[i - 1].trimStart().contains("velocity") ->
                    lines[i] = lines[i].replace(Regex("enabled:.*"), "enabled: true")
                trimmed.startsWith("online-mode:") && i > 1 && lines.subList(maxOf(0, i - 3), i).any { it.contains("velocity") } ->
                    lines[i] = lines[i].replace(Regex("online-mode:.*"), "online-mode: true")
                trimmed.startsWith("secret:") && i > 0 && lines.subList(maxOf(0, i - 3), i).any { it.contains("velocity") } ->
                    lines[i] = lines[i].replace(Regex("secret:.*"), "secret: $secret")
            }
        }
        Files.write(file, lines)
    }

    private fun patchPaperYml(file: Path, secret: String) {
        val lines = Files.readAllLines(file).toMutableList()
        for (i in lines.indices) {
            val trimmed = lines[i].trimStart()
            when {
                trimmed.startsWith("enabled:") && i > 0 && lines[i - 1].contains("velocity-support") ->
                    lines[i] = lines[i].replace(Regex("enabled:.*"), "enabled: true")
                trimmed.startsWith("secret:") && i > 0 && lines.subList(maxOf(0, i - 2), i).any { it.contains("velocity-support") } ->
                    lines[i] = lines[i].replace(Regex("secret:.*"), "secret: $secret")
                trimmed.startsWith("online-mode:") && i > 0 && lines.subList(maxOf(0, i - 3), i).any { it.contains("velocity-support") } ->
                    lines[i] = lines[i].replace(Regex("online-mode:.*"), "online-mode: true")
            }
        }
        Files.write(file, lines)
    }

    private fun patchFabricProxy(workDir: Path, secret: String) {
        val configDir = workDir.resolve("config")
        val configFile = configDir.resolve("FabricProxy-Lite.toml")
        if (configFile.exists()) {
            val lines = Files.readAllLines(configFile).map { line ->
                when {
                    line.trimStart().startsWith("hackOnlineMode") -> "hackOnlineMode = true"
                    line.trimStart().startsWith("secret") -> "secret = \"$secret\""
                    else -> line
                }
            }
            Files.write(configFile, lines)
        } else {
            configDir.createDirectories()
            Files.writeString(configFile, "hackOnlineMode = true\nsecret = \"$secret\"\n")
        }
    }

    private fun patchForgeProxy(workDir: Path, secret: String) {
        val configDir = workDir.resolve("config")
        val configFile = configDir.resolve("proxy-compatible-forge.toml")
        if (configFile.exists()) {
            val lines = Files.readAllLines(configFile).map { line ->
                when {
                    line.trimStart().startsWith("enabled") -> "enabled = true"
                    line.trimStart().startsWith("secret") -> "secret = \"$secret\""
                    else -> line
                }
            }
            Files.write(configFile, lines)
        }
    }

    private suspend fun buildCommand(msg: ClusterMessage.StartService): List<String> {
        val javaBin = javaResolver.resolve(msg.javaVersion)
        if (msg.javaVersion > 0) {
            logger.info("Service '{}' using Java {} ({})", msg.serviceName, msg.javaVersion, javaBin)
        }
        val cmd = mutableListOf(javaBin, "-Xmx${msg.memory}")
        // Controller resolves Aikar's flags into jvmArgs when optimize=true
        cmd.addAll(msg.jvmArgs)
        cmd.add("-Dnimbus.service.name=${msg.serviceName}")
        cmd.add("-Dnimbus.service.group=${msg.groupName}")
        cmd.add("-Dnimbus.service.port=${msg.port}")
        if (msg.apiUrl.isNotEmpty()) {
            cmd.add("-Dnimbus.api.url=${msg.apiUrl}")
            cmd.add("-Dnimbus.api.token=${msg.apiToken}")
        }
        for ((k, v) in msg.nimbusProperties) {
            cmd.add("-D$k=$v")
        }
        if (msg.isModded) {
            cmd.add("-jar")
            cmd.add(msg.jarName.ifEmpty { "server.jar" })
            cmd.add("nogui")
        } else {
            cmd.add("-jar")
            cmd.add(msg.jarName.ifEmpty { "server.jar" })
            if (msg.software != "VELOCITY") cmd.add("--nogui")
        }
        return cmd
    }
}
