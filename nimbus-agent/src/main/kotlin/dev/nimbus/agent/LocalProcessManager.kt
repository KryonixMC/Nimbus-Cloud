package dev.nimbus.agent

import dev.nimbus.protocol.ClusterMessage
import dev.nimbus.protocol.ServiceHeartbeat
import kotlinx.coroutines.CoroutineScope
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
    private val scope: CoroutineScope
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
            val templateDir = templatesDir.resolve(msg.templateName)

            val workDir = if (msg.isStatic) {
                servicesDir.resolve("static").resolve(msg.serviceName)
            } else {
                val uuid = UUID.randomUUID().toString().replace("-", "").take(8)
                servicesDir.resolve("temp").resolve("${msg.serviceName}_$uuid")
            }
            workDir.createDirectories()

            // Copy template to work dir
            copyTemplate(templateDir, workDir, msg.isStatic)

            // Patch server.properties port + online-mode
            patchServerPort(workDir, msg.port)

            // Patch Velocity forwarding config
            if (msg.software != "VELOCITY" && msg.forwardingSecret.isNotEmpty()) {
                patchForwarding(workDir, msg.forwardingMode, msg.forwardingSecret, msg.software)
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
        handles.remove(serviceName)
    }

    suspend fun sendCommand(serviceName: String, command: String): Boolean {
        val handle = handles[serviceName] ?: return false
        return try {
            handle.sendCommand(command)
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

    fun getServiceHeartbeats(): List<ServiceHeartbeat> {
        return handles.map { (name, handle) ->
            ServiceHeartbeat(
                serviceName = name,
                groupName = "",  // Agent doesn't track group names, controller knows
                state = if (handle.isAlive()) "READY" else "STOPPED",
                port = 0,
                pid = handle.pid() ?: 0,
                playerCount = 0  // Agent doesn't ping, controller's scaling engine does
            )
        }
    }

    fun cleanup(serviceName: String, isStatic: Boolean) {
        handles.remove(serviceName)
        if (!isStatic) {
            val workDir = workDirs.remove(serviceName)
            if (workDir != null && workDir.exists()) {
                try {
                    Files.walk(workDir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
                } catch (e: Exception) {
                    logger.warn("Failed to clean up work dir: {}", e.message)
                }
            }
        }
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
            } else if (software in listOf("PAPER", "PURPUR")) {
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

    private fun buildCommand(msg: ClusterMessage.StartService): List<String> {
        val cmd = mutableListOf("java", "-Xmx${msg.memory}")
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
