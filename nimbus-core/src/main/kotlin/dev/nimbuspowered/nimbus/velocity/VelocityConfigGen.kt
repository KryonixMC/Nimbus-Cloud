package dev.nimbuspowered.nimbus.velocity

import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.service.ServiceState
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

    companion object {
        private val MODDED_SOFTWARE = setOf(
            ServerSoftware.FORGE,
            ServerSoftware.NEOFORGE,
            ServerSoftware.FABRIC
        )
        private const val MODDED_CONNECTION_TIMEOUT = 10000  // default 5000, increased for large mod handshakes
        private const val MODDED_READ_TIMEOUT = 60000        // default 30000, increased for large modpacks
    }

    fun regenerateServerList(velocityWorkDir: Path) {
        val configFile = velocityWorkDir.resolve("velocity.toml")
        if (!configFile.exists()) {
            logger.warn("velocity.toml not found at {}", configFile)
            return
        }

        var content = configFile.readText()

        // Find all READY backend services (non-VELOCITY)
        val backendServices = registry.getAll()
            .filter { service ->
                service.state == ServiceState.READY &&
                    groupManager.getGroup(service.groupName)
                        ?.config?.group?.software != ServerSoftware.VELOCITY
            }
            .sortedBy { it.name }

        // Detect modded backends and set announce-forge accordingly
        val hasModdedBackend = backendServices.any { service ->
            val software = groupManager.getGroup(service.groupName)?.config?.group?.software
            software in MODDED_SOFTWARE
        }
        content = setModdedSettings(content, hasModdedBackend)

        val serverEntries = backendServices.joinToString("\n") { service ->
            val safeName = service.name.replace("\"", "").replace("[", "").replace("]", "")
            val safeHost = service.host.replace("\"", "")
            """$safeName = "$safeHost:${service.port}""""
        }

        // Try list — lobby servers preferred
        val lobbyServices = backendServices.filter { it.groupName.contains("lobby", ignoreCase = true) }
        val tryServices = lobbyServices.ifEmpty {
            val firstGroup = backendServices.firstOrNull()?.groupName
            if (firstGroup != null) backendServices.filter { it.groupName == firstGroup } else emptyList()
        }
        val tryList = tryServices.joinToString(", ") { "\"${it.name.replace("\"", "")}\"" }

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
        logger.info("Updated velocity.toml: {} server(s), try=[{}]{}",
            backendServices.size, tryServices.joinToString { it.name },
            if (hasModdedBackend) ", announce-forge=true" else "")
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
        return findAllVelocityWorkDirs().firstOrNull()
    }

    fun findAllVelocityWorkDirs(): List<Path> {
        return registry.getAll()
            .filter { service ->
                (service.state == ServiceState.READY || service.state == ServiceState.STARTING) &&
                    groupManager.getGroup(service.groupName)
                        ?.config?.group?.software == ServerSoftware.VELOCITY
            }
            .map { it.workingDirectory }
    }

    /**
     * Configures velocity.toml for modded client support:
     * - `announce-forge` (root level) — advertises Forge compatibility in server list ping
     * - `connection-timeout` / `read-timeout` ([advanced]) — increased for large mod lists
     */
    fun setModdedSettings(content: String, enabled: Boolean): String {
        var result = setTomlValue(content, "announce-forge", enabled.toString(), section = null)
        if (enabled) {
            result = setTomlValue(result, "connection-timeout", MODDED_CONNECTION_TIMEOUT.toString(), section = "advanced")
            result = setTomlValue(result, "read-timeout", MODDED_READ_TIMEOUT.toString(), section = "advanced")
        }
        return result
    }

    /**
     * Sets a single key=value in velocity.toml, either at root level (section=null) or within a [section].
     */
    private fun setTomlValue(content: String, key: String, value: String, section: String?): String {
        val keyRegex = Regex("""(?m)^(\s*)${Regex.escape(key)}\s*=\s*.+$""")

        if (section == null) {
            // Root level — replace if exists, otherwise insert before first section header
            return if (keyRegex.containsMatchIn(content)) {
                keyRegex.replace(content) { "${it.groupValues[1]}$key = $value" }
            } else {
                val firstSection = Regex("""(?m)^\[""")
                val match = firstSection.find(content)
                if (match != null) {
                    content.substring(0, match.range.first) + "$key = $value\n" + content.substring(match.range.first)
                } else {
                    content.trimEnd() + "\n$key = $value\n"
                }
            }
        }

        // Section-scoped key — find the section, then find/replace the key within it
        val lines = content.lines().toMutableList()
        val sectionHeader = "[$section]"
        var sectionStart = -1
        var sectionEnd = lines.size

        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (trimmed == sectionHeader) {
                sectionStart = i
            } else if (sectionStart >= 0 && trimmed.startsWith("[") && trimmed.endsWith("]")) {
                sectionEnd = i
                break
            }
        }

        if (sectionStart >= 0) {
            // Find key within section
            for (i in (sectionStart + 1) until sectionEnd) {
                if (keyRegex.matches(lines[i])) {
                    lines[i] = "$key = $value"
                    return lines.joinToString("\n")
                }
            }
            // Key not found in section — insert after header
            lines.add(sectionStart + 1, "$key = $value")
            return lines.joinToString("\n")
        }

        // Section not found — append section + key
        return content.trimEnd() + "\n\n[$section]\n$key = $value\n"
    }

    fun updateProxyServerList() {
        val workDirs = findAllVelocityWorkDirs()
        if (workDirs.isEmpty()) {
            logger.debug("No running Velocity proxy found, skipping server list update")
            return
        }
        for (workDir in workDirs) {
            regenerateServerList(workDir)
        }
    }
}
