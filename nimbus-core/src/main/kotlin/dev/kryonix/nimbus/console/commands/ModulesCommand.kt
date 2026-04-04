package dev.kryonix.nimbus.console.commands

import dev.kryonix.nimbus.console.Command
import dev.kryonix.nimbus.console.ConsoleFormatter
import dev.kryonix.nimbus.console.ConsoleFormatter.BOLD
import dev.kryonix.nimbus.console.ConsoleFormatter.CYAN
import dev.kryonix.nimbus.console.ConsoleFormatter.DIM
import dev.kryonix.nimbus.console.ConsoleFormatter.GREEN
import dev.kryonix.nimbus.console.ConsoleFormatter.RED
import dev.kryonix.nimbus.console.ConsoleFormatter.RESET
import dev.kryonix.nimbus.console.ConsoleFormatter.YELLOW
import dev.kryonix.nimbus.console.InteractivePicker
import dev.kryonix.nimbus.group.GroupManager
import dev.kryonix.nimbus.module.ModuleInfo
import dev.kryonix.nimbus.module.ModuleManager
import org.jline.terminal.Terminal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

class ModulesCommand(
    private val moduleManager: ModuleManager,
    private val terminal: Terminal,
    private val groupManager: GroupManager? = null,
    private val templatesDir: Path? = null
) : Command {
    override val name = "modules"
    override val description = "Manage controller modules"
    override val usage = "modules [list|install|uninstall <id>]"

    /**
     * Maps module IDs to the server-side plugins they need deployed on backend groups.
     * Key = module id, Value = list of (plugin resource path, target filename)
     */
    private val modulePlugins = mapOf(
        "perms" to listOf(PluginMapping("plugins/nimbus-perms.jar", "nimbus-perms.jar", "NimbusPerms")),
        "display" to listOf(
            PluginMapping("plugins/nimbus-display.jar", "nimbus-display.jar", "NimbusDisplay"),
            PluginMapping("plugins/FancyNpcs.jar", "FancyNpcs.jar", "FancyNpcs")
        )
    )

    private data class PluginMapping(val resource: String, val fileName: String, val displayName: String)

    override suspend fun execute(args: List<String>) {
        val sub = args.firstOrNull()?.lowercase() ?: "list"
        when (sub) {
            "list", "ls" -> list()
            "install", "add" -> {
                val id = args.getOrNull(1)
                if (id != null) {
                    installDirect(id.lowercase())
                } else {
                    installInteractive()
                }
            }
            "uninstall", "remove" -> {
                val id = args.getOrNull(1)
                if (id == null) {
                    println(ConsoleFormatter.error("Usage: modules uninstall <module-id>"))
                    return
                }
                uninstall(id.lowercase())
            }
            else -> {
                println(ConsoleFormatter.error("Unknown subcommand: $sub"))
                println("${DIM}Usage: $usage$RESET")
            }
        }
    }

    private fun list() {
        val loaded = moduleManager.getModules()
        val available = moduleManager.discoverAvailable()
        val loadedIds = loaded.map { it.id }.toSet()

        println("${BOLD}Modules:$RESET")
        println()

        if (loaded.isNotEmpty()) {
            for (module in loaded) {
                val pluginInfo = modulePlugins[module.id]
                val pluginHint = if (pluginInfo != null) {
                    " ${DIM}(plugins: ${pluginInfo.joinToString(", ") { it.displayName }})$RESET"
                } else ""
                println("  ${GREEN}●$RESET ${CYAN}${module.name}$RESET ${DIM}v${module.version}$RESET — ${module.description}$pluginHint")
            }
        }

        val notInstalled = available.filter { it.id !in loadedIds }
        if (notInstalled.isNotEmpty()) {
            if (loaded.isNotEmpty()) println()
            for (mod in notInstalled) {
                println("  ${DIM}○ ${mod.name}$RESET ${DIM}— ${mod.description}$RESET")
            }
            println()
            println("  ${DIM}Install with: ${CYAN}modules install$RESET")
        }

        if (loaded.isEmpty() && notInstalled.isEmpty()) {
            println("  ${DIM}No modules found.$RESET")
        }
    }

    // ── Interactive picker ──────────────────────────────────

    private fun installInteractive() {
        val available = moduleManager.discoverAvailable()
        val loadedIds = moduleManager.getModules().map { it.id }.toSet()
        val notInstalled = available.filter { it.id !in loadedIds }

        if (notInstalled.isEmpty()) {
            println("${DIM}All available modules are already installed.$RESET")
            return
        }

        val options = notInstalled.map { InteractivePicker.Option(it.id, it.name, it.description) }
        val selected = mutableSetOf<String>()
        if (!InteractivePicker.pickMany(terminal, options, selected)) {
            println("${DIM}Cancelled.$RESET")
            return
        }

        if (selected.isEmpty()) {
            println("${DIM}No modules selected.$RESET")
            return
        }

        var installed = 0
        for (id in selected) {
            val result = moduleManager.install(id)
            if (result == ModuleManager.InstallResult.INSTALLED) {
                val info = available.find { it.id == id }
                println("  ${GREEN}●$RESET Installed ${CYAN}${info?.name ?: id}$RESET")
                installed++
                offerPluginDeploy(id, info?.name ?: id)
            }
        }
        if (installed > 0) {
            println()
            println("  ${YELLOW}Restart Nimbus to activate ${if (installed == 1) "the module" else "$installed modules"}.$RESET")
        }
    }

    // ── Direct install/uninstall ────────────────────────────

    private fun installDirect(id: String) {
        val result = moduleManager.install(id)
        when (result) {
            ModuleManager.InstallResult.INSTALLED -> {
                val info = moduleManager.discoverAvailable().find { it.id == id }
                println("${GREEN}●$RESET Installed ${CYAN}${info?.name ?: id}$RESET")
                offerPluginDeploy(id, info?.name ?: id)
                println("  ${YELLOW}Restart Nimbus to activate the module.$RESET")
            }
            ModuleManager.InstallResult.ALREADY_INSTALLED -> {
                println("${DIM}Module '$id' is already installed.$RESET")
            }
            ModuleManager.InstallResult.NOT_FOUND -> {
                println(ConsoleFormatter.error("Module '$id' not found."))
                val available = moduleManager.discoverAvailable()
                if (available.isNotEmpty()) {
                    println("  ${DIM}Available: ${available.joinToString(", ") { it.id }}$RESET")
                }
            }
        }
    }

    private fun uninstall(id: String) {
        if (!moduleManager.uninstall(id)) {
            println(ConsoleFormatter.error("Module '$id' is not installed."))
            return
        }
        println("${RED}●$RESET Uninstalled ${CYAN}$id$RESET")
        if (moduleManager.isLoaded(id)) {
            println("  ${YELLOW}Module is still active until restart.$RESET")
        }

        warnOrphanedPlugins(id)
    }

    // ── Plugin linkage ─────────────────────────────────────

    /**
     * After installing a module, offer to deploy its related plugins
     * to all backend groups via `global/plugins/`.
     */
    private fun offerPluginDeploy(moduleId: String, moduleName: String) {
        val plugins = modulePlugins[moduleId] ?: return
        if (templatesDir == null) return

        val globalPluginsDir = templatesDir.resolve("global").resolve("plugins")

        // Check which plugins are not yet deployed globally
        val missing = plugins.filter { p ->
            !globalPluginsDir.resolve(p.fileName).exists()
        }
        if (missing.isEmpty()) return

        println()
        val pluginNames = missing.joinToString(", ") { "${CYAN}${it.displayName}$RESET" }
        println("  ${DIM}$moduleName needs server-side plugins: $pluginNames$RESET")

        val options = listOf(
            InteractivePicker.Option("global", "Deploy to all backends", "install to templates/global/plugins/"),
            InteractivePicker.Option("skip", "Skip for now", "install later with: plugins install")
        )
        val choice = InteractivePicker.pickOne(terminal, options)
        if (choice == 0) {
            if (!globalPluginsDir.exists()) globalPluginsDir.createDirectories()
            for (plugin in missing) {
                val resource = javaClass.classLoader.getResourceAsStream(plugin.resource)
                if (resource != null) {
                    resource.use { Files.copy(it, globalPluginsDir.resolve(plugin.fileName), StandardCopyOption.REPLACE_EXISTING) }
                    println("    ${GREEN}+$RESET ${plugin.fileName} → global/plugins/")
                }
            }
        }
    }

    /**
     * After uninstalling a module, warn about orphaned server-side plugins.
     */
    private fun warnOrphanedPlugins(moduleId: String) {
        val plugins = modulePlugins[moduleId] ?: return
        if (templatesDir == null) return

        // Find where these plugins are installed
        val locations = mutableListOf<Pair<PluginMapping, String>>()

        // Check global
        val globalDir = templatesDir.resolve("global").resolve("plugins")
        if (globalDir.exists()) {
            for (p in plugins) {
                if (globalDir.resolve(p.fileName).exists()) {
                    locations.add(p to "global")
                }
            }
        }

        // Check per-group templates
        if (groupManager != null) {
            for (group in groupManager.getAllGroups()) {
                val groupDir = templatesDir.resolve(group.name.lowercase()).resolve("plugins")
                if (groupDir.exists()) {
                    for (p in plugins) {
                        if (groupDir.resolve(p.fileName).exists()) {
                            locations.add(p to group.name)
                        }
                    }
                }
            }
        }

        if (locations.isEmpty()) return

        println()
        println("  ${YELLOW}These server-side plugins will no longer function without the module:$RESET")
        for ((plugin, loc) in locations) {
            println("    ${DIM}●$RESET ${plugin.displayName} ${DIM}in $loc$RESET")
        }

        val options = listOf(
            InteractivePicker.Option("remove", "Remove all orphaned plugins", "delete from templates"),
            InteractivePicker.Option("keep", "Keep for now", "remove later with: plugins remove")
        )
        val choice = InteractivePicker.pickOne(terminal, options)
        if (choice == 0) {
            for ((plugin, loc) in locations) {
                val dir = when (loc) {
                    "global" -> templatesDir.resolve("global").resolve("plugins")
                    else -> templatesDir.resolve(loc.lowercase()).resolve("plugins")
                }
                val file = dir.resolve(plugin.fileName)
                if (file.exists()) {
                    Files.deleteIfExists(file)
                    println("    ${RED}-$RESET ${plugin.fileName} removed from $loc")
                }
            }
        }
    }
}
