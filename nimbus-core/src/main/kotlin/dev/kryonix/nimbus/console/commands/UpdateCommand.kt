package dev.kryonix.nimbus.console.commands

import dev.kryonix.nimbus.config.ConfigLoader
import dev.kryonix.nimbus.config.ServerSoftware
import dev.kryonix.nimbus.console.Command
import dev.kryonix.nimbus.console.ConsoleFormatter
import dev.kryonix.nimbus.console.ConsoleFormatter.CYAN
import dev.kryonix.nimbus.console.ConsoleFormatter.RESET
import dev.kryonix.nimbus.console.ConsoleFormatter.YELLOW
import dev.kryonix.nimbus.console.NimbusConsole
import dev.kryonix.nimbus.group.GroupManager
import dev.kryonix.nimbus.service.ServiceRegistry
import dev.kryonix.nimbus.template.SoftwareResolver
import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class UpdateCommand(
    private val terminal: Terminal,
    private val groupManager: GroupManager,
    private val registry: ServiceRegistry,
    private val softwareResolver: SoftwareResolver,
    private val groupsDir: Path,
    private val templatesDir: Path,
    private val console: NimbusConsole
) : Command {

    override val name = "update"
    override val description = "Update a group's server software or Minecraft version"
    override val usage = "update <group> [version <ver>] [software <sw> [<ver>]]"

    override suspend fun execute(args: List<String>) {
        if (args.isEmpty()) {
            printUsage()
            return
        }

        val groupName = args[0]
        val group = groupManager.getGroup(groupName)
        if (group == null) {
            println(ConsoleFormatter.error("Group '$groupName' not found."))
            return
        }

        val def = group.config.group
        val subcommand = args.getOrNull(1)?.lowercase()

        when (subcommand) {
            "version" -> {
                val targetVersion = args.getOrNull(2)
                if (targetVersion == null) {
                    println(ConsoleFormatter.error("Usage: update $groupName version <version>"))
                    return
                }
                updateVersion(groupName, def.software, def.version, targetVersion, def.modloaderVersion)
            }
            "software" -> {
                val targetSoftwareStr = args.getOrNull(2)
                if (targetSoftwareStr == null) {
                    println(ConsoleFormatter.error("Usage: update $groupName software <paper|purpur|forge|neoforge|fabric>"))
                    return
                }
                val targetSoftware = parseSoftware(targetSoftwareStr)
                if (targetSoftware == null) {
                    println(ConsoleFormatter.error("Unknown software: $targetSoftwareStr"))
                    println(ConsoleFormatter.hint("Available: paper, purpur, folia, forge, neoforge, fabric"))
                    return
                }
                val targetVersion = args.getOrNull(3)
                updateSoftware(groupName, def.software, targetSoftware, def.version, targetVersion, def.modloaderVersion)
            }
            null -> {
                // Interactive mode
                runInteractive(groupName)
            }
            else -> {
                printUsage()
            }
        }
    }

    // ── Compatibility ──────────────────────────────────────────

    enum class SoftwareFamily {
        PLUGIN,   // Paper, Purpur
        FORGE,    // Forge, NeoForge
        FABRIC,   // Fabric
        PROXY,    // Velocity
        CUSTOM    // Custom JAR
    }

    private fun familyOf(software: ServerSoftware): SoftwareFamily = when (software) {
        ServerSoftware.PAPER, ServerSoftware.PUFFERFISH, ServerSoftware.PURPUR, ServerSoftware.FOLIA -> SoftwareFamily.PLUGIN
        ServerSoftware.FORGE, ServerSoftware.NEOFORGE -> SoftwareFamily.FORGE
        ServerSoftware.FABRIC -> SoftwareFamily.FABRIC
        ServerSoftware.VELOCITY -> SoftwareFamily.PROXY
        ServerSoftware.CUSTOM -> SoftwareFamily.CUSTOM
    }

    data class CompatResult(
        val allowed: Boolean,
        val warning: String? = null
    )

    private fun checkCompatibility(from: ServerSoftware, to: ServerSoftware): CompatResult {
        if (from == to) return CompatResult(true)

        val fromFamily = familyOf(from)
        val toFamily = familyOf(to)

        // Same family: always allowed
        if (fromFamily == toFamily) {
            // Special warning for Forge <-> NeoForge
            if ((from == ServerSoftware.FORGE && to == ServerSoftware.NEOFORGE) ||
                (from == ServerSoftware.NEOFORGE && to == ServerSoftware.FORGE)) {
                return CompatResult(true,
                    "Forge and NeoForge have diverged significantly. " +
                    "Some mods may not be compatible after switching. " +
                    "Check your mods/ folder after the update.")
            }
            return CompatResult(true)
        }

        // Cross-family: blocked
        val reason = when {
            fromFamily == SoftwareFamily.PROXY || toFamily == SoftwareFamily.PROXY ->
                "Cannot switch between proxy (Velocity) and game server software. " +
                "They serve completely different purposes."

            fromFamily == SoftwareFamily.CUSTOM || toFamily == SoftwareFamily.CUSTOM ->
                "Cannot switch to/from CUSTOM software automatically. " +
                "Create a new group instead."

            fromFamily == SoftwareFamily.PLUGIN && toFamily == SoftwareFamily.FORGE ->
                "Cannot switch from ${from.name} to ${to.name}. " +
                "Plugin servers (.jar plugins in plugins/) are incompatible with " +
                "modded servers (.jar mods in mods/). Create a new group instead."

            fromFamily == SoftwareFamily.PLUGIN && toFamily == SoftwareFamily.FABRIC ->
                "Cannot switch from ${from.name} to ${to.name}. " +
                "Plugin servers use plugins/ while Fabric uses mods/. " +
                "These are fundamentally different ecosystems."

            fromFamily == SoftwareFamily.FORGE && toFamily == SoftwareFamily.PLUGIN ->
                "Cannot switch from ${from.name} to ${to.name}. " +
                "Modded servers use mods/ which are incompatible with plugin servers. " +
                "Create a new group instead."

            fromFamily == SoftwareFamily.FABRIC && toFamily == SoftwareFamily.PLUGIN ->
                "Cannot switch from ${from.name} to ${to.name}. " +
                "Fabric mods are incompatible with plugin servers. " +
                "Create a new group instead."

            fromFamily == SoftwareFamily.FORGE && toFamily == SoftwareFamily.FABRIC ->
                "Cannot switch from ${from.name} to ${to.name}. " +
                "Forge mods (.jar) use a completely different format than Fabric mods. " +
                "Your mods/ folder would need to be replaced entirely."

            fromFamily == SoftwareFamily.FABRIC && toFamily == SoftwareFamily.FORGE ->
                "Cannot switch from ${from.name} to ${to.name}. " +
                "Fabric mods are incompatible with Forge/NeoForge. " +
                "Your mods/ folder would need to be replaced entirely."

            else -> "Switching from ${from.name} to ${to.name} is not supported."
        }
        return CompatResult(false, reason)
    }

    // ── Version update ─────────────────────────────────────────

    private suspend fun updateVersion(
        groupName: String,
        software: ServerSoftware,
        currentVersion: String,
        targetVersion: String,
        currentModloaderVersion: String
    ) {
        if (currentVersion == targetVersion) {
            println(ConsoleFormatter.warn("Group '$groupName' is already on version $targetVersion."))
            return
        }

        println(ConsoleFormatter.info("Updating $groupName: $currentVersion -> $targetVersion"))

        // For modded servers, resolve a new modloader version
        var newModloaderVersion = currentModloaderVersion
        if (software in listOf(ServerSoftware.FORGE, ServerSoftware.NEOFORGE, ServerSoftware.FABRIC)) {
            print("${ConsoleFormatter.hint("Fetching modloader versions for $targetVersion...")} ")
            val loaderVersions = when (software) {
                ServerSoftware.FORGE -> softwareResolver.fetchForgeVersions(targetVersion)
                ServerSoftware.NEOFORGE -> softwareResolver.fetchNeoForgeVersions(targetVersion)
                ServerSoftware.FABRIC -> softwareResolver.fetchFabricLoaderVersions()
                else -> SoftwareResolver.VersionList.EMPTY
            }
            if (loaderVersions.latest == null) {
                println(ConsoleFormatter.colorize("!", ConsoleFormatter.RED))
                println(ConsoleFormatter.error("No modloader versions found for ${software.name} on MC $targetVersion."))
                return
            }
            println(ConsoleFormatter.colorize("ok", ConsoleFormatter.GREEN))
            newModloaderVersion = loaderVersions.latest!!
            println(ConsoleFormatter.hint("Modloader: $currentModloaderVersion -> $newModloaderVersion"))
        }

        // Remove old JAR
        val templateDir = templatesDir.resolve(groupName.lowercase())
        removeOldJar(templateDir, software)

        // Download new JAR
        val success = downloadNewJar(software, targetVersion, templateDir, newModloaderVersion)
        if (!success) {
            println(ConsoleFormatter.error("Failed to download ${software.name} $targetVersion."))
            return
        }

        // Update TOML
        updateToml(groupName, software = null, version = targetVersion, modloaderVersion = newModloaderVersion.takeIf { it != currentModloaderVersion })

        // Reload
        reloadConfigs()

        // Warn about running services
        warnRunningServices(groupName)

        println()
        println(ConsoleFormatter.successLine("Updated '$groupName' to version $targetVersion."))
    }

    // ── Software update ────────────────────────────────────────

    private suspend fun updateSoftware(
        groupName: String,
        currentSoftware: ServerSoftware,
        targetSoftware: ServerSoftware,
        currentVersion: String,
        explicitVersion: String?,
        currentModloaderVersion: String
    ) {
        // Check compatibility
        val compat = checkCompatibility(currentSoftware, targetSoftware)
        if (!compat.allowed) {
            println(ConsoleFormatter.error(compat.warning ?: "Switch not allowed."))
            return
        }
        if (compat.warning != null) {
            println(ConsoleFormatter.warnLine(compat.warning!!))
            println()
        }

        val targetVersion = explicitVersion ?: currentVersion
        println(ConsoleFormatter.info("Updating $groupName: ${currentSoftware.name} $currentVersion -> ${targetSoftware.name} $targetVersion"))

        // For modded servers, resolve modloader version
        var newModloaderVersion = ""
        if (targetSoftware in listOf(ServerSoftware.FORGE, ServerSoftware.NEOFORGE, ServerSoftware.FABRIC)) {
            print("${ConsoleFormatter.hint("Fetching modloader versions...")} ")
            val loaderVersions = when (targetSoftware) {
                ServerSoftware.FORGE -> softwareResolver.fetchForgeVersions(targetVersion)
                ServerSoftware.NEOFORGE -> softwareResolver.fetchNeoForgeVersions(targetVersion)
                ServerSoftware.FABRIC -> softwareResolver.fetchFabricLoaderVersions()
                else -> SoftwareResolver.VersionList.EMPTY
            }
            if (loaderVersions.latest == null) {
                println(ConsoleFormatter.colorize("!", ConsoleFormatter.RED))
                println(ConsoleFormatter.error("No modloader versions found for ${targetSoftware.name} on MC $targetVersion."))
                return
            }
            println(ConsoleFormatter.colorize("ok", ConsoleFormatter.GREEN))
            newModloaderVersion = loaderVersions.latest!!
            println(ConsoleFormatter.hint("Modloader version: $newModloaderVersion"))
        }

        // Remove old JAR
        val templateDir = templatesDir.resolve(groupName.lowercase())
        removeOldJar(templateDir, currentSoftware)

        // Download new JAR
        val success = downloadNewJar(targetSoftware, targetVersion, templateDir, newModloaderVersion)
        if (!success) {
            println(ConsoleFormatter.error("Failed to download ${targetSoftware.name} $targetVersion."))
            return
        }

        // Download forwarding mods if switching to modded
        if (targetSoftware in listOf(ServerSoftware.FORGE, ServerSoftware.NEOFORGE)) {
            print("${ConsoleFormatter.hint("Ensuring proxy forwarding mod...")} ")
            softwareResolver.ensureForwardingMod(targetSoftware, targetVersion, templateDir)
            println(ConsoleFormatter.colorize("ok", ConsoleFormatter.GREEN))
        } else if (targetSoftware == ServerSoftware.FABRIC) {
            print("${ConsoleFormatter.hint("Ensuring FabricProxy-Lite...")} ")
            softwareResolver.ensureFabricProxyMod(templateDir, targetVersion)
            println(ConsoleFormatter.colorize("ok", ConsoleFormatter.GREEN))
        }

        // Update TOML
        updateToml(groupName, software = targetSoftware, version = targetVersion, modloaderVersion = newModloaderVersion)

        // Reload
        reloadConfigs()

        // Warn about running services
        warnRunningServices(groupName)

        println()
        println(ConsoleFormatter.successLine("Updated '$groupName' to ${targetSoftware.name} $targetVersion."))
    }

    // ── Interactive mode ───────────────────────────────────────

    private suspend fun runInteractive(groupName: String) {
        val group = groupManager.getGroup(groupName)!!
        val def = group.config.group

        console.eventsPaused = true
        val w = terminal.writer()

        try {
            w.println(ConsoleFormatter.colorize("Update Group: $groupName", ConsoleFormatter.BOLD))
            w.println()
            w.println(ConsoleFormatter.hint("Current: ${def.software.name} ${def.version}" +
                (if (def.modloaderVersion.isNotEmpty()) " (loader ${def.modloaderVersion})" else "")))
            w.println()

            val action = prompt("What to update?", "version",
                candidates = listOf("version", "software"))

            when (action.lowercase()) {
                "version" -> {
                    w.print("${ConsoleFormatter.hint("Fetching available versions...")} ")
                    w.flush()
                    val versions = fetchVersionsFor(def.software, def.version)
                    w.println(ConsoleFormatter.colorize("ok", ConsoleFormatter.GREEN))

                    if (versions.stable.isNotEmpty()) {
                        val display = versions.stable.take(15).joinToString("  ")
                        w.println(ConsoleFormatter.hint("Available: $display"))
                        if (versions.stable.size > 15) {
                            w.println(ConsoleFormatter.hint("... and ${versions.stable.size - 15} more"))
                        }
                    }

                    val targetVersion = prompt("New version", versions.latest ?: def.version,
                        candidates = versions.all)

                    w.println()
                    updateVersion(groupName, def.software, def.version, targetVersion, def.modloaderVersion)
                }
                "software" -> {
                    // Show compatible options
                    w.println(ConsoleFormatter.hint("Compatible software for ${def.software.name}:"))

                    val options = mutableListOf<String>()
                    for (sw in ServerSoftware.entries) {
                        if (sw == def.software || sw == ServerSoftware.CUSTOM || sw == ServerSoftware.VELOCITY) continue
                        val compat = checkCompatibility(def.software, sw)
                        if (compat.allowed) {
                            val warning = if (compat.warning != null) " ${YELLOW}(with caveats)$RESET" else ""
                            w.println("  ${CYAN}${sw.name.lowercase()}$RESET$warning")
                            options.add(sw.name.lowercase())
                        }
                    }

                    if (options.isEmpty()) {
                        w.println(ConsoleFormatter.warn("No compatible software switches available for ${def.software.name}."))
                        return
                    }

                    val targetStr = prompt("New software", options.first(), candidates = options)
                    val targetSoftware = parseSoftware(targetStr) ?: run {
                        w.println(ConsoleFormatter.error("Unknown software: $targetStr"))
                        return
                    }

                    // Optionally pick a new version
                    w.print("${ConsoleFormatter.hint("Fetching available versions...")} ")
                    w.flush()
                    val versions = fetchVersionsFor(targetSoftware, def.version)
                    w.println(ConsoleFormatter.colorize("ok", ConsoleFormatter.GREEN))

                    val defaultVersion = if (versions.stable.contains(def.version)) def.version
                        else versions.latest ?: def.version

                    if (versions.stable.isNotEmpty()) {
                        val display = versions.stable.take(10).joinToString("  ")
                        w.println(ConsoleFormatter.hint("Available: $display"))
                    }

                    val targetVersion = prompt("Minecraft version", defaultVersion, candidates = versions.all)

                    w.println()
                    updateSoftware(groupName, def.software, targetSoftware, def.version, targetVersion, def.modloaderVersion)
                }
                else -> {
                    w.println(ConsoleFormatter.hint("Cancelled."))
                }
            }
        } catch (_: UserInterruptException) {
            w.println()
            w.println(ConsoleFormatter.hint("Cancelled."))
        } finally {
            console.eventsPaused = false
            console.flushBufferedEvents()
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    private suspend fun fetchVersionsFor(software: ServerSoftware, currentMcVersion: String): SoftwareResolver.VersionList {
        return when (software) {
            ServerSoftware.PAPER -> softwareResolver.fetchPaperVersions()
            ServerSoftware.PUFFERFISH -> softwareResolver.fetchPufferfishVersions()
            ServerSoftware.PURPUR -> softwareResolver.fetchPurpurVersions()
            ServerSoftware.FOLIA -> softwareResolver.fetchFoliaVersions()
            ServerSoftware.VELOCITY -> softwareResolver.fetchVelocityVersions()
            ServerSoftware.FORGE -> softwareResolver.fetchForgeGameVersions()
            ServerSoftware.NEOFORGE -> softwareResolver.fetchNeoForgeGameVersions()
            ServerSoftware.FABRIC -> softwareResolver.fetchFabricGameVersions()
            ServerSoftware.CUSTOM -> SoftwareResolver.VersionList(listOf(currentMcVersion), emptyList())
        }
    }

    private fun removeOldJar(templateDir: Path, software: ServerSoftware) {
        if (!templateDir.exists()) return

        when (software) {
            ServerSoftware.FORGE, ServerSoftware.NEOFORGE -> {
                // Remove forge/neoforge JARs, server.jar, libraries/, and installer artifacts
                val prefix = if (software == ServerSoftware.FORGE) "forge-" else "neoforge-"
                templateDir.toFile().listFiles()?.filter {
                    (it.name.startsWith(prefix) && it.name.endsWith(".jar")) ||
                    it.name == "server.jar"
                }?.forEach { it.delete() }

                // Remove libraries dir (will be re-created by installer)
                val libsDir = templateDir.resolve("libraries").toFile()
                if (libsDir.exists() && libsDir.isDirectory) {
                    libsDir.deleteRecursively()
                }
            }
            ServerSoftware.FABRIC -> {
                val jar = templateDir.resolve("server.jar")
                if (jar.exists()) Files.delete(jar)
            }
            ServerSoftware.VELOCITY -> {
                val jar = templateDir.resolve("velocity.jar")
                if (jar.exists()) Files.delete(jar)
            }
            else -> {
                val jar = templateDir.resolve("server.jar")
                if (jar.exists()) Files.delete(jar)
            }
        }
    }

    private suspend fun downloadNewJar(
        software: ServerSoftware,
        version: String,
        templateDir: Path,
        modloaderVersion: String
    ): Boolean {
        if (!templateDir.exists()) Files.createDirectories(templateDir)

        print("${ConsoleFormatter.hint("Downloading ${software.name.lowercase()} $version...")} ")
        val success = softwareResolver.ensureJarAvailable(software, version, templateDir, modloaderVersion)
        if (success) {
            println(ConsoleFormatter.colorize("ok", ConsoleFormatter.GREEN))
        } else {
            println(ConsoleFormatter.colorize("failed", ConsoleFormatter.RED))
        }
        return success
    }

    private fun updateToml(groupName: String, software: ServerSoftware?, version: String?, modloaderVersion: String?) {
        val tomlFile = groupsDir.resolve("${groupName.lowercase()}.toml")
        if (!tomlFile.exists()) {
            println(ConsoleFormatter.warn("Config file not found: ${tomlFile.fileName}"))
            return
        }

        var content = tomlFile.readText()

        if (version != null) {
            val versionPattern = Regex("""^(\s*version\s*=\s*)["'][^"']*["']""", RegexOption.MULTILINE)
            content = if (versionPattern.containsMatchIn(content)) {
                versionPattern.replace(content) { "${it.groupValues[1]}\"$version\"" }
            } else {
                // Insert after software line
                val softwarePattern = Regex("""^(\s*software\s*=\s*"[^"]*"\s*)$""", RegexOption.MULTILINE)
                softwarePattern.replace(content) { "${it.value}\nversion = \"$version\"" }
            }
        }

        if (software != null) {
            val softwarePattern = Regex("""^(\s*software\s*=\s*)["'][^"']*["']""", RegexOption.MULTILINE)
            content = if (softwarePattern.containsMatchIn(content)) {
                softwarePattern.replace(content) { "${it.groupValues[1]}\"${software.name}\"" }
            } else {
                val namePattern = Regex("""^(\s*name\s*=\s*"[^"]*"\s*)$""", RegexOption.MULTILINE)
                namePattern.replace(content) { "${it.value}\nsoftware = \"${software.name}\"" }
            }
        }

        if (modloaderVersion != null) {
            val mlPattern = Regex("""^(\s*modloader_version\s*=\s*)["'][^"']*["']""", RegexOption.MULTILINE)
            if (modloaderVersion.isEmpty()) {
                // Remove the line entirely if clearing
                content = content.replace(Regex("""^\s*modloader_version\s*=\s*["'][^"']*["']\s*\n?""", RegexOption.MULTILINE), "")
            } else if (mlPattern.containsMatchIn(content)) {
                content = mlPattern.replace(content) { "${it.groupValues[1]}\"$modloaderVersion\"" }
            } else {
                // Insert after version line
                val versionPattern = Regex("""^(\s*version\s*=\s*"[^"]*"\s*)$""", RegexOption.MULTILINE)
                content = versionPattern.replace(content) { "${it.value}\nmodloader_version = \"$modloaderVersion\"" }
            }
        }

        tomlFile.writeText(content)
    }

    private fun reloadConfigs() {
        val configs = ConfigLoader.loadGroupConfigs(groupsDir)
        groupManager.reloadGroups(configs)
        println(ConsoleFormatter.successLine("Configs reloaded"))
    }

    private fun warnRunningServices(groupName: String) {
        val running = registry.getByGroup(groupName)
        if (running.isNotEmpty()) {
            println()
            println(ConsoleFormatter.warnLine("${running.size} service(s) still running with the old version."))
            println(ConsoleFormatter.hint("    Restart them to apply the update: restart ${running.first().name}"))
        }
    }

    private fun parseSoftware(input: String): ServerSoftware? = when (input.lowercase()) {
        "paper" -> ServerSoftware.PAPER
        "pufferfish" -> ServerSoftware.PUFFERFISH
        "purpur" -> ServerSoftware.PURPUR
        "folia" -> ServerSoftware.FOLIA
        "forge" -> ServerSoftware.FORGE
        "neoforge" -> ServerSoftware.NEOFORGE
        "fabric" -> ServerSoftware.FABRIC
        "velocity" -> ServerSoftware.VELOCITY
        else -> null
    }

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

    private fun printUsage() {
        println(ConsoleFormatter.error("Usage: $usage"))
        println()
        println(ConsoleFormatter.colorize("Examples:", ConsoleFormatter.BOLD))
        println("  ${CYAN}update Lobby$RESET                         ${ConsoleFormatter.hint("— interactive mode")}")
        println("  ${CYAN}update Lobby version 1.21.5$RESET          ${ConsoleFormatter.hint("— update Minecraft version")}")
        println("  ${CYAN}update Lobby software purpur$RESET         ${ConsoleFormatter.hint("— switch Paper -> Purpur")}")
        println("  ${CYAN}update Lobby software purpur 1.21.5$RESET  ${ConsoleFormatter.hint("— switch software + version")}")
    }
}
