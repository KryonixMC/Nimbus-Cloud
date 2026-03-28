package dev.nimbus.setup

import dev.nimbus.config.ServerSoftware
import dev.nimbus.console.ConsoleFormatter
import dev.nimbus.console.ConsoleFormatter.BOLD
import dev.nimbus.console.ConsoleFormatter.CYAN
import dev.nimbus.console.ConsoleFormatter.DIM
import dev.nimbus.console.ConsoleFormatter.GRAY
import dev.nimbus.console.ConsoleFormatter.GREEN
import dev.nimbus.console.ConsoleFormatter.RED
import dev.nimbus.console.ConsoleFormatter.RESET
import dev.nimbus.console.ConsoleFormatter.YELLOW
import dev.nimbus.template.SoftwareResolver
import dev.nimbus.template.SoftwareResolver.ViaPlugin
import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.slf4j.LoggerFactory
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

class SetupWizard(
    private val baseDir: Path,
    private val softwareResolver: SoftwareResolver
) {
    private val logger = LoggerFactory.getLogger(SetupWizard::class.java)

    // Cached version lists (fetched once)
    private var paperVersions: SoftwareResolver.VersionList? = null
    private var purpurVersions: SoftwareResolver.VersionList? = null
    private var velocityVersions: SoftwareResolver.VersionList? = null

    fun isSetupNeeded(): Boolean {
        val groupsDir = baseDir.resolve("groups")
        if (!groupsDir.exists() || !groupsDir.isDirectory()) return true
        return groupsDir.listDirectoryEntries("*.toml").isEmpty()
    }

    suspend fun run(): Boolean {
        var terminal: Terminal? = null
        try {
            terminal = TerminalBuilder.builder()
                .system(true)
                .jansi(true)
                .build()

            val w = terminal.writer()

            // Print the banner
            w.print(ConsoleFormatter.banner(""))
            w.println("  ${DIM}Let's get your cloud ready.$RESET")
            w.println()
            w.flush()

            // Fetch versions in the background
            w.print("  ${DIM}Fetching available versions...$RESET")
            w.flush()
            paperVersions = softwareResolver.fetchPaperVersions()
            purpurVersions = softwareResolver.fetchPurpurVersions()
            velocityVersions = softwareResolver.fetchVelocityVersions()
            w.println(" ${GREEN}✓$RESET")
            w.println()

            // --- Step 1: Network ---
            stepHeader(w, 1, "Network")
            val networkName = prompt(terminal, "  Network name", "MyNetwork")
            w.println()

            // --- Step 2: Proxy ---
            stepHeader(w, 2, "Proxy")
            val defaultVelocity = velocityVersions?.latest ?: "3.4.0-SNAPSHOT"
            val velocityCandidates = velocityVersions?.all ?: listOf(defaultVelocity)
            val velocityVersion = prompt(terminal, "  Velocity version", defaultVelocity,
                candidates = velocityCandidates)
            done(w, "Proxy configured ${DIM}(Velocity $velocityVersion)$RESET")
            w.println()

            // --- Step 3: Server Groups ---
            stepHeader(w, 3, "Server Groups")
            w.println()

            w.println("  ${BOLD}Choose a template:$RESET")
            w.println("    ${CYAN}1$RESET  Standard Lobby  ${DIM}(Proxy + Lobby)$RESET")
            w.println("    ${CYAN}2$RESET  Lobby + Games   ${DIM}(Proxy + Lobby + Minigame server)$RESET")
            w.println("    ${CYAN}3$RESET  Custom          ${DIM}(configure everything yourself)$RESET")
            w.println()

            val templateChoice = prompt(terminal, "  Template", "1",
                candidates = listOf("1", "2", "3"))

            data class GroupEntry(
                val name: String,
                val software: ServerSoftware,
                val version: String,
                val minInstances: Int,
                val maxInstances: Int,
                val memory: String,
                val viaPlugins: List<ViaPlugin>
            )

            val groups = mutableListOf<GroupEntry>()

            when (templateChoice) {
                "1" -> {
                    w.println()
                    w.println("  ${DIM}Setting up: Proxy + Lobby$RESET")
                    w.println()
                    val sw = promptSoftware(terminal)
                    val ver = promptVersion(terminal, w, sw)
                    val mem = prompt(terminal, "  Lobby memory", "1G")
                    val vias = promptViaPlugins(terminal, w, ver)
                    groups.add(GroupEntry("Lobby", sw, ver, 1, 4, mem, vias))
                    done(w, "Lobby ${DIM}($sw $ver, $mem)$RESET")
                }
                "2" -> {
                    w.println()
                    w.println("  ${DIM}Setting up: Proxy + Lobby + Game server$RESET")
                    w.println()

                    w.println("  ${BOLD}Lobby:$RESET")
                    val lobbySw = promptSoftware(terminal)
                    val lobbyVer = promptVersion(terminal, w, lobbySw)
                    val lobbyMem = prompt(terminal, "  Lobby memory", "1G")
                    val lobbyVias = promptViaPlugins(terminal, w, lobbyVer)
                    groups.add(GroupEntry("Lobby", lobbySw, lobbyVer, 1, 4, lobbyMem, lobbyVias))
                    done(w, "Lobby ${DIM}($lobbySw $lobbyVer, $lobbyMem)$RESET")
                    w.println()

                    w.println("  ${BOLD}Game server:$RESET")
                    val gameName = prompt(terminal, "  Group name", "BedWars")
                    val gameSw = promptSoftware(terminal)
                    val gameVer = promptVersion(terminal, w, gameSw)
                    val gameMem = prompt(terminal, "  Memory per instance", "2G")
                    val gameMax = promptInt(terminal, "  Max instances", 10)
                    val gameVias = promptViaPlugins(terminal, w, gameVer)
                    groups.add(GroupEntry(gameName, gameSw, gameVer, 1, gameMax, gameMem, gameVias))
                    done(w, "$gameName ${DIM}($gameSw $gameVer, $gameMem, max $gameMax)$RESET")
                }
                else -> {
                    w.println()
                    var addMore = true
                    while (addMore) {
                        val name = prompt(terminal, "  Group name", "")
                        if (name.isBlank()) {
                            w.println("  ${RED}Name cannot be empty.$RESET")
                            continue
                        }
                        val sw = promptSoftware(terminal)
                        val ver = promptVersion(terminal, w, sw)
                        val min = promptInt(terminal, "  Min instances", 1)
                        val max = promptInt(terminal, "  Max instances", 4)
                        val mem = prompt(terminal, "  Memory per instance", "1G")
                        val vias = promptViaPlugins(terminal, w, ver)
                        groups.add(GroupEntry(name, sw, ver, min, max, mem, vias))
                        done(w, "$name ${DIM}($sw $ver, $mem, $min-$max instances)$RESET")
                        w.println()
                        addMore = promptYesNo(terminal, "  Add another group?", false)
                    }
                }
            }
            w.println()

            // --- Step 4: Download ---
            stepHeader(w, 4, "Downloading")
            w.println()

            // Proxy
            download(w, "Velocity $velocityVersion") {
                val dir = baseDir.resolve("templates/proxy")
                softwareResolver.ensureJarAvailable(ServerSoftware.VELOCITY, velocityVersion, dir)
            }

            // Game servers (Via plugins go on backend servers only, NOT on the proxy)
            val downloaded = mutableSetOf<Pair<ServerSoftware, String>>()
            for (group in groups) {
                val key = group.software to group.version
                val templateDir = baseDir.resolve("templates/${group.name.lowercase()}")

                if (key in downloaded) {
                    val sourceGroup = groups.first { (it.software to it.version) == key && it.name != group.name }
                    val sourceJar = baseDir.resolve("templates/${sourceGroup.name.lowercase()}/${softwareResolver.jarFileName(group.software)}")
                    if (sourceJar.exists()) {
                        Files.createDirectories(templateDir)
                        Files.copy(sourceJar, templateDir.resolve(softwareResolver.jarFileName(group.software)))
                        w.println("  ${GREEN}+$RESET ${group.name} ${DIM}(copied from ${sourceGroup.name})$RESET")
                    }
                } else {
                    val label = "${group.software.name.lowercase().replaceFirstChar { it.uppercase() }} ${group.version}"
                    download(w, "$label ${DIM}(${group.name})$RESET") {
                        softwareResolver.ensureJarAvailable(group.software, group.version, templateDir)
                    }
                    downloaded.add(key)
                }

                // Via plugins for backend servers
                for (plugin in group.viaPlugins) {
                    download(w, "${plugin.slug} ${DIM}(${group.name})$RESET") {
                        softwareResolver.downloadViaPlugin(plugin, templateDir, "PAPER")
                    }
                }
            }
            w.println()

            // --- Step 5: Write configs ---
            stepHeader(w, 5, "Saving configuration")
            w.println()

            writeNimbusToml(networkName)
            w.println("  ${GREEN}+$RESET nimbus.toml")

            writeProxyToml(velocityVersion)
            w.println("  ${GREEN}+$RESET groups/proxy.toml")

            for (group in groups) {
                writeGroupToml(group.name, group.software, group.version, group.minInstances, group.maxInstances, group.memory)
                w.println("  ${GREEN}+$RESET groups/${group.name.lowercase()}.toml")
            }

            w.println()
            w.println(ConsoleFormatter.separator(40))
            w.println("  ${GREEN}${BOLD}Setup complete!$RESET ${DIM}${groups.size + 1} group(s) configured.$RESET")
            w.println(ConsoleFormatter.separator(40))
            w.println()

            return promptYesNo(terminal, "  Start Nimbus now?", true)

        } catch (_: UserInterruptException) {
            terminal?.writer()?.println("\n  ${DIM}Setup cancelled.$RESET")
            return false
        } catch (_: EndOfFileException) {
            return false
        } finally {
            terminal?.close()
        }
    }

    // ── Prompt helpers ──────────────────────────────────────────

    private fun prompt(terminal: Terminal, label: String, default: String, candidates: List<String> = emptyList()): String {
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

    private fun promptYesNo(terminal: Terminal, label: String, default: Boolean): Boolean {
        val hint = if (default) "Y/n" else "y/N"
        val answer = prompt(terminal, label, hint, candidates = listOf("y", "n"))
        return when (answer.lowercase()) {
            "y", "yes" -> true
            "n", "no" -> false
            else -> default
        }
    }

    private fun promptInt(terminal: Terminal, label: String, default: Int): Int {
        val answer = prompt(terminal, label, default.toString())
        return answer.toIntOrNull() ?: default
    }

    private fun promptSoftware(terminal: Terminal): ServerSoftware {
        val answer = prompt(terminal, "  Server software", "paper",
            candidates = listOf("paper", "purpur"))
        return when (answer.lowercase()) {
            "purpur" -> ServerSoftware.PURPUR
            else -> ServerSoftware.PAPER
        }
    }

    private fun promptVersion(terminal: Terminal, w: PrintWriter, software: ServerSoftware): String {
        val versions = when (software) {
            ServerSoftware.PAPER -> paperVersions
            ServerSoftware.PURPUR -> purpurVersions
            ServerSoftware.VELOCITY -> velocityVersions
            else -> paperVersions // Modded servers use their own version prompts in CreateGroupCommand
        }

        val stableVersions = versions?.stable ?: emptyList()
        val snapshotVersions = versions?.snapshots ?: emptyList()
        val defaultVersion = versions?.latest ?: "1.21.4"

        // Show available versions grouped
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
        return prompt(terminal, "  Version", defaultVersion, candidates = allCandidates)
    }

    private fun promptViaPlugins(terminal: Terminal, w: PrintWriter, version: String): List<ViaPlugin> {
        val plugins = mutableListOf<ViaPlugin>()

        w.println()
        w.println("  ${BOLD}Protocol support:$RESET")
        w.println("  ${DIM}ViaVersion allows players with newer clients to join older servers.$RESET")
        w.println("  ${DIM}ViaBackwards allows players with older clients to join newer servers.$RESET")
        w.println("  ${DIM}ViaRewind extends backwards support to 1.7/1.8 clients.$RESET")
        w.println()

        // Parse major.minor from version
        val parts = version.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 1
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 21

        // Suggest ViaVersion if not on latest
        val isLatest = (paperVersions?.latest ?: "1.21.4") == version
        if (!isLatest) {
            if (promptYesNo(terminal, "  Install ${CYAN}ViaVersion$RESET? ${DIM}(newer clients can join)$RESET", true)) {
                plugins.add(ViaPlugin.VIA_VERSION)
            }
        } else {
            if (promptYesNo(terminal, "  Install ${CYAN}ViaVersion$RESET?", false)) {
                plugins.add(ViaPlugin.VIA_VERSION)
            }
        }

        // Suggest ViaBackwards
        if (promptYesNo(terminal, "  Install ${CYAN}ViaBackwards$RESET? ${DIM}(older clients can join)$RESET", minor >= 17)) {
            plugins.add(ViaPlugin.VIA_BACKWARDS)

            // ViaRewind only makes sense with ViaBackwards and for 1.9+ servers
            if (minor >= 9) {
                if (promptYesNo(terminal, "  Install ${CYAN}ViaRewind$RESET? ${DIM}(extends support to 1.7/1.8)$RESET", false)) {
                    plugins.add(ViaPlugin.VIA_REWIND)
                }
            }
        }

        if (plugins.isNotEmpty()) {
            done(w, "Via plugins: ${plugins.joinToString(", ") { it.slug }}")
        } else {
            w.println("  ${DIM}No Via plugins selected.$RESET")
        }

        return plugins
    }

    // ── Output helpers ──────────────────────────────────────────

    private fun stepHeader(w: PrintWriter, step: Int, title: String) {
        w.println("  ${CYAN}[$step]$RESET ${BOLD}$title$RESET")
        w.flush()
    }

    private fun done(w: PrintWriter, message: String) {
        w.println("  ${GREEN}✓$RESET $message")
        w.flush()
    }

    private suspend fun download(w: PrintWriter, label: String, action: suspend () -> Boolean) {
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

    // ── Config writers ──────────────────────────────────────────

    private fun writeNimbusToml(networkName: String) {
        val content = """
            |# Nimbus — Main Configuration
            |
            |[network]
            |name = "$networkName"
            |bind = "0.0.0.0"
            |
            |[controller]
            |max_memory = "10G"
            |max_services = 20
            |heartbeat_interval = 5000
            |
            |[console]
            |colored = true
            |log_events = true
            |history_file = ".nimbus_history"
            |
            |[paths]
            |templates = "templates"
            |services = "services"
            |logs = "logs"
        """.trimMargin() + "\n"
        Files.writeString(baseDir.resolve("nimbus.toml"), content)
    }

    private fun writeProxyToml(velocityVersion: String) {
        val groupsDir = baseDir.resolve("groups")
        Files.createDirectories(groupsDir)
        val content = """
            |[group]
            |name = "Proxy"
            |type = "STATIC"
            |template = "proxy"
            |software = "VELOCITY"
            |version = "$velocityVersion"
            |
            |[group.resources]
            |memory = "512M"
            |max_players = 500
            |
            |[group.scaling]
            |min_instances = 1
            |max_instances = 1
            |
            |[group.jvm]
            |args = ["-XX:+UseG1GC", "-XX:MaxGCPauseMillis=50"]
        """.trimMargin() + "\n"
        Files.writeString(groupsDir.resolve("proxy.toml"), content)
    }

    private fun writeGroupToml(
        name: String, software: ServerSoftware, version: String,
        minInstances: Int, maxInstances: Int, memory: String
    ) {
        val groupsDir = baseDir.resolve("groups")
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
