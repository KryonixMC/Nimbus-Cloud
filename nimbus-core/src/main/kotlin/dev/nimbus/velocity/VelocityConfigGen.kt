package dev.nimbus.velocity

import dev.nimbus.config.ServerSoftware
import dev.nimbus.group.GroupManager
import dev.nimbus.service.ServiceRegistry
import dev.nimbus.service.ServiceState
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class VelocityConfigGen(
    private val registry: ServiceRegistry,
    private val groupManager: GroupManager
) {
    private val logger = LoggerFactory.getLogger(VelocityConfigGen::class.java)

    fun regenerateServerList(velocityWorkDir: Path) {
        val configFile = velocityWorkDir.resolve("velocity.toml")
        if (!configFile.exists()) {
            logger.warn("velocity.toml not found at {}", configFile)
            return
        }

        val content = configFile.readText()

        // Find all READY backend services (non-VELOCITY)
        val backendServices = registry.getAll()
            .filter { service ->
                service.state == ServiceState.READY &&
                    groupManager.getGroup(service.groupName)
                        ?.config?.group?.software != ServerSoftware.VELOCITY
            }
            .sortedBy { it.name }

        val serverEntries = backendServices.joinToString("\n") { service ->
            """${service.name} = "${service.host}:${service.port}""""
        }

        // Try list — lobby servers preferred
        val lobbyServices = backendServices.filter { it.groupName.contains("lobby", ignoreCase = true) }
        val tryServices = lobbyServices.ifEmpty {
            val firstGroup = backendServices.firstOrNull()?.groupName
            if (firstGroup != null) backendServices.filter { it.groupName == firstGroup } else emptyList()
        }
        val tryList = tryServices.joinToString(", ") { "\"${it.name}\"" }

        // Build the new [servers] block
        val newServersBlock = buildString {
            appendLine("[servers]")
            if (serverEntries.isNotEmpty()) appendLine(serverEntries)
            appendLine("try = [$tryList]")
        }

        // Build the new [forced-hosts] block (empty, we don't use forced hosts)
        val newForcedHosts = "[forced-hosts]\n"

        // Replace [servers] section: everything from [servers] until next [section]
        var result = replaceTOMLSection(content, "servers", newServersBlock)
        result = replaceTOMLSection(result, "forced-hosts", newForcedHosts)

        configFile.writeText(result)
        logger.info("Updated velocity.toml: {} server(s), try=[{}]",
            backendServices.size, tryServices.joinToString { it.name })
    }

    /**
     * Replaces a TOML section [name] and its contents up to the next [section] header.
     */
    fun replaceTOMLSection(content: String, sectionName: String, replacement: String): String {
        val lines = content.lines().toMutableList()
        val sectionHeader = "[$sectionName]"

        var sectionStart = -1
        var sectionEnd = -1

        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (trimmed == sectionHeader) {
                sectionStart = i
            } else if (sectionStart >= 0 && sectionEnd < 0 && trimmed.startsWith("[") && trimmed.endsWith("]")) {
                sectionEnd = i
            }
        }

        if (sectionStart < 0) {
            // Section not found, append
            return content.trimEnd() + "\n\n" + replacement
        }

        if (sectionEnd < 0) sectionEnd = lines.size

        val before = lines.subList(0, sectionStart).joinToString("\n")
        val after = lines.subList(sectionEnd, lines.size).joinToString("\n")

        return before.trimEnd() + "\n\n" + replacement + "\n" + after
    }

    fun findVelocityWorkDir(): Path? {
        return registry.getAll().firstOrNull { service ->
            (service.state == ServiceState.READY || service.state == ServiceState.STARTING) &&
                groupManager.getGroup(service.groupName)
                    ?.config?.group?.software == ServerSoftware.VELOCITY
        }?.workingDirectory
    }

    fun updateProxyServerList() {
        val workDir = findVelocityWorkDir()
        if (workDir == null) {
            logger.debug("No running Velocity proxy found, skipping server list update")
            return
        }
        regenerateServerList(workDir)
    }
}
