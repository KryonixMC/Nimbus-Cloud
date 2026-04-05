package dev.kryonix.nimbus.console.commands

import dev.kryonix.nimbus.console.Command
import dev.kryonix.nimbus.console.ConsoleFormatter
import dev.kryonix.nimbus.console.InteractivePicker
import dev.kryonix.nimbus.group.GroupManager
import dev.kryonix.nimbus.module.ModuleInfo
import dev.kryonix.nimbus.module.ModuleManager
import dev.kryonix.nimbus.module.ModulePluginInfo
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
                println(ConsoleFormatter.hint("Usage: $usage"))
            }
        }
    }

    private fun list() {
        val loaded = moduleManager.getModules()
        val available = moduleManager.discoverAvailable()
        val loadedIds = loaded.map { it.id }.toSet()

        // Build plugin info from module.properties metadata
        val pluginsByModuleId = available.associate { it.id to it.plugins }

        println(ConsoleFormatter.header("Modules"))
        println()

        if (loaded.isNotEmpty()) {
            for (module in loaded) {
                val pluginInfo = pluginsByModuleId[module.id]
                val pluginHint = if (!pluginInfo.isNullOrEmpty()) {
                    " ${ConsoleFormatter.hint("(plugins: ${pluginInfo.joinToString(", ") { it.displayName }})")}"
                } else ""
                println("  ${ConsoleFormatter.success("●")} ${ConsoleFormatter.info(module.name)} ${ConsoleFormatter.hint("v${module.version}")} — ${module.description}$pluginHint")
            }
        }

        val notInstalled = available.filter { it.id !in loadedIds }
        if (notInstalled.isNotEmpty()) {
            if (loaded.isNotEmpty()) println()
            for (mod in notInstalled) {
                println("  ${ConsoleFormatter.hint("○ ${mod.name}")} ${ConsoleFormatter.hint("— ${mod.description}")}")
            }
            println()
            println(ConsoleFormatter.hint("  Install with: ${ConsoleFormatter.info("modules install")}"))
        }

        if (loaded.isEmpty() && notInstalled.isEmpty()) {
            println(ConsoleFormatter.emptyState("  No modules found."))
        }
    }

    // ── Interactive picker ──────────────────────────────────

    private fun installInteractive() {
        val available = moduleManager.discoverAvailable()
        val loadedIds = moduleManager.getModules().map { it.id }.toSet()
        val notInstalled = available.filter { it.id !in loadedIds }

        if (notInstalled.isEmpty()) {
            println(ConsoleFormatter.hint("All available modules are already installed."))
            return
        }

        val options = notInstalled.map { InteractivePicker.Option(it.id, it.name, it.description) }
        val selected = mutableSetOf<String>()
        if (!InteractivePicker.pickMany(terminal, options, selected)) {
            println(ConsoleFormatter.hint("Cancelled."))
            return
        }

        if (selected.isEmpty()) {
            println(ConsoleFormatter.hint("No modules selected."))
            return
        }

        var installed = 0
        for (id in selected) {
            val result = moduleManager.install(id)
            if (result == ModuleManager.InstallResult.INSTALLED) {
                val info = available.find { it.id == id }
                println("  ${ConsoleFormatter.success("●")} Installed ${ConsoleFormatter.info(info?.name ?: id)}")
                installed++
                if (info != null) offerPluginDeploy(info)
            }
        }
        if (installed > 0) {
            println()
            println(ConsoleFormatter.warn("  Restart Nimbus to activate ${if (installed == 1) "the module" else "$installed modules"}."))
        }
    }

    // ── Direct install/uninstall ────────────────────────────

    private fun installDirect(id: String) {
        val result = moduleManager.install(id)
        when (result) {
            ModuleManager.InstallResult.INSTALLED -> {
                val info = moduleManager.discoverAvailable().find { it.id == id }
                println("${ConsoleFormatter.success("●")} Installed ${ConsoleFormatter.info(info?.name ?: id)}")
                if (info != null) offerPluginDeploy(info)
                println(ConsoleFormatter.warn("  Restart Nimbus to activate the module."))
            }
            ModuleManager.InstallResult.ALREADY_INSTALLED -> {
                println(ConsoleFormatter.hint("Module '$id' is already installed."))
            }
            ModuleManager.InstallResult.NOT_FOUND -> {
                println(ConsoleFormatter.error("Module '$id' not found."))
                val available = moduleManager.discoverAvailable()
                if (available.isNotEmpty()) {
                    println(ConsoleFormatter.hint("  Available: ${available.joinToString(", ") { it.id }}"))
                }
            }
        }
    }

    private fun uninstall(id: String) {
        if (!moduleManager.uninstall(id)) {
            println(ConsoleFormatter.error("Module '$id' is not installed."))
            return
        }
        println("${ConsoleFormatter.error("●")} Uninstalled ${ConsoleFormatter.info(id)}")
        if (moduleManager.isLoaded(id)) {
            println(ConsoleFormatter.warn("  Module is still active until restart."))
        }

        // Read plugin info from available modules metadata
        val info = moduleManager.discoverAvailable().find { it.id == id }
        if (info != null) warnOrphanedPlugins(info)
    }

    // ── Plugin linkage ─────────────────────────────────────

    /**
     * After installing a module, offer to deploy its related plugins
     * to all backend groups via `global/plugins/`.
     */
    private fun offerPluginDeploy(info: ModuleInfo) {
        val plugins = info.plugins
        if (plugins.isEmpty() || templatesDir == null) return

        val globalPluginsDir = templatesDir.resolve("global").resolve("plugins")

        // Check which plugins are not yet deployed globally
        val missing = plugins.filter { p ->
            !globalPluginsDir.resolve(p.fileName).exists()
        }
        if (missing.isEmpty()) return

        println()
        val pluginNames = missing.joinToString(", ") { ConsoleFormatter.info(it.displayName) }
        println(ConsoleFormatter.hint("  ${info.name} needs server-side plugins: $pluginNames"))

        val options = listOf(
            InteractivePicker.Option("global", "Deploy to all backends", "install to templates/global/plugins/"),
            InteractivePicker.Option("skip", "Skip for now", "install later with: plugins install")
        )
        val choice = InteractivePicker.pickOne(terminal, options)
        if (choice == 0) {
            if (!globalPluginsDir.exists()) globalPluginsDir.createDirectories()
            for (plugin in missing) {
                val resource = javaClass.classLoader.getResourceAsStream(plugin.resourcePath)
                if (resource != null) {
                    resource.use { Files.copy(it, globalPluginsDir.resolve(plugin.fileName), StandardCopyOption.REPLACE_EXISTING) }
                    println("    ${ConsoleFormatter.success("+")} ${plugin.fileName} → global/plugins/")
                }
            }
        }
    }

    /**
     * After uninstalling a module, warn about orphaned server-side plugins.
     */
    private fun warnOrphanedPlugins(info: ModuleInfo) {
        val plugins = info.plugins
        if (plugins.isEmpty() || templatesDir == null) return

        // Find where these plugins are installed
        val locations = mutableListOf<Pair<ModulePluginInfo, String>>()

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
        println(ConsoleFormatter.warn("  These server-side plugins will no longer function without the module:"))
        for ((plugin, loc) in locations) {
            println("    ${ConsoleFormatter.hint("●")} ${plugin.displayName} ${ConsoleFormatter.hint("in $loc")}")
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
                    println("    ${ConsoleFormatter.error("-")} ${plugin.fileName} removed from $loc")
                }
            }
        }
    }
}
