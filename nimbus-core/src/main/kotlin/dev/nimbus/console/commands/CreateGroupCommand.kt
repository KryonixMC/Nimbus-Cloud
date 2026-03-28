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
import dev.nimbus.console.NimbusConsole
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
import kotlin.io.path.exists

class CreateGroupCommand(
    private val terminal: Terminal,
    private val groupManager: GroupManager,
    private val serviceManager: ServiceManager,
    private val softwareResolver: SoftwareResolver,
    private val groupsDir: Path,
    private val templatesDir: Path,
    private val console: NimbusConsole
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
            w.println("${BOLD}Create New Group$RESET")
            w.println()

            // Step 1: Group name
            val groupName = promptGroupName(w) ?: return

            // Step 2: Software
            val software = promptSoftware(w)

            // Modpack → hand off to import wizard
            if (software == null) {
                val importCmd = ImportCommand(terminal, groupManager, serviceManager, softwareResolver, groupsDir, templatesDir, console)
                val source = prompt("Modrinth URL or slug", "")
                if (source.isBlank()) {
                    w.println("${DIM}Cancelled.$RESET")
                    return
                }
                w.println()
                importCmd.runImport(w, source)
                return
            }

            // Step 3: Version
            w.print("${DIM}Fetching available versions...$RESET")
            w.flush()
            val versions = when (software) {
                ServerSoftware.PAPER -> softwareResolver.fetchPaperVersions()
                ServerSoftware.PURPUR -> softwareResolver.fetchPurpurVersions()
                ServerSoftware.FORGE -> softwareResolver.fetchForgeGameVersions()
                ServerSoftware.NEOFORGE -> softwareResolver.fetchNeoForgeGameVersions()
                ServerSoftware.FABRIC -> softwareResolver.fetchFabricGameVersions()
                ServerSoftware.CUSTOM -> SoftwareResolver.VersionList(listOf("1.21.4"), emptyList())
                else -> softwareResolver.fetchPaperVersions()
            }
            w.println(" ${GREEN}✓$RESET")

            val stableVersions = versions.stable
            val snapshotVersions = versions.snapshots
            val defaultVersion = versions.latest ?: "1.21.4"

            if (stableVersions.isNotEmpty()) {
                val display = stableVersions.take(15).joinToString("  ")
                w.println("${DIM}Stable: $display$RESET")
                if (stableVersions.size > 15) {
                    w.println("${DIM}... and ${stableVersions.size - 15} more (tab for all)$RESET")
                }
            }
            if (snapshotVersions.isNotEmpty()) {
                val display = snapshotVersions.take(5).joinToString("  ")
                w.println("${YELLOW}Nightly: $display$RESET")
            }

            val allCandidates = stableVersions + snapshotVersions
            val version = prompt("Minecraft version", defaultVersion, candidates = allCandidates)

            // Step 3b: Modloader version
            var modloaderVersion = ""
            if (software in listOf(ServerSoftware.FORGE, ServerSoftware.NEOFORGE, ServerSoftware.FABRIC)) {
                w.print("${DIM}Fetching modloader versions...$RESET")
                w.flush()
                val loaderVersions = when (software) {
                    ServerSoftware.FORGE -> softwareResolver.fetchForgeVersions(version)
                    ServerSoftware.NEOFORGE -> softwareResolver.fetchNeoForgeVersions(version)
                    ServerSoftware.FABRIC -> softwareResolver.fetchFabricLoaderVersions()
                    else -> SoftwareResolver.VersionList.EMPTY
                }
                w.println(" ${GREEN}✓$RESET")

                val loaderDefault = loaderVersions.latest ?: ""
                if (loaderVersions.stable.isNotEmpty()) {
                    val display = loaderVersions.stable.take(10).joinToString("  ")
                    w.println("${DIM}Available: $display$RESET")
                }
                if (loaderDefault.isNotEmpty()) {
                    modloaderVersion = prompt("Modloader version", loaderDefault, candidates = loaderVersions.all)
                } else {
                    w.println("${YELLOW}No modloader versions found for $version$RESET")
                }
            }

            // Step 3c: Custom JAR name
            var customJarName = ""
            if (software == ServerSoftware.CUSTOM) {
                customJarName = prompt("JAR filename in template", "server.jar")
                w.println("${DIM}Place your server JAR as '$customJarName' in templates/${groupName.lowercase()}/$RESET")
            }

            // Step 4: Static or dynamic
            w.println()
            w.println("${DIM}Static services keep their data (world, configs) across restarts.$RESET")
            w.println("${DIM}Dynamic services start fresh from the template every time.$RESET")
            val isStatic = promptYesNo("Static service", false)

            // Step 5-7: Instances & memory
            val minInstances = promptInt("Min instances", 1)
            val maxInstances = promptInt("Max instances", if (isStatic) 1 else 4)
            val defaultMemory = if (software in listOf(ServerSoftware.FORGE, ServerSoftware.NEOFORGE)) "2G" else "1G"
            val memory = prompt("Memory per instance", defaultMemory)

            // Step 7: Via plugins (Paper/Purpur only)
            val viaPlugins = if (software in listOf(ServerSoftware.PAPER, ServerSoftware.PURPUR)) {
                promptViaPlugins(w, version, versions.latest)
            } else {
                emptyList()
            }

            w.println()
            w.println("${BOLD}Downloading files...$RESET")
            w.println()

            // Step 8: Download/install server
            val templateDir = templatesDir.resolve(groupName.lowercase())
            when (software) {
                ServerSoftware.CUSTOM -> {
                    w.println("${DIM}Custom software — skipping download$RESET")
                    if (!templateDir.exists()) {
                        Files.createDirectories(templateDir)
                        w.println("${GREEN}✓$RESET Created template dir: templates/${groupName.lowercase()}/")
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
            w.println("${GREEN}✓$RESET groups/${groupName.lowercase()}.toml")

            // Step 11: Reload
            val configs = ConfigLoader.loadGroupConfigs(groupsDir)
            groupManager.reloadGroups(configs)
            w.println("${GREEN}✓$RESET Group configs reloaded")

            w.println()
            w.println("${GREEN}${BOLD}Group '$groupName' created!$RESET")
            w.println()

            // Step 12: Start?
            if (software != ServerSoftware.CUSTOM || templateDir.toFile().listFiles()?.any { it.name.endsWith(".jar") } == true) {
                if (promptYesNo("Start an instance now?", true)) {
                    try {
                        serviceManager.startService(groupName)
                        w.println("${GREEN}✓$RESET Service start initiated.")
                    } catch (e: Exception) {
                        w.println("${RED}✗$RESET Failed: ${e.message}")
                    }
                }
            } else {
                w.println("${DIM}Place your server JAR in templates/${groupName.lowercase()}/ before starting.$RESET")
            }

            w.println()
            w.flush()

        } catch (_: UserInterruptException) {
            w.println()
            w.println("${DIM}Cancelled.$RESET")
            w.flush()
        } finally {
            console.eventsPaused = false
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
        val hint = if (default.isNotEmpty()) " ${DIM}[$default]${RESET}" else ""
        val line = reader.readLine("$label$hint${DIM}:$RESET ").trim()
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
        return prompt(label, default.toString()).toIntOrNull() ?: default
    }

    private fun promptGroupName(w: java.io.PrintWriter): String? {
        while (true) {
            val name = prompt("Group name", "")
            if (name.isBlank()) { w.println("${RED}Name cannot be empty.$RESET"); w.flush(); continue }
            if (groupManager.getGroup(name) != null) { w.println("${RED}Group '$name' already exists.$RESET"); w.flush(); continue }
            return name
        }
    }

    private fun promptSoftware(w: java.io.PrintWriter): ServerSoftware? {
        w.println("${DIM}Available server software:$RESET")
        w.println("  ${CYAN}paper$RESET     — Paper (optimized vanilla, plugins)")
        w.println("  ${CYAN}purpur$RESET    — Purpur (Paper fork, extra features)")
        w.println("  ${CYAN}forge$RESET     — Forge (mods, auto-installs)")
        w.println("  ${CYAN}neoforge$RESET  — NeoForge (modern Forge fork)")
        w.println("  ${CYAN}fabric$RESET    — Fabric (lightweight mods)")
        w.println("  ${CYAN}modpack$RESET   — Import a Modrinth modpack")
        w.println("  ${CYAN}custom$RESET    — Custom JAR (bring your own)")
        w.flush()

        val answer = prompt("Server software", "paper",
            candidates = listOf("paper", "purpur", "forge", "neoforge", "fabric", "modpack", "custom"))
        return when (answer.lowercase()) {
            "purpur" -> ServerSoftware.PURPUR
            "forge" -> ServerSoftware.FORGE
            "neoforge" -> ServerSoftware.NEOFORGE
            "fabric" -> ServerSoftware.FABRIC
            "custom" -> ServerSoftware.CUSTOM
            "modpack" -> null // signals to switch to import flow
            else -> ServerSoftware.PAPER
        }
    }

    private fun promptViaPlugins(w: java.io.PrintWriter, version: String, latestVersion: String?): List<ViaPlugin> {
        val plugins = mutableListOf<ViaPlugin>()
        w.println()
        w.println("${BOLD}Protocol support:$RESET")
        w.println("${DIM}ViaVersion allows newer clients, ViaBackwards allows older clients.$RESET")
        w.println()

        val minor = version.split(".").getOrNull(1)?.toIntOrNull() ?: 21
        val isLatest = (latestVersion ?: "1.21.4") == version

        if (!isLatest) {
            if (promptYesNo("Install ${CYAN}ViaVersion$RESET? ${DIM}(newer clients can join)$RESET", true)) {
                plugins.add(ViaPlugin.VIA_VERSION)
            }
        } else {
            if (promptYesNo("Install ${CYAN}ViaVersion$RESET?", false)) {
                plugins.add(ViaPlugin.VIA_VERSION)
            }
        }

        if (promptYesNo("Install ${CYAN}ViaBackwards$RESET? ${DIM}(older clients can join)$RESET", minor >= 17)) {
            plugins.add(ViaPlugin.VIA_BACKWARDS)
            if (minor >= 9 && promptYesNo("Install ${CYAN}ViaRewind$RESET? ${DIM}(1.7/1.8 clients)$RESET", false)) {
                plugins.add(ViaPlugin.VIA_REWIND)
            }
        }

        if (plugins.isNotEmpty()) {
            w.println("${GREEN}✓$RESET Via plugins: ${plugins.joinToString(", ") { it.slug }}")
        } else {
            w.println("${DIM}No Via plugins selected.$RESET")
        }
        w.flush()
        return plugins
    }

    // -- Download helper -----------------------------------------------------

    private suspend fun download(w: java.io.PrintWriter, label: String, action: suspend () -> Boolean) {
        w.print("${DIM}↓$RESET $label ")
        w.flush()
        val success = action()
        if (success) {
            w.println("${GREEN}✓$RESET")
        } else {
            w.println("${RED}✗$RESET")
            w.println("${YELLOW}Download failed. You can place the file manually later.$RESET")
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
            |args = ["-XX:+UseG1GC", "-XX:MaxGCPauseMillis=50"]
        """.trimMargin() + "\n"
        Files.writeString(groupsDir.resolve("$templateName.toml"), content)
    }
}
