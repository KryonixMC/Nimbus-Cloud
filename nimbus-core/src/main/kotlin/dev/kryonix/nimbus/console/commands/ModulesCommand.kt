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
import dev.kryonix.nimbus.module.ModuleManager

class ModulesCommand(private val moduleManager: ModuleManager) : Command {
    override val name = "modules"
    override val description = "Manage controller modules"
    override val usage = "modules [list|install <id>|uninstall <id>]"

    override suspend fun execute(args: List<String>) {
        val sub = args.firstOrNull()?.lowercase() ?: "list"
        when (sub) {
            "list", "ls" -> list()
            "install", "add" -> {
                val id = args.getOrNull(1)
                if (id == null) {
                    println(ConsoleFormatter.error("Usage: modules install <module-id>"))
                    return
                }
                install(id.lowercase())
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
                println("  ${GREEN}●$RESET ${CYAN}${module.name}$RESET ${DIM}v${module.version}$RESET — ${module.description}")
            }
        }

        // Show available but not installed modules
        val notInstalled = available.filter { it.id !in loadedIds }
        if (notInstalled.isNotEmpty()) {
            if (loaded.isNotEmpty()) println()
            for (mod in notInstalled) {
                println("  ${DIM}○ ${mod.name}$RESET ${DIM}— ${mod.description}$RESET")
            }
            println()
            println("  ${DIM}Install with: ${CYAN}modules install <id>$RESET")
        }

        if (loaded.isEmpty() && notInstalled.isEmpty()) {
            println("  ${DIM}No modules found.$RESET")
        }
    }

    private fun install(id: String) {
        val result = moduleManager.install(id)
        when (result) {
            ModuleManager.InstallResult.INSTALLED -> {
                val info = moduleManager.discoverAvailable().find { it.id == id }
                println("${GREEN}●$RESET Installed ${CYAN}${info?.name ?: id}$RESET")
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
    }
}
