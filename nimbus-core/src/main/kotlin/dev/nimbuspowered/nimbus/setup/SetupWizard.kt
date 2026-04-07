package dev.nimbuspowered.nimbus.setup

import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.CYAN
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.GREEN
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.RESET
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.YELLOW
import dev.nimbuspowered.nimbus.console.InteractivePicker
import dev.nimbuspowered.nimbus.module.ModuleManager
import dev.nimbuspowered.nimbus.module.ModuleInfo
import dev.nimbuspowered.nimbus.template.SoftwareResolver
import dev.nimbuspowered.nimbus.template.SoftwareResolver.ViaPlugin
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
import java.nio.file.attribute.PosixFilePermissions
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
    private var pufferfishVersions: SoftwareResolver.VersionList? = null
    private var purpurVersions: SoftwareResolver.VersionList? = null
    private var leafVersions: SoftwareResolver.VersionList? = null
    private var foliaVersions: SoftwareResolver.VersionList? = null
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

    data class GroupEntry(
        val name: String,
        val software: ServerSoftware,
        val version: String,
        val minInstances: Int,
        val maxInstances: Int,
        val memory: String,
        val viaPlugins: List<ViaPlugin>
    )

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
            pufferfishVersions = softwareResolver.fetchPufferfishVersions()
            purpurVersions = softwareResolver.fetchPurpurVersions()
            leafVersions = softwareResolver.fetchLeafVersions()
            foliaVersions = softwareResolver.fetchFoliaVersions()
            velocityVersions = softwareResolver.fetchVelocityVersions()
            w.println(" ${ConsoleFormatter.colorize("✓", ConsoleFormatter.GREEN)}")
            w.println()

            // Mutable state for step-based navigation
            var networkName = "MyNetwork"
            var bedrockEnabled = false
            val selectedModules = mutableSetOf<String>()
            var templateChoice = "1"
            val groups = mutableListOf<GroupEntry>()

            val availableModules = discoverEmbeddedModules()
            val velocityVersion = velocityVersions?.latest ?: "3.4.0-SNAPSHOT"
            val lastStep = 6

            var step = 0
            while (step <= lastStep) {
                if (step < 0) {
                    // Back from first step = cancel wizard
                    w.println("\n  ${ConsoleFormatter.hint("Setup cancelled.")}")
                    return false
                }
                when (step) {
                    // --- Step 0: Network ---
                    0 -> {
                        stepHeader(w, 1, "Network")
                        networkName = prompt(terminal, "  Network name", networkName)
                        w.println()
                        step++
                    }

                    // --- Step 1: Proxy + Bedrock ---
                    1 -> {
                        stepHeader(w, 2, "Proxy")
                        done(w, "Velocity $velocityVersion ${ConsoleFormatter.hint("(always latest — backwards compatible)")}")
                        w.println()

                        val bedrockOptions = listOf(
                            InteractivePicker.Option("no", "No", "standard Java Edition only"),
                            InteractivePicker.Option("yes", "Yes", "Geyser + Floodgate will be auto-installed")
                        )
                        w.println("  ${ConsoleFormatter.colorize("Enable Bedrock Edition?", ConsoleFormatter.BOLD)}")
                        val bedrockIndex = InteractivePicker.pickOne(terminal, bedrockOptions, if (bedrockEnabled) 1 else 0)
                        if (bedrockIndex == InteractivePicker.BACK) {
                            step--
                            continue
                        }
                        bedrockEnabled = bedrockIndex == 1
                        if (bedrockEnabled) {
                            done(w, "Bedrock support enabled ${ConsoleFormatter.hint("(Geyser + Floodgate will be auto-installed)")}")
                        }
                        w.println()
                        step++
                    }

                    // --- Step 2: Modules ---
                    2 -> {
                        if (availableModules.isNotEmpty()) {
                            stepHeader(w, 3, "Modules")
                            w.println()

                            // Pre-select defaults if starting fresh
                            if (selectedModules.isEmpty()) {
                                availableModules.filter { it.defaultEnabled }.forEach { selectedModules.add(it.id) }
                            }

                            val moduleOptions = availableModules.map { InteractivePicker.Option(it.id, it.name, it.description) }
                            val confirmed = InteractivePicker.pickMany(terminal, moduleOptions, selectedModules)
                            if (!confirmed) {
                                step--
                                continue
                            }

                            val moduleCount = selectedModules.size
                            if (moduleCount > 0) {
                                done(w, "$moduleCount module(s) selected")
                            } else {
                                w.println("  ${ConsoleFormatter.hint("No modules selected — you can add them later with: modules install")}")
                            }
                            w.println()
                        }
                        step++
                    }

                    // --- Step 3: Server Groups (template + config) ---
                    3 -> {
                        stepHeader(w, 4, "Server Groups")
                        w.println()

                        val templateOptions = listOf(
                            InteractivePicker.Option("1", "Standard Lobby", "Proxy + Lobby"),
                            InteractivePicker.Option("2", "Lobby + Games", "Proxy + Lobby + Minigame server"),
                            InteractivePicker.Option("3", "Custom", "configure everything yourself")
                        )
                        val defaultTemplate = templateOptions.indexOfFirst { it.id == templateChoice }.coerceAtLeast(0)
                        val templateIndex = InteractivePicker.pickOne(terminal, templateOptions, defaultTemplate)
                        if (templateIndex == InteractivePicker.BACK) {
                            step--
                            continue
                        }
                        templateChoice = templateOptions[templateIndex].id
                        done(w, templateOptions[templateIndex].label)

                        groups.clear()
                        var goBack = false

                        when (templateChoice) {
                            "1" -> {
                                w.println()
                                w.println("  ${ConsoleFormatter.hint("Setting up: Proxy + Lobby")}")
                                w.println()
                                val sw = promptSoftware(terminal)
                                if (sw == null) { goBack = true } else {
                                    val ver = promptVersion(terminal, w, sw)
                                    val mem = prompt(terminal, "  Lobby memory", "1G")
                                    val vias = promptViaPlugins(terminal, w, ver)
                                    groups.add(GroupEntry("Lobby", sw, ver, 1, 4, mem, vias))
                                    done(w, "Lobby ${ConsoleFormatter.hint("($sw $ver, $mem)")}")
                                }
                            }
                            "2" -> {
                                w.println()
                                w.println("  ${ConsoleFormatter.hint("Setting up: Proxy + Lobby + Game server")}")
                                w.println()

                                w.println("  ${ConsoleFormatter.colorize("Lobby:", ConsoleFormatter.BOLD)}")
                                val lobbySw = promptSoftware(terminal)
                                if (lobbySw == null) { goBack = true } else {
                                    val lobbyVer = promptVersion(terminal, w, lobbySw)
                                    val lobbyMem = prompt(terminal, "  Lobby memory", "1G")
                                    val lobbyVias = promptViaPlugins(terminal, w, lobbyVer)
                                    groups.add(GroupEntry("Lobby", lobbySw, lobbyVer, 1, 4, lobbyMem, lobbyVias))
                                    done(w, "Lobby ${ConsoleFormatter.hint("($lobbySw $lobbyVer, $lobbyMem)")}")
                                    w.println()

                                    w.println("  ${ConsoleFormatter.colorize("Game server:", ConsoleFormatter.BOLD)}")
                                    val gameName = prompt(terminal, "  Group name", "BedWars")
                                    val gameSw = promptSoftware(terminal)
                                    if (gameSw == null) { goBack = true } else {
                                        val gameVer = promptVersion(terminal, w, gameSw)
                                        val gameMem = prompt(terminal, "  Memory per instance", "2G")
                                        val gameMax = promptInt(terminal, "  Max instances", 10)
                                        val gameVias = promptViaPlugins(terminal, w, gameVer)
                                        groups.add(GroupEntry(gameName, gameSw, gameVer, 1, gameMax, gameMem, gameVias))
                                        done(w, "$gameName ${ConsoleFormatter.hint("($gameSw $gameVer, $gameMem, max $gameMax)")}")
                                    }
                                }
                            }
                            else -> {
                                w.println()
                                var addMore = true
                                while (addMore && !goBack) {
                                    val name = prompt(terminal, "  Group name", "")
                                    if (name.isBlank()) {
                                        w.println("  ${ConsoleFormatter.error("Name cannot be empty.")}")
                                        continue
                                    }
                                    val sw = promptSoftware(terminal)
                                    if (sw == null) { goBack = true; break }
                                    val ver = promptVersion(terminal, w, sw)
                                    val min = promptInt(terminal, "  Min instances", 1)
                                    val max = promptInt(terminal, "  Max instances", 4)
                                    val mem = prompt(terminal, "  Memory per instance", "1G")
                                    val vias = promptViaPlugins(terminal, w, ver)
                                    groups.add(GroupEntry(name, sw, ver, min, max, mem, vias))
                                    done(w, "$name ${ConsoleFormatter.hint("($sw $ver, $mem, $min-$max instances)")}")
                                    w.println()
                                    val addMoreOptions = listOf(
                                        InteractivePicker.Option("add", "Add another group"),
                                        InteractivePicker.Option("done", "Done, continue setup")
                                    )
                                    val addMoreIndex = InteractivePicker.pickOne(terminal, addMoreOptions, 1)
                                    addMore = addMoreIndex == 0
                                }
                            }
                        }

                        if (goBack) {
                            groups.clear()
                            step--
                            continue
                        }
                        w.println()
                        step++
                    }

                    // --- Step 4: Download ---
                    4 -> {
                        stepHeader(w, 5, "Downloading")
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

                        // Bedrock plugins (Geyser + Floodgate)
                        if (bedrockEnabled) {
                            w.println()
                            w.println("  ${ConsoleFormatter.colorize("Bedrock:", ConsoleFormatter.BOLD)}")
                            val proxyTemplate = baseDir.resolve("templates/proxy")
                            val globalTemplate = baseDir.resolve("templates/global")
                            download(w, "Geyser ${ConsoleFormatter.hint("(Velocity plugin)")}") {
                                softwareResolver.ensureGeyserPlugin(proxyTemplate)
                            }
                            download(w, "Floodgate ${ConsoleFormatter.hint("(Velocity plugin)")}") {
                                softwareResolver.ensureFloodgatePlugin(proxyTemplate, "velocity")
                            }
                            download(w, "Floodgate ${ConsoleFormatter.hint("(backend plugin)")}") {
                                softwareResolver.ensureFloodgatePlugin(globalTemplate, "spigot")
                            }
                        }
                        w.println()
                        step++
                    }

                    // --- Step 5: Save config ---
                    5 -> {
                        stepHeader(w, 6, "Saving configuration")
                        w.println()

                        writeNimbusToml(networkName, bedrockEnabled = bedrockEnabled)
                        w.println("  ${ConsoleFormatter.colorize("+", ConsoleFormatter.GREEN)} config/nimbus.toml")

                        writeProxyToml(velocityVersion)
                        w.println("  ${ConsoleFormatter.colorize("+", ConsoleFormatter.GREEN)} config/groups/proxy.toml")

                        for (group in groups) {
                            writeGroupToml(group.name, group.software, group.version, group.minInstances, group.maxInstances, group.memory)
                            w.println("  ${ConsoleFormatter.colorize("+", ConsoleFormatter.GREEN)} config/groups/${group.name.lowercase()}.toml")
                        }

                        // Extract selected module JARs to modules/
                        if (selectedModules.isNotEmpty()) {
                            val modulesOutputDir = baseDir.resolve("modules")
                            Files.createDirectories(modulesOutputDir)
                            for (mod in availableModules) {
                                if (mod.id in selectedModules) {
                                    extractEmbeddedModule(mod.fileName, modulesOutputDir)
                                    w.println("  ${ConsoleFormatter.colorize("+", ConsoleFormatter.GREEN)} modules/${mod.fileName}")
                                }
                            }
                        }

                        w.println()
                        w.println(ConsoleFormatter.separator(40))
                        w.println("  ${ConsoleFormatter.successLine("Setup complete!")} ${ConsoleFormatter.hint("${groups.size + 1} group(s) configured.")}")
                        w.println(ConsoleFormatter.separator(40))
                        w.println()
                        step++
                    }

                    // --- Step 6: Start script ---
                    6 -> {
                        val startScriptName = if (detectedOs == OperatingSystem.WINDOWS) "start.bat" else "start.sh"
                        val startScriptExists = baseDir.resolve(startScriptName).exists()

                        if (detectedOs != OperatingSystem.UNKNOWN && !startScriptExists) {
                            stepHeader(w, 7, "Start Script")
                            w.println()

                            if (detectedOs == OperatingSystem.WINDOWS) {
                                w.println("  ${ConsoleFormatter.hint("Create a start.bat to launch Nimbus easily.")}")
                            } else {
                                w.println("  ${ConsoleFormatter.hint("Create a start.sh that runs Nimbus in a screen session.")}")
                                w.println("  ${ConsoleFormatter.hint("Detach with Ctrl+A,D — reattach with: screen -r nimbus")}")
                            }
                            w.println()

                            val scriptOptions = listOf(
                                InteractivePicker.Option("yes", "Yes, create start script"),
                                InteractivePicker.Option("no", "No, skip")
                            )
                            val scriptIndex = InteractivePicker.pickOne(terminal, scriptOptions, 0)
                            if (scriptIndex == 0) {
                                writeStartScript(detectedOs)
                                w.println("  ${ConsoleFormatter.colorize("+", ConsoleFormatter.GREEN)} $startScriptName")
                                w.println()
                            }
                            w.println()
                        }
                        step++
                    }
                }
            }

            return true

        } catch (_: UserInterruptException) {
            terminal?.writer()?.println("\n  ${ConsoleFormatter.hint("Setup cancelled.")}")
            return false
        } catch (_: EndOfFileException) {
            return false
        } finally {
            terminal?.close()
        }
    }

    // (Picker methods removed — now uses shared InteractivePicker)

    // ── Module helpers ────────────────────────────────────────

    /**
     * Discovers module JARs embedded in the application JAR under `controller-modules/`.
     * Reads the auto-generated `modules.list` index, then reads each JAR's
     * `module.properties` to get metadata without loading classes.
     */
    private fun discoverEmbeddedModules(): List<ModuleInfo> {
        // Read the build-generated index of embedded modules
        val indexResource = javaClass.classLoader.getResourceAsStream("controller-modules/modules.list")
        val resourceNames = indexResource?.bufferedReader()?.readLines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: return emptyList()

        val modules = mutableListOf<ModuleInfo>()
        for (name in resourceNames) {
            val resource = javaClass.classLoader.getResourceAsStream("controller-modules/$name") ?: continue
            try {
                // Write to temp file so we can read it as a JarFile
                val tempFile = Files.createTempFile("nimbus-module-", ".jar")
                resource.use { input -> Files.copy(input, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                val info = ModuleManager.readModuleProperties(tempFile)
                if (info != null) {
                    modules.add(info.copy(fileName = name))
                }
                Files.deleteIfExists(tempFile)
            } catch (e: Exception) {
                logger.debug("Failed to read module properties from {}: {}", name, e.message)
            }
        }

        return modules
    }

    /**
     * Extracts an embedded module JAR from application resources to the target directory.
     */
    private fun extractEmbeddedModule(fileName: String, targetDir: Path) {
        val resource = javaClass.classLoader.getResourceAsStream("controller-modules/$fileName") ?: return
        val target = targetDir.resolve(fileName)
        resource.use { input -> Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
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

    private fun promptInt(terminal: Terminal, label: String, default: Int): Int {
        val answer = prompt(terminal, label, default.toString())
        return answer.toIntOrNull() ?: default
    }

    /**
     * Prompts for server software using InteractivePicker.
     * Returns null if the user pressed ESC (back).
     */
    private fun promptSoftware(terminal: Terminal): ServerSoftware? {
        val w = terminal.writer()
        w.println("  ${ConsoleFormatter.colorize("Server software:", ConsoleFormatter.BOLD)}")
        val options = listOf(
            InteractivePicker.Option("paper", "Paper", "recommended, best plugin support"),
            InteractivePicker.Option("purpur", "Purpur", "Paper fork with extra gameplay config"),
            InteractivePicker.Option("pufferfish", "Pufferfish", "Paper fork optimized for large servers"),
            InteractivePicker.Option("leaf", "Leaf", "Paper fork, performance + stability balance"),
            InteractivePicker.Option("folia", "Folia", "Paper fork with regionized multithreading")
        )
        val index = InteractivePicker.pickOne(terminal, options)
        if (index == InteractivePicker.BACK) return null
        val chosen = options[index]
        done(w, chosen.label)
        return when (chosen.id) {
            "purpur" -> ServerSoftware.PURPUR
            "pufferfish" -> ServerSoftware.PUFFERFISH
            "leaf" -> ServerSoftware.LEAF
            "folia" -> ServerSoftware.FOLIA
            else -> ServerSoftware.PAPER
        }
    }

    private fun promptVersion(terminal: Terminal, w: PrintWriter, software: ServerSoftware): String {
        val versions = when (software) {
            ServerSoftware.PAPER -> paperVersions
            ServerSoftware.PUFFERFISH -> pufferfishVersions
            ServerSoftware.PURPUR -> purpurVersions
            ServerSoftware.LEAF -> leafVersions
            ServerSoftware.FOLIA -> foliaVersions
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
        w.println()
        w.println("  ${ConsoleFormatter.colorize("Protocol support:", ConsoleFormatter.BOLD)}")

        // Parse major.minor from version
        val parts = version.split(".")
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 21
        val isLatest = (paperVersions?.latest ?: "1.21.4") == version

        val options = mutableListOf(
            InteractivePicker.Option("viaversion", "ViaVersion", "newer clients can join older servers"),
            InteractivePicker.Option("viabackwards", "ViaBackwards", "older clients can join newer servers")
        )
        if (minor >= 9) {
            options.add(InteractivePicker.Option("viarewind", "ViaRewind", "extends backwards support to 1.7/1.8"))
        }

        // Pre-select based on version
        val preSelected = mutableSetOf<String>()
        if (!isLatest) preSelected.add("viaversion")
        if (minor >= 17) preSelected.add("viabackwards")

        InteractivePicker.pickMany(terminal, options, preSelected)

        val plugins = mutableListOf<ViaPlugin>()

        // Enforce dependency chain: ViaBackwards requires ViaVersion, ViaRewind requires ViaBackwards
        if ("viarewind" in preSelected && "viabackwards" !in preSelected) {
            preSelected.add("viabackwards")
            w.println("  ${ConsoleFormatter.hint("→ ViaBackwards auto-included (required by ViaRewind)")}")
        }
        if ("viabackwards" in preSelected && "viaversion" !in preSelected) {
            preSelected.add("viaversion")
            w.println("  ${ConsoleFormatter.hint("→ ViaVersion auto-included (required by ViaBackwards)")}")
        }

        if ("viaversion" in preSelected) plugins.add(ViaPlugin.VIA_VERSION)
        if ("viabackwards" in preSelected) plugins.add(ViaPlugin.VIA_BACKWARDS)
        if ("viarewind" in preSelected) plugins.add(ViaPlugin.VIA_REWIND)

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

    private fun writeNimbusToml(networkName: String, bedrockEnabled: Boolean = false) {
        val token = generateToken()

        val bedrockSection = if (bedrockEnabled) """
            |
            |[bedrock]
            |enabled = true
            |base_port = 19132
        """.trimMargin() else ""

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
            |$bedrockSection
        """.trimMargin() + "\n"
        val configDir = baseDir.resolve("config")
        Files.createDirectories(configDir)
        val configFile = configDir.resolve("nimbus.toml")
        Files.writeString(configFile, content)
        // Restrict permissions: nimbus.toml contains API token & database credentials
        try {
            Files.setPosixFilePermissions(configFile, PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
            // Windows doesn't support POSIX permissions
        }
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
            |optimize = true
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
            |optimize = true
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
