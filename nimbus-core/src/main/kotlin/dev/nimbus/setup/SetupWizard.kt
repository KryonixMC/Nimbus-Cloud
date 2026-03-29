package dev.nimbus.setup

import dev.nimbus.config.ServerSoftware
import dev.nimbus.console.ConsoleFormatter
import dev.nimbus.console.ConsoleFormatter.CYAN
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
import java.security.SecureRandom
import kotlin.io.path.exists
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

    // Operating system detection
    private enum class OperatingSystem(val displayName: String) {
        LINUX("Linux"),
        MACOS("macOS"),
        WINDOWS("Windows"),
        UNKNOWN("Unknown");
    }

    private fun detectOs(): OperatingSystem {
        val osName = System.getProperty("os.name", "").lowercase()
        return when {
            "linux" in osName -> OperatingSystem.LINUX
            "mac" in osName || "darwin" in osName -> OperatingSystem.MACOS
            "win" in osName -> OperatingSystem.WINDOWS
            else -> OperatingSystem.UNKNOWN
        }
    }

    fun isSetupNeeded(): Boolean {
        val groupsDir = baseDir.resolve("config").resolve("groups")
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

            // Detect operating system
            val detectedOs = detectOs()

            // Clear screen and print the banner at the top
            w.print("\u001B[2J\u001B[H")
            w.print(ConsoleFormatter.banner(""))
            w.println("  ${ConsoleFormatter.hint("Let's get your cloud ready.")}")
            w.println("  ${ConsoleFormatter.hint("Detected OS:")} ${ConsoleFormatter.colorize(detectedOs.displayName, CYAN)}")
            w.println()
            w.flush()

            // Fetch versions in the background
            w.print("  ${ConsoleFormatter.hint("Fetching available versions...")}")
            w.flush()
            paperVersions = softwareResolver.fetchPaperVersions()
            purpurVersions = softwareResolver.fetchPurpurVersions()
            velocityVersions = softwareResolver.fetchVelocityVersions()
            w.println(" ${ConsoleFormatter.colorize("✓", ConsoleFormatter.GREEN)}")
            w.println()

            // --- Step 1: Network ---
            stepHeader(w, 1, "Network")
            val networkName = prompt(terminal, "  Network name", "MyNetwork")
            w.println()

            // --- Step 2: Proxy ---
            stepHeader(w, 2, "Proxy")
            val velocityVersion = velocityVersions?.latest ?: "3.4.0-SNAPSHOT"
            done(w, "Velocity $velocityVersion ${ConsoleFormatter.hint("(always latest — backwards compatible)")}")
            w.println()

            // --- Step 3: Server Groups ---
            stepHeader(w, 3, "Server Groups")
            w.println()

            w.println("  ${ConsoleFormatter.colorize("Choose a template:", ConsoleFormatter.BOLD)}")
            w.println("    ${CYAN}1$RESET  Standard Lobby  ${ConsoleFormatter.hint("(Proxy + Lobby)")}")
            w.println("    ${CYAN}2$RESET  Lobby + Games   ${ConsoleFormatter.hint("(Proxy + Lobby + Minigame server)")}")
            w.println("    ${CYAN}3$RESET  Custom          ${ConsoleFormatter.hint("(configure everything yourself)")}")
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
                    w.println("  ${ConsoleFormatter.hint("Setting up: Proxy + Lobby")}")
                    w.println()
                    val sw = promptSoftware(terminal)
                    val ver = promptVersion(terminal, w, sw)
                    val mem = prompt(terminal, "  Lobby memory", "1G")
                    val vias = promptViaPlugins(terminal, w, ver)
                    groups.add(GroupEntry("Lobby", sw, ver, 1, 4, mem, vias))
                    done(w, "Lobby ${ConsoleFormatter.hint("($sw $ver, $mem)")}")
                }
                "2" -> {
                    w.println()
                    w.println("  ${ConsoleFormatter.hint("Setting up: Proxy + Lobby + Game server")}")
                    w.println()

                    w.println("  ${ConsoleFormatter.colorize("Lobby:", ConsoleFormatter.BOLD)}")
                    val lobbySw = promptSoftware(terminal)
                    val lobbyVer = promptVersion(terminal, w, lobbySw)
                    val lobbyMem = prompt(terminal, "  Lobby memory", "1G")
                    val lobbyVias = promptViaPlugins(terminal, w, lobbyVer)
                    groups.add(GroupEntry("Lobby", lobbySw, lobbyVer, 1, 4, lobbyMem, lobbyVias))
                    done(w, "Lobby ${ConsoleFormatter.hint("($lobbySw $lobbyVer, $lobbyMem)")}")
                    w.println()

                    w.println("  ${ConsoleFormatter.colorize("Game server:", ConsoleFormatter.BOLD)}")
                    val gameName = prompt(terminal, "  Group name", "BedWars")
                    val gameSw = promptSoftware(terminal)
                    val gameVer = promptVersion(terminal, w, gameSw)
                    val gameMem = prompt(terminal, "  Memory per instance", "2G")
                    val gameMax = promptInt(terminal, "  Max instances", 10)
                    val gameVias = promptViaPlugins(terminal, w, gameVer)
                    groups.add(GroupEntry(gameName, gameSw, gameVer, 1, gameMax, gameMem, gameVias))
                    done(w, "$gameName ${ConsoleFormatter.hint("($gameSw $gameVer, $gameMem, max $gameMax)")}")
                }
                else -> {
                    w.println()
                    var addMore = true
                    while (addMore) {
                        val name = prompt(terminal, "  Group name", "")
                        if (name.isBlank()) {
                            w.println("  ${ConsoleFormatter.error("Name cannot be empty.")}")
                            continue
                        }
                        val sw = promptSoftware(terminal)
                        val ver = promptVersion(terminal, w, sw)
                        val min = promptInt(terminal, "  Min instances", 1)
                        val max = promptInt(terminal, "  Max instances", 4)
                        val mem = prompt(terminal, "  Memory per instance", "1G")
                        val vias = promptViaPlugins(terminal, w, ver)
                        groups.add(GroupEntry(name, sw, ver, min, max, mem, vias))
                        done(w, "$name ${ConsoleFormatter.hint("($sw $ver, $mem, $min-$max instances)")}")
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
                        w.println("  ${ConsoleFormatter.colorize("+", ConsoleFormatter.GREEN)} ${group.name} ${ConsoleFormatter.hint("(copied from ${sourceGroup.name})")}")
                    }
                } else {
                    val label = "${group.software.name.lowercase().replaceFirstChar { it.uppercase() }} ${group.version}"
                    download(w, "$label ${ConsoleFormatter.hint("(${group.name})")}") {
                        softwareResolver.ensureJarAvailable(group.software, group.version, templateDir)
                    }
                    downloaded.add(key)
                }

                // Via plugins for backend servers
                for (plugin in group.viaPlugins) {
                    download(w, "${plugin.slug} ${ConsoleFormatter.hint("(${group.name})")}") {
                        softwareResolver.downloadViaPlugin(plugin, templateDir, "PAPER")
                    }
                }
            }
            w.println()

            // --- Step 5: Write configs ---
            stepHeader(w, 5, "Saving configuration")
            w.println()

            writeNimbusToml(networkName)
            w.println("  ${ConsoleFormatter.colorize("+", ConsoleFormatter.GREEN)} config/nimbus.toml")

            writeProxyToml(velocityVersion)
            w.println("  ${ConsoleFormatter.colorize("+", ConsoleFormatter.GREEN)} config/groups/proxy.toml")

            for (group in groups) {
                writeGroupToml(group.name, group.software, group.version, group.minInstances, group.maxInstances, group.memory)
                w.println("  ${ConsoleFormatter.colorize("+", ConsoleFormatter.GREEN)} config/groups/${group.name.lowercase()}.toml")
            }

            w.println()
            w.println(ConsoleFormatter.separator(40))
            w.println("  ${ConsoleFormatter.successLine("Setup complete!")} ${ConsoleFormatter.hint("${groups.size + 1} group(s) configured.")}")
            w.println(ConsoleFormatter.separator(40))
            w.println()

            // --- Step 6: Start script ---
            if (detectedOs != OperatingSystem.UNKNOWN) {
                stepHeader(w, 6, "Start Script")
                w.println()

                if (detectedOs == OperatingSystem.WINDOWS) {
                    w.println("  ${ConsoleFormatter.hint("Create a start.bat to launch Nimbus easily.")}")
                } else {
                    w.println("  ${ConsoleFormatter.hint("Create a start.sh that runs Nimbus in a screen session.")}")
                    w.println("  ${ConsoleFormatter.hint("Detach with Ctrl+A,D — reattach with: screen -r nimbus")}")
                }
                w.println()

                if (promptYesNo(terminal, "  Create start script?", true)) {
                    writeStartScript(detectedOs)
                    val scriptName = if (detectedOs == OperatingSystem.WINDOWS) "start.bat" else "start.sh"
                    w.println("  ${ConsoleFormatter.colorize("+", ConsoleFormatter.GREEN)} $scriptName")
                    w.println()
                }
                w.println()
            }

            return promptYesNo(terminal, "  Start Nimbus now?", true)

        } catch (_: UserInterruptException) {
            terminal?.writer()?.println("\n  ${ConsoleFormatter.hint("Setup cancelled.")}")
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

        val defaultHint = if (default.isNotEmpty()) " ${ConsoleFormatter.hint("[$default]")}" else ""
        val line = reader.readLine("$label$defaultHint${ConsoleFormatter.hint(":")} ").trim()
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
            w.println("  ${ConsoleFormatter.hint("Stable: $display")}")
            if (stableVersions.size > 15) {
                w.println("  ${ConsoleFormatter.hint("        ... and ${stableVersions.size - 15} more (tab for all)")}")
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
        w.println("  ${ConsoleFormatter.colorize("Protocol support:", ConsoleFormatter.BOLD)}")
        w.println("  ${ConsoleFormatter.hint("ViaVersion allows players with newer clients to join older servers.")}")
        w.println("  ${ConsoleFormatter.hint("ViaBackwards allows players with older clients to join newer servers.")}")
        w.println("  ${ConsoleFormatter.hint("ViaRewind extends backwards support to 1.7/1.8 clients.")}")
        w.println()

        // Parse major.minor from version
        val parts = version.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 1
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 21

        // Suggest ViaVersion if not on latest
        val isLatest = (paperVersions?.latest ?: "1.21.4") == version
        if (!isLatest) {
            if (promptYesNo(terminal, "  Install ${CYAN}ViaVersion$RESET? ${ConsoleFormatter.hint("(newer clients can join)")}", true)) {
                plugins.add(ViaPlugin.VIA_VERSION)
            }
        } else {
            if (promptYesNo(terminal, "  Install ${CYAN}ViaVersion$RESET?", false)) {
                plugins.add(ViaPlugin.VIA_VERSION)
            }
        }

        // Suggest ViaBackwards
        if (promptYesNo(terminal, "  Install ${CYAN}ViaBackwards$RESET? ${ConsoleFormatter.hint("(older clients can join)")}", minor >= 17)) {
            plugins.add(ViaPlugin.VIA_BACKWARDS)

            // ViaRewind only makes sense with ViaBackwards and for 1.9+ servers
            if (minor >= 9) {
                if (promptYesNo(terminal, "  Install ${CYAN}ViaRewind$RESET? ${ConsoleFormatter.hint("(extends support to 1.7/1.8)")}", false)) {
                    plugins.add(ViaPlugin.VIA_REWIND)
                }
            }
        }

        if (plugins.isNotEmpty()) {
            done(w, "Via plugins: ${plugins.joinToString(", ") { it.slug }}")
        } else {
            w.println("  ${ConsoleFormatter.hint("No Via plugins selected.")}")
        }

        return plugins
    }

    // ── Output helpers ──────────────────────────────────────────

    private fun stepHeader(w: PrintWriter, step: Int, title: String) {
        w.println("  ${CYAN}[$step]$RESET ${ConsoleFormatter.colorize(title, ConsoleFormatter.BOLD)}")
        w.flush()
    }

    private fun done(w: PrintWriter, message: String) {
        w.println("  ${ConsoleFormatter.successLine(message)}")
        w.flush()
    }

    private suspend fun download(w: PrintWriter, label: String, action: suspend () -> Boolean) {
        w.print("  ${ConsoleFormatter.hint("↓")} $label ")
        w.flush()
        val success = action()
        if (success) {
            w.println(ConsoleFormatter.colorize("✓", ConsoleFormatter.GREEN))
        } else {
            w.println(ConsoleFormatter.colorize("✗", ConsoleFormatter.RED))
            w.println("    ${ConsoleFormatter.warn("Download failed. You can place the file manually later.")}")
        }
        w.flush()
    }

    // ── Config writers ──────────────────────────────────────────

    private fun writeNimbusToml(networkName: String) {
        val token = generateToken()
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
            |
            |[api]
            |enabled = true
            |bind = "127.0.0.1"
            |port = 8080
            |token = "$token"
            |
            |[database]
            |# Supported types: sqlite, mysql, postgresql
            |type = "sqlite"
            |# Settings below only apply to mysql/postgresql:
            |# host = "localhost"
            |# port = 3306
            |# name = "nimbus"
            |# username = ""
            |# password = ""
        """.trimMargin() + "\n"
        val configDir = baseDir.resolve("config")
        Files.createDirectories(configDir)
        Files.writeString(configDir.resolve("nimbus.toml"), content)
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun writeProxyToml(velocityVersion: String) {
        val groupsDir = baseDir.resolve("config").resolve("groups")
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
        val groupsDir = baseDir.resolve("config").resolve("groups")
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

    private fun writeStartScript(os: OperatingSystem) {
        // Find the Nimbus JAR in the current directory
        val jarPattern = "nimbus-core-*-all.jar"
        val jarFile = baseDir.listDirectoryEntries(jarPattern).firstOrNull()
        val jarName = jarFile?.fileName?.toString() ?: "nimbus-core-*-all.jar"

        when (os) {
            OperatingSystem.LINUX, OperatingSystem.MACOS -> {
                val content = """
                    |#!/bin/bash
                    |# Nimbus Cloud — Start Script
                    |# Runs Nimbus inside a screen session for easy detach/reattach.
                    |#
                    |# Usage:
                    |#   ./start.sh          — Start Nimbus in a screen session
                    |#   screen -r nimbus    — Reattach to the session
                    |#   Ctrl+A, D           — Detach from the session
                    |
                    |SESSION_NAME="nimbus"
                    |JAR_FILE="$jarName"
                    |JVM_ARGS="-Xms256M -Xmx256M --enable-native-access=ALL-UNNAMED"
                    |
                    |# Check if screen is installed
                    |if ! command -v screen &> /dev/null; then
                    |    echo "screen is not installed. Install it with:"
                    |    echo "  Debian/Ubuntu: sudo apt install screen"
                    |    echo "  RHEL/CentOS:   sudo yum install screen"
                    |    echo "  macOS:         brew install screen"
                    |    echo ""
                    |    echo "Starting without screen..."
                    |    java ${'$'}JVM_ARGS -jar "${'$'}JAR_FILE"
                    |    exit ${'$'}?
                    |fi
                    |
                    |# Check if session already exists
                    |if screen -list | grep -q "${'$'}SESSION_NAME"; then
                    |    echo "Nimbus is already running. Reattach with: screen -r ${'$'}SESSION_NAME"
                    |    exit 1
                    |fi
                    |
                    |echo "Starting Nimbus in screen session '${'$'}SESSION_NAME'..."
                    |echo "Reattach with: screen -r ${'$'}SESSION_NAME"
                    |screen -dmS "${'$'}SESSION_NAME" java ${'$'}JVM_ARGS -jar "${'$'}JAR_FILE"
                    |echo "Nimbus started."
                """.trimMargin() + "\n"

                val scriptPath = baseDir.resolve("start.sh")
                Files.writeString(scriptPath, content)
                scriptPath.toFile().setExecutable(true)
            }
            OperatingSystem.WINDOWS -> {
                val content = """
                    |@echo off
                    |:: Nimbus Cloud — Start Script
                    |title Nimbus Cloud
                    |
                    |set JAR_FILE=$jarName
                    |set JVM_ARGS=-Xms256M -Xmx256M --enable-native-access=ALL-UNNAMED
                    |
                    |echo Starting Nimbus...
                    |java %JVM_ARGS% -jar "%JAR_FILE%"
                    |
                    |echo.
                    |echo Nimbus stopped. Press any key to close.
                    |pause >nul
                """.trimMargin() + "\r\n"

                Files.writeString(baseDir.resolve("start.bat"), content)
            }
            else -> {} // UNKNOWN — should not reach here
        }
    }
}
