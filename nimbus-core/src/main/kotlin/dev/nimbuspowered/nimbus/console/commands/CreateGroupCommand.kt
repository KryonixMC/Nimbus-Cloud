package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.config.ConfigLoader
import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.CYAN
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.RESET
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.YELLOW
import dev.nimbuspowered.nimbus.console.InteractivePicker
import dev.nimbuspowered.nimbus.console.NimbusConsole
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.template.SoftwareResolver
import dev.nimbuspowered.nimbus.template.SoftwareResolver.ViaPlugin
import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class CreateGroupCommand(
    private val terminal: Terminal,
    private val groupManager: GroupManager,
    private val serviceManager: ServiceManager,
    private val softwareResolver: SoftwareResolver,
    private val groupsDir: Path,
    private val templatesDir: Path,
    private val console: NimbusConsole,
    private val curseForgeApiKey: String = ""
) : Command {

    override val name = "create"
    override val description = "Create a new server group"
    override val usage = "create"

    override suspend fun execute(args: List<String>) {
        // Enter wizard mode: clear screen, pause events
        console.eventsPaused = true
        val w = terminal.writer()
        w.print("\u001B[2J\u001B[H")
        w.flush()

        try {
            w.println(ConsoleFormatter.colorize("Create New Group", ConsoleFormatter.BOLD))
            w.println()

            // Step 1: Group name
            val groupName = promptGroupName(w) ?: return

            // Step 2: Software
            val software = promptSoftware(w)

            // Modpack → hand off to import wizard
            if (software == null) {
                val importCmd = ImportCommand(terminal, groupManager, serviceManager, softwareResolver, groupsDir, templatesDir, console, curseForgeApiKey)
                val source = prompt("Modrinth/CurseForge URL, slug, or path to .zip", "")
                if (source.isBlank()) {
                    w.println(ConsoleFormatter.hint("Cancelled."))
                    return
                }
                w.println()
                importCmd.runImport(w, source)
                return
            }

            // Step 3: Version
            w.print(ConsoleFormatter.hint("Fetching available versions..."))
            w.flush()
            val versions = when (software) {
                ServerSoftware.PAPER -> softwareResolver.fetchPaperVersions()
                ServerSoftware.PUFFERFISH -> softwareResolver.fetchPufferfishVersions()
                ServerSoftware.PURPUR -> softwareResolver.fetchPurpurVersions()
                ServerSoftware.LEAF -> softwareResolver.fetchLeafVersions()
                ServerSoftware.FOLIA -> softwareResolver.fetchFoliaVersions()
                ServerSoftware.FORGE -> softwareResolver.fetchForgeGameVersions()
                ServerSoftware.NEOFORGE -> softwareResolver.fetchNeoForgeGameVersions()
                ServerSoftware.FABRIC -> softwareResolver.fetchFabricGameVersions()
                ServerSoftware.CUSTOM -> SoftwareResolver.VersionList(listOf("1.21.4"), emptyList())
                else -> softwareResolver.fetchPaperVersions()
            }
            w.println(" ${ConsoleFormatter.colorize("✓", ConsoleFormatter.GREEN)}")

            val stableVersions = versions.stable
            val snapshotVersions = versions.snapshots
            val defaultVersion = versions.latest ?: "1.21.4"

            if (stableVersions.isNotEmpty()) {
                val display = stableVersions.take(15).joinToString("  ")
                w.println(ConsoleFormatter.hint("Stable: $display"))
                if (stableVersions.size > 15) {
                    w.println(ConsoleFormatter.hint("... and ${stableVersions.size - 15} more (tab for all)"))
                }
            }
            if (snapshotVersions.isNotEmpty()) {
                val display = snapshotVersions.take(5).joinToString("  ")
                w.println("${YELLOW}Nightly: $display$RESET")
            }

            val allCandidates = stableVersions + snapshotVersions
            val version = prompt("Minecraft version", defaultVersion, candidates = allCandidates)

            // Folia warning
            if (software == ServerSoftware.FOLIA) {
                w.println()
                w.println("${YELLOW}Note: Folia uses regionized multithreading. Most Bukkit/Paper plugins")
                w.println("will NOT work. Nimbus SDK + Perms are Folia-compatible.$RESET")
                w.println()
            }

            // Step 3b: Modloader version
            var modloaderVersion = ""
            if (software in listOf(ServerSoftware.FORGE, ServerSoftware.NEOFORGE, ServerSoftware.FABRIC)) {
                w.print(ConsoleFormatter.hint("Fetching modloader versions..."))
                w.flush()
                val loaderVersions = when (software) {
                    ServerSoftware.FORGE -> softwareResolver.fetchForgeVersions(version)
                    ServerSoftware.NEOFORGE -> softwareResolver.fetchNeoForgeVersions(version)
                    ServerSoftware.FABRIC -> softwareResolver.fetchFabricLoaderVersions()
                    else -> SoftwareResolver.VersionList.EMPTY
                }
                w.println(" ${ConsoleFormatter.colorize("✓", ConsoleFormatter.GREEN)}")

                val loaderDefault = loaderVersions.latest ?: ""
                if (loaderVersions.stable.isNotEmpty()) {
                    val display = loaderVersions.stable.take(10).joinToString("  ")
                    w.println(ConsoleFormatter.hint("Available: $display"))
                }
                if (loaderDefault.isNotEmpty()) {
                    modloaderVersion = prompt("Modloader version", loaderDefault, candidates = loaderVersions.all)
                } else {
                    w.println(ConsoleFormatter.warn("No modloader versions found for $version"))
                }
            }

            // Step 3c: Custom JAR name
            var customJarName = ""
            if (software == ServerSoftware.CUSTOM) {
                customJarName = prompt("JAR filename in template", "server.jar")
                w.println(ConsoleFormatter.hint("Place your server JAR as '$customJarName' in templates/${groupName.lowercase()}/"))
            }

            // Step 4: Static or dynamic
            w.println()
            w.println(ConsoleFormatter.hint("Static services keep their data. Dynamic services start fresh from template."))
            val staticOptions = listOf(
                InteractivePicker.Option("dynamic", "Dynamic", "start fresh from template every time"),
                InteractivePicker.Option("static", "Static", "keep world, configs across restarts")
            )
            val staticIndex = InteractivePicker.pickOne(terminal, staticOptions, 0)
            val isStatic = if (staticIndex == InteractivePicker.BACK) false else staticOptions[staticIndex].id == "static"
            w.println(ConsoleFormatter.successLine(if (isStatic) "Static" else "Dynamic"))

            // Step 5-7: Instances & memory
            val minInstances = promptInt("Min instances", 1)
            val maxInstances = promptInt("Max instances", if (isStatic) 1 else 4)
            val defaultMemory = if (software in listOf(ServerSoftware.FORGE, ServerSoftware.NEOFORGE)) "2G" else "1G"
            val memory = normalizeMemory(prompt("Memory per instance", defaultMemory))

            // Step 7: Via plugins (Paper/Purpur only)
            val viaPlugins = if (software in listOf(ServerSoftware.PAPER, ServerSoftware.PUFFERFISH, ServerSoftware.PURPUR, ServerSoftware.LEAF, ServerSoftware.FOLIA)) {
                promptViaPlugins(w, version, versions.latest)
            } else {
                emptyList()
            }

            w.println()
            w.println(ConsoleFormatter.colorize("Downloading files...", ConsoleFormatter.BOLD))
            w.println()

            // Step 8: Download/install server
            val templateDir = templatesDir.resolve(groupName.lowercase())
            when (software) {
                ServerSoftware.CUSTOM -> {
                    w.println(ConsoleFormatter.hint("Custom software — skipping download"))
                    if (!templateDir.exists()) {
                        Files.createDirectories(templateDir)
                        w.println(ConsoleFormatter.successLine("Created template dir: templates/${groupName.lowercase()}/"))
                    }
                }
                ServerSoftware.FORGE -> {
                    download(w, "Forge $modloaderVersion for MC $version") {
                        softwareResolver.ensureJarAvailable(software, version, templateDir, modloaderVersion)
                    }
                    download(w, "Proxy forwarding mod") {
                        softwareResolver.ensureForwardingMod(software, version, templateDir)
                        true
                    }
                }
                ServerSoftware.NEOFORGE -> {
                    download(w, "NeoForge $modloaderVersion for MC $version") {
                        softwareResolver.ensureJarAvailable(software, version, templateDir, modloaderVersion)
                    }
                    download(w, "Proxy forwarding mod") {
                        softwareResolver.ensureForwardingMod(software, version, templateDir)
                        true
                    }
                }
                ServerSoftware.FABRIC -> {
                    download(w, "Fabric $modloaderVersion for MC $version") {
                        softwareResolver.ensureJarAvailable(software, version, templateDir, modloaderVersion)
                    }
                    download(w, "FabricProxy-Lite") {
                        softwareResolver.ensureFabricProxyMod(templateDir, version)
                        true
                    }
                    w.println()
                    w.println("${YELLOW}[BETA] Cardboard allows running Bukkit/Paper plugins on Fabric servers.")
                    w.println("This is experimental software — not all plugins will work correctly.$RESET")
                    val cardboardOptions = listOf(
                        InteractivePicker.Option("no", "No"),
                        InteractivePicker.Option("yes", "Yes — install Cardboard (BETA)")
                    )
                    val cardboardIndex = InteractivePicker.pickOne(terminal, cardboardOptions, 0)
                    if (cardboardIndex != InteractivePicker.BACK && cardboardOptions[cardboardIndex].id == "yes") {
                        download(w, "Cardboard (BETA)") {
                            softwareResolver.ensureCardboardMod(templateDir, version)
                        }
                    }
                }
                else -> {
                    download(w, "${software.name.lowercase().replaceFirstChar { it.uppercase() }} $version") {
                        softwareResolver.ensureJarAvailable(software, version, templateDir)
                    }
                }
            }

            // Step 9: Via plugins
            for (plugin in viaPlugins) {
                download(w, plugin.slug) {
                    softwareResolver.downloadViaPlugin(plugin, templateDir, "PAPER")
                }
            }

            // Step 10: Write TOML
            w.println()
            writeGroupToml(groupName, software, version, modloaderVersion, customJarName, minInstances, maxInstances, memory, isStatic)
            w.println(ConsoleFormatter.successLine("config/groups/${groupName.lowercase()}.toml"))

            // Step 11: Reload
            val configs = ConfigLoader.loadGroupConfigs(groupsDir)
            groupManager.reloadGroups(configs)
            w.println(ConsoleFormatter.successLine("Group configs reloaded"))

            w.println()
            w.println(ConsoleFormatter.successLine("Group '$groupName' created!"))
            w.println()

            // Step 12: Start?
            if (software != ServerSoftware.CUSTOM || templateDir.toFile().listFiles()?.any { it.name.endsWith(".jar") } == true) {
                val startOptions = listOf(
                    InteractivePicker.Option("yes", "Yes, start now"),
                    InteractivePicker.Option("no", "No, start later")
                )
                val startIndex = InteractivePicker.pickOne(terminal, startOptions, 0)
                if (startIndex != InteractivePicker.BACK && startOptions[startIndex].id == "yes") {
                    try {
                        serviceManager.startService(groupName)
                        w.println(ConsoleFormatter.successLine("Service start initiated."))
                    } catch (e: Exception) {
                        w.println(ConsoleFormatter.errorLine("Failed: ${e.message}"))
                    }
                }
            } else {
                w.println(ConsoleFormatter.hint("Place your server JAR in templates/${groupName.lowercase()}/ before starting."))
            }

            w.println()
            w.flush()

        } catch (_: UserInterruptException) {
            w.println()
            w.println(ConsoleFormatter.hint("Cancelled."))
            w.flush()
        } finally {
            console.eventsPaused = false
            console.reprintBanner()
            console.flushBufferedEvents()
        }
    }

    // -- Prompts (flush left) ------------------------------------------------

    private fun prompt(label: String, default: String, candidates: List<String> = emptyList()): String {
        val completer = if (candidates.isNotEmpty()) {
            Completer { _, _, list -> candidates.forEach { list.add(Candidate(it)) } }
        } else null
        val reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .let { if (completer != null) it.completer(completer) else it }
            .build()
        val hint = if (default.isNotEmpty()) " ${ConsoleFormatter.hint("[$default]")}" else ""
        val line = reader.readLine("$label$hint${ConsoleFormatter.hint(":")} ").trim()
        return line.ifEmpty { default }
    }

    private fun promptInt(label: String, default: Int): Int {
        return prompt(label, default.toString()).toIntOrNull() ?: default
    }

    private fun normalizeMemory(input: String): String {
        val trimmed = input.trim()
        if (trimmed.last().isDigit()) {
            val num = trimmed.toIntOrNull() ?: return trimmed
            return if (num >= 256) "${num}M" else "${num}G"
        }
        return trimmed
    }

    private fun promptGroupName(w: java.io.PrintWriter): String? {
        while (true) {
            val name = prompt("Group name", "")
            if (name.isBlank()) { w.println(ConsoleFormatter.error("Name cannot be empty.")); w.flush(); continue }
            if (groupManager.getGroup(name) != null) { w.println(ConsoleFormatter.error("Group '$name' already exists.")); w.flush(); continue }
            return name
        }
    }

    private fun promptSoftware(w: java.io.PrintWriter): ServerSoftware? {
        w.println(ConsoleFormatter.hint("Select server software:"))
        val options = listOf(
            InteractivePicker.Option("paper", "Paper", "optimized vanilla, plugins"),
            InteractivePicker.Option("purpur", "Purpur", "Paper fork, extra features"),
            InteractivePicker.Option("pufferfish", "Pufferfish", "Paper fork, high-performance"),
            InteractivePicker.Option("leaf", "Leaf", "Paper fork, performance + stability"),
            InteractivePicker.Option("folia", "Folia", "regionized multithreading, 1.19.4+"),
            InteractivePicker.Option("forge", "Forge", "mods, auto-installs"),
            InteractivePicker.Option("neoforge", "NeoForge", "modern Forge fork"),
            InteractivePicker.Option("fabric", "Fabric", "lightweight mods"),
            InteractivePicker.Option("modpack", "Import Modpack", "Modrinth modpack"),
            InteractivePicker.Option("custom", "Custom JAR", "bring your own")
        )
        val index = InteractivePicker.pickOne(terminal, options)
        if (index == InteractivePicker.BACK) {
            w.println(ConsoleFormatter.hint("Cancelled."))
            return ServerSoftware.PAPER // fallback, won't reach here in normal flow
        }
        val chosen = options[index]
        w.println(ConsoleFormatter.successLine(chosen.label))
        return when (chosen.id) {
            "pufferfish" -> ServerSoftware.PUFFERFISH
            "purpur" -> ServerSoftware.PURPUR
            "leaf" -> ServerSoftware.LEAF
            "folia" -> ServerSoftware.FOLIA
            "forge" -> ServerSoftware.FORGE
            "neoforge" -> ServerSoftware.NEOFORGE
            "fabric" -> ServerSoftware.FABRIC
            "custom" -> ServerSoftware.CUSTOM
            "modpack" -> null // signals to switch to import flow
            else -> ServerSoftware.PAPER
        }
    }

    private fun promptViaPlugins(w: java.io.PrintWriter, version: String, latestVersion: String?): List<ViaPlugin> {
        w.println()
        w.println(ConsoleFormatter.colorize("Protocol support:", ConsoleFormatter.BOLD))
        w.println(ConsoleFormatter.hint("ViaVersion allows newer clients, ViaBackwards allows older clients."))
        w.println()

        val minor = version.split(".").getOrNull(1)?.toIntOrNull() ?: 21
        val isLatest = (latestVersion ?: "1.21.4") == version

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
            w.println(ConsoleFormatter.hint("  → ViaBackwards auto-included (required by ViaRewind)"))
        }
        if ("viabackwards" in preSelected && "viaversion" !in preSelected) {
            preSelected.add("viaversion")
            w.println(ConsoleFormatter.hint("  → ViaVersion auto-included (required by ViaBackwards)"))
        }

        if ("viaversion" in preSelected) plugins.add(ViaPlugin.VIA_VERSION)
        if ("viabackwards" in preSelected) plugins.add(ViaPlugin.VIA_BACKWARDS)
        if ("viarewind" in preSelected) plugins.add(ViaPlugin.VIA_REWIND)

        if (plugins.isNotEmpty()) {
            w.println(ConsoleFormatter.successLine("Via plugins: ${plugins.joinToString(", ") { it.slug }}"))
        } else {
            w.println(ConsoleFormatter.hint("No Via plugins selected."))
        }
        w.flush()
        return plugins
    }

    // -- Download helper -----------------------------------------------------

    private suspend fun download(w: java.io.PrintWriter, label: String, action: suspend () -> Boolean) {
        w.print("${ConsoleFormatter.hint("↓")} $label ")
        w.flush()
        val success = action()
        if (success) {
            w.println(ConsoleFormatter.colorize("✓", ConsoleFormatter.GREEN))
        } else {
            w.println(ConsoleFormatter.colorize("✗", ConsoleFormatter.RED))
            w.println(ConsoleFormatter.warn("Download failed. You can place the file manually later."))
        }
        w.flush()
    }

    // -- TOML writer ---------------------------------------------------------

    private fun writeGroupToml(name: String, software: ServerSoftware, version: String, modloaderVersion: String, jarName: String, minInstances: Int, maxInstances: Int, memory: String, isStatic: Boolean) {
        Files.createDirectories(groupsDir)
        val templateName = name.lowercase()
        val isLobby = name.contains("lobby", ignoreCase = true)
        val modloaderLine = if (modloaderVersion.isNotEmpty()) "modloader_version = \"$modloaderVersion\"\n" else ""
        val jarNameLine = if (jarName.isNotEmpty() && jarName != "server.jar") "jar_name = \"$jarName\"\n" else ""
        val groupType = if (isStatic) "STATIC" else "DYNAMIC"

        val content = """
            |[group]
            |name = "$name"
            |type = "$groupType"
            |template = "$templateName"
            |software = "${software.name}"
            |version = "$version"
            |${modloaderLine}${jarNameLine}
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
}
