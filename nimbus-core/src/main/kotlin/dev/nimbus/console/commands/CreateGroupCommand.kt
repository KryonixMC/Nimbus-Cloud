package dev.nimbus.console.commands

import dev.nimbus.config.ConfigLoader
import dev.nimbus.config.ServerSoftware
import dev.nimbus.console.Command
import dev.nimbus.console.ConsoleFormatter.BOLD
import dev.nimbus.console.ConsoleFormatter.CYAN
import dev.nimbus.console.ConsoleFormatter.DIM
import dev.nimbus.console.ConsoleFormatter.GREEN
import dev.nimbus.console.ConsoleFormatter.RED
import dev.nimbus.console.ConsoleFormatter.RESET
import dev.nimbus.console.ConsoleFormatter.YELLOW
import dev.nimbus.group.GroupManager
import dev.nimbus.service.ServiceManager
import dev.nimbus.template.SoftwareResolver
import dev.nimbus.template.SoftwareResolver.ViaPlugin
import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import java.nio.file.Files
import java.nio.file.Path

class CreateGroupCommand(
    private val terminal: Terminal,
    private val groupManager: GroupManager,
    private val serviceManager: ServiceManager,
    private val softwareResolver: SoftwareResolver,
    private val groupsDir: Path,
    private val templatesDir: Path
) : Command {

    override val name = "create"
    override val description = "Create a new server group"
    override val usage = "create"

    override suspend fun execute(args: List<String>) {
        val w = terminal.writer()
        try {
            w.println()
            w.println("  ${BOLD}Create New Group$RESET")
            w.println()

            // Step 1: Group name
            val groupName = promptGroupName(w)
            if (groupName == null) return

            // Step 2: Software
            val software = promptSoftware()

            // Step 3: Version
            w.println()
            w.print("  ${DIM}Fetching available versions...$RESET")
            w.flush()
            val versions = when (software) {
                ServerSoftware.PAPER -> softwareResolver.fetchPaperVersions()
                ServerSoftware.PURPUR -> softwareResolver.fetchPurpurVersions()
                else -> softwareResolver.fetchPaperVersions()
            }
            w.println(" ${GREEN}✓$RESET")

            val stableVersions = versions.stable
            val snapshotVersions = versions.snapshots
            val defaultVersion = versions.latest ?: "1.21.4"

            if (stableVersions.isNotEmpty()) {
                val display = stableVersions.take(15).joinToString("  ")
                w.println("  ${DIM}Stable: $display$RESET")
                if (stableVersions.size > 15) {
                    w.println("  ${DIM}        ... and ${stableVersions.size - 15} more (tab for all)$RESET")
                }
            }
            if (snapshotVersions.isNotEmpty()) {
                val display = snapshotVersions.take(5).joinToString("  ")
                w.println("  ${YELLOW}Nightly: $display$RESET")
            }

            val allCandidates = stableVersions + snapshotVersions
            val version = prompt("  Version", defaultVersion, candidates = allCandidates)

            // Step 4: Min instances
            val minInstances = promptInt("  Min instances", 1)

            // Step 5: Max instances
            val maxInstances = promptInt("  Max instances", 4)

            // Step 6: Memory
            val memory = prompt("  Memory per instance", "1G")

            // Step 7: Via plugins
            val viaPlugins = promptViaPlugins(w, version, versions.latest)

            w.println()
            w.println("  ${BOLD}Downloading files...$RESET")
            w.println()

            // Step 8: Download server JAR
            val templateDir = templatesDir.resolve(groupName.lowercase())
            download(w, "${software.name.lowercase().replaceFirstChar { it.uppercase() }} $version") {
                softwareResolver.ensureJarAvailable(software, version, templateDir)
            }

            // Step 9: Download Via plugins
            for (plugin in viaPlugins) {
                download(w, plugin.slug) {
                    softwareResolver.downloadViaPlugin(plugin, templateDir, "PAPER")
                }
            }

            // Step 10: Write group TOML
            w.println()
            writeGroupToml(groupName, software, version, minInstances, maxInstances, memory)
            w.println("  ${GREEN}✓$RESET groups/${groupName.lowercase()}.toml")

            // Step 11: Reload groups
            val configs = ConfigLoader.loadGroupConfigs(groupsDir)
            groupManager.reloadGroups(configs)
            w.println("  ${GREEN}✓$RESET Group configs reloaded")

            w.println()
            w.println("  ${GREEN}${BOLD}Group '$groupName' created!$RESET")
            w.println()

            // Step 12: Ask to start an instance
            if (promptYesNo("  Start an instance now?", true)) {
                w.println()
                try {
                    serviceManager.startService(groupName)
                    w.println("  ${GREEN}✓$RESET Service start initiated for '$groupName'.")
                } catch (e: Exception) {
                    w.println("  ${RED}✗$RESET Failed to start service: ${e.message}")
                }
            }

            w.println()
            w.flush()

        } catch (_: UserInterruptException) {
            w.println()
            w.println("  ${DIM}Cancelled.$RESET")
            w.flush()
        }
    }

    // -- Prompt helpers -------------------------------------------------------

    private fun prompt(label: String, default: String, candidates: List<String> = emptyList()): String {
        val completer = if (candidates.isNotEmpty()) {
            Completer { _, _, list -> candidates.forEach { list.add(Candidate(it)) } }
        } else null

        val reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .let { if (completer != null) it.completer(completer) else it }
            .build()

        val defaultHint = if (default.isNotEmpty()) " ${DIM}[$default]${RESET}" else ""
        val line = reader.readLine("$label$defaultHint${DIM}:$RESET ").trim()
        return line.ifEmpty { default }
    }

    private fun promptYesNo(label: String, default: Boolean): Boolean {
        val hint = if (default) "Y/n" else "y/N"
        val answer = prompt(label, hint, candidates = listOf("y", "n"))
        return when (answer.lowercase()) {
            "y", "yes" -> true
            "n", "no" -> false
            else -> default
        }
    }

    private fun promptInt(label: String, default: Int): Int {
        val answer = prompt(label, default.toString())
        return answer.toIntOrNull() ?: default
    }

    private fun promptGroupName(w: java.io.PrintWriter): String? {
        while (true) {
            val name = prompt("  Group name", "")
            if (name.isBlank()) {
                w.println("  ${RED}Name cannot be empty.$RESET")
                w.flush()
                continue
            }
            if (groupManager.getGroup(name) != null) {
                w.println("  ${RED}Group '$name' already exists.$RESET")
                w.flush()
                continue
            }
            return name
        }
    }

    private fun promptSoftware(): ServerSoftware {
        val answer = prompt("  Server software", "paper",
            candidates = listOf("paper", "purpur"))
        return when (answer.lowercase()) {
            "purpur" -> ServerSoftware.PURPUR
            else -> ServerSoftware.PAPER
        }
    }

    private fun promptViaPlugins(
        w: java.io.PrintWriter,
        version: String,
        latestVersion: String?
    ): List<ViaPlugin> {
        val plugins = mutableListOf<ViaPlugin>()

        w.println()
        w.println("  ${BOLD}Protocol support:$RESET")
        w.println("  ${DIM}ViaVersion allows players with newer clients to join older servers.$RESET")
        w.println("  ${DIM}ViaBackwards allows players with older clients to join newer servers.$RESET")
        w.println("  ${DIM}ViaRewind extends backwards support to 1.7/1.8 clients.$RESET")
        w.println()

        val parts = version.split(".")
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 21

        val isLatest = (latestVersion ?: "1.21.4") == version
        if (!isLatest) {
            if (promptYesNo("  Install ${CYAN}ViaVersion$RESET? ${DIM}(newer clients can join)$RESET", true)) {
                plugins.add(ViaPlugin.VIA_VERSION)
            }
        } else {
            if (promptYesNo("  Install ${CYAN}ViaVersion$RESET?", false)) {
                plugins.add(ViaPlugin.VIA_VERSION)
            }
        }

        if (promptYesNo("  Install ${CYAN}ViaBackwards$RESET? ${DIM}(older clients can join)$RESET", minor >= 17)) {
            plugins.add(ViaPlugin.VIA_BACKWARDS)

            if (minor >= 9) {
                if (promptYesNo("  Install ${CYAN}ViaRewind$RESET? ${DIM}(extends support to 1.7/1.8)$RESET", false)) {
                    plugins.add(ViaPlugin.VIA_REWIND)
                }
            }
        }

        if (plugins.isNotEmpty()) {
            w.println("  ${GREEN}✓$RESET Via plugins: ${plugins.joinToString(", ") { it.slug }}")
        } else {
            w.println("  ${DIM}No Via plugins selected.$RESET")
        }
        w.flush()

        return plugins
    }

    // -- Download helper ------------------------------------------------------

    private suspend fun download(w: java.io.PrintWriter, label: String, action: suspend () -> Boolean) {
        w.print("  ${DIM}↓$RESET $label ")
        w.flush()
        val success = action()
        if (success) {
            w.println("${GREEN}✓$RESET")
        } else {
            w.println("${RED}✗$RESET")
            w.println("    ${YELLOW}Download failed. You can place the file manually later.$RESET")
        }
        w.flush()
    }

    // -- Config writer --------------------------------------------------------

    private fun writeGroupToml(
        name: String,
        software: ServerSoftware,
        version: String,
        minInstances: Int,
        maxInstances: Int,
        memory: String
    ) {
        Files.createDirectories(groupsDir)
        val templateName = name.lowercase()
        val isLobby = name.contains("lobby", ignoreCase = true)

        val content = """
            |[group]
            |name = "$name"
            |type = "DYNAMIC"
            |template = "$templateName"
            |software = "${software.name}"
            |version = "$version"
            |
            |[group.resources]
            |memory = "$memory"
            |max_players = ${if (isLobby) 50 else 16}
            |
            |[group.scaling]
            |min_instances = $minInstances
            |max_instances = $maxInstances
            |players_per_instance = ${if (isLobby) 40 else 16}
            |scale_threshold = 0.8
            |idle_timeout = ${if (isLobby) 0 else 300}
            |
            |[group.lifecycle]
            |stop_on_empty = ${!isLobby}
            |restart_on_crash = true
            |max_restarts = 5
            |
            |[group.jvm]
            |args = ["-XX:+UseG1GC", "-XX:MaxGCPauseMillis=50"]
        """.trimMargin() + "\n"
        Files.writeString(groupsDir.resolve("$templateName.toml"), content)
    }
}
