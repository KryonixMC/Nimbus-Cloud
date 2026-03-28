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
import dev.nimbus.template.ModpackInstaller
import dev.nimbus.template.SoftwareResolver
import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class ImportCommand(
    private val terminal: Terminal,
    private val groupManager: GroupManager,
    private val serviceManager: ServiceManager,
    private val softwareResolver: SoftwareResolver,
    private val groupsDir: Path,
    private val templatesDir: Path,
    private val console: NimbusConsole
) : Command {

    override val name = "import"
    override val description = "Import a Modrinth modpack"
    override val usage = "import <url|slug|path.mrpack>"

    private val installer = ModpackInstaller(softwareResolver.client)

    override suspend fun execute(args: List<String>) {
        if (args.isEmpty()) {
            val w = terminal.writer()
            w.println()
            w.println("${BOLD}Usage:$RESET import <source>")
            w.println()
            w.println("${DIM}Sources:$RESET")
            w.println("  ${CYAN}modrinth URL$RESET    import https://modrinth.com/modpack/adrenaserver")
            w.println("  ${CYAN}modrinth slug$RESET   import adrenaserver")
            w.println("  ${CYAN}local file$RESET      import /path/to/modpack.mrpack")
            w.println()
            w.flush()
            return
        }

        // Enter wizard mode: clear screen, pause events
        console.eventsPaused = true
        val w = terminal.writer()
        w.print("\u001B[2J\u001B[H") // clear screen + cursor home
        w.flush()

        try {
            runImport(w, args.joinToString(" "))
        } catch (_: UserInterruptException) {
            w.println()
            w.println("${DIM}Cancelled.$RESET")
        } finally {
            console.eventsPaused = false
            console.flushBufferedEvents()
            w.println()
            w.flush()
        }
    }

    suspend fun runImport(w: java.io.PrintWriter, source: String) {
        w.println("${BOLD}Import Modpack$RESET")
        w.println()

        // Step 1: Resolve source
        w.print("${DIM}Resolving modpack...$RESET")
        w.flush()

        val tempDir = templatesDir.resolve(".modpack-cache")
        val mrpackPath = installer.resolve(source, tempDir)
        if (mrpackPath == null) {
            w.println(" ${RED}✗$RESET")
            w.println("${RED}Could not find or download modpack: $source$RESET")
            w.println("${DIM}Try a Modrinth URL, slug, or local .mrpack file path.$RESET")
            w.flush()
            return
        }
        w.println(" ${GREEN}✓$RESET")

        // Step 2: Parse index
        val index = installer.parseIndex(mrpackPath)
        if (index == null) {
            w.println("${RED}Failed to parse modpack — not a valid .mrpack file.$RESET")
            w.flush()
            return
        }

        val info = installer.getInfo(index)

        // Step 3: Display info
        w.println()
        w.println("${BOLD}${info.name}$RESET ${DIM}v${info.version}$RESET")
        w.println("${DIM}MC ${info.mcVersion} · ${info.modloader.name} ${info.modloaderVersion}$RESET")
        w.println("${DIM}${info.serverFiles} server mods (${info.totalFiles - info.serverFiles} client-only skipped)$RESET")
        w.println()

        // Step 4: Group name
        val defaultName = info.name.replace(Regex("[^a-zA-Z0-9-]"), "").take(20).ifEmpty { "modpack" }
        val groupName = promptGroupName(w, defaultName) ?: return

        // Step 5: Static or dynamic
        w.println()
        w.println("${DIM}Static services keep their data (world, configs) across restarts.$RESET")
        w.println("${DIM}Dynamic services start fresh from the template every time.$RESET")
        val isStatic = promptYesNo("Static service", true)

        // Step 6: Memory & instances
        val memory = prompt("Memory per instance", "2G")
        val minInstances = promptInt("Min instances", if (isStatic) 1 else 0)
        val maxInstances = promptInt("Max instances", 1)

        w.println()
        w.println("${BOLD}Installing...$RESET")
        w.println()

        // Step 6: Install modloader
        val templateDir = templatesDir.resolve(groupName.lowercase())
        if (!templateDir.exists()) templateDir.toFile().mkdirs()

        w.print("${DIM}↓$RESET ${info.modloader.name} ${info.modloaderVersion} ")
        w.flush()
        val loaderOk = softwareResolver.ensureJarAvailable(
            info.modloader, info.mcVersion, templateDir,
            modloaderVersion = info.modloaderVersion
        )
        w.println(if (loaderOk) "${GREEN}✓$RESET" else "${RED}✗$RESET")

        // Step 7: Download mods
        w.print("${DIM}↓$RESET Mods 0/${info.serverFiles} ")
        w.flush()
        val result = installer.installFiles(index, templateDir) { current, total, fileName ->
            w.print("\r${DIM}↓$RESET Mods $current/$total ${DIM}$fileName$RESET${" ".repeat(30)}")
            w.flush()
        }
        w.println()
        if (result.filesFailed > 0) {
            w.println("${YELLOW}${result.filesFailed} mod(s) failed to download.$RESET")
        }
        w.println("${GREEN}✓$RESET ${result.filesDownloaded} mods downloaded")

        // Step 8: Extract overrides
        w.print("${DIM}↓$RESET Configs & overrides ")
        w.flush()
        installer.extractOverrides(mrpackPath, templateDir)
        w.println("${GREEN}✓$RESET")

        // Step 9: Proxy mods
        when (info.modloader) {
            ServerSoftware.FABRIC -> {
                w.print("${DIM}↓$RESET Proxy mods ")
                w.flush()
                softwareResolver.ensureFabricProxyMod(templateDir, info.mcVersion)
                w.println("${GREEN}✓$RESET")
            }
            ServerSoftware.FORGE, ServerSoftware.NEOFORGE -> {
                w.print("${DIM}↓$RESET Proxy mod ")
                w.flush()
                softwareResolver.ensureForwardingMod(info.modloader, info.mcVersion, templateDir)
                w.println("${GREEN}✓$RESET")
            }
            else -> {}
        }

        // Step 10: eula
        val eulaFile = templateDir.resolve("eula.txt")
        if (!eulaFile.exists()) eulaFile.toFile().writeText("eula=true\n")

        // Step 11: Write group TOML
        w.println()
        writeGroupToml(groupName, info, minInstances, maxInstances, memory, isStatic)
        w.println("${GREEN}✓$RESET groups/${groupName.lowercase()}.toml")

        // Step 12: Reload
        val configs = ConfigLoader.loadGroupConfigs(groupsDir)
        groupManager.reloadGroups(configs)
        w.println("${GREEN}✓$RESET Group configs reloaded")

        w.println()
        w.println("${GREEN}${BOLD}Modpack '${info.name}' imported as group '$groupName'!$RESET")
        w.println()

        // Step 13: Start?
        if (minInstances > 0 || promptYesNo("Start an instance now?", true)) {
            try {
                serviceManager.startService(groupName)
                w.println("${GREEN}✓$RESET Service start initiated.")
            } catch (e: Exception) {
                w.println("${RED}✗$RESET Failed: ${e.message}")
            }
        }

        // Cleanup
        try { Files.deleteIfExists(tempDir.resolve(mrpackPath.fileName)) } catch (_: Exception) {}
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

    private fun promptGroupName(w: java.io.PrintWriter, default: String): String? {
        while (true) {
            val name = prompt("Group name", default)
            if (name.isBlank()) { w.println("${RED}Name cannot be empty.$RESET"); w.flush(); continue }
            if (groupManager.getGroup(name) != null) { w.println("${RED}Group '$name' already exists.$RESET"); w.flush(); continue }
            return name
        }
    }

    // -- TOML writer ---------------------------------------------------------

    private fun writeGroupToml(name: String, info: dev.nimbus.template.ModpackInfo, minInstances: Int, maxInstances: Int, memory: String, isStatic: Boolean) {
        Files.createDirectories(groupsDir)
        val templateName = name.lowercase()
        val modloaderLine = if (info.modloaderVersion.isNotEmpty()) "modloader_version = \"${info.modloaderVersion}\"\n" else ""
        val groupType = if (isStatic) "STATIC" else "DYNAMIC"
        val content = """
            |[group]
            |name = "$name"
            |type = "$groupType"
            |template = "$templateName"
            |software = "${info.modloader.name}"
            |version = "${info.mcVersion}"
            |${modloaderLine}
            |[group.resources]
            |memory = "$memory"
            |max_players = 16
            |
            |[group.scaling]
            |min_instances = $minInstances
            |max_instances = $maxInstances
            |players_per_instance = 16
            |scale_threshold = 0.8
            |idle_timeout = 300
            |
            |[group.lifecycle]
            |stop_on_empty = true
            |restart_on_crash = true
            |max_restarts = 5
            |
            |[group.jvm]
            |args = ["-XX:+UseG1GC", "-XX:MaxGCPauseMillis=50"]
        """.trimMargin() + "\n"
        Files.writeString(groupsDir.resolve("$templateName.toml"), content)
    }
}
