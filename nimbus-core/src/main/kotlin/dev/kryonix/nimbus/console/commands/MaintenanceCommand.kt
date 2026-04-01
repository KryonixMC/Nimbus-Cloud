package dev.kryonix.nimbus.console.commands

import dev.kryonix.nimbus.console.Command
import dev.kryonix.nimbus.console.ConsoleFormatter
import dev.kryonix.nimbus.event.EventBus
import dev.kryonix.nimbus.event.NimbusEvent
import dev.kryonix.nimbus.group.GroupManager
import dev.kryonix.nimbus.proxy.ProxySyncManager

class MaintenanceCommand(
    private val proxySyncManager: ProxySyncManager,
    private val groupManager: GroupManager,
    private val eventBus: EventBus
) : Command {

    override val name = "maintenance"
    override val description = "Toggle maintenance mode (global or per-group)"
    override val usage = "maintenance [on|off | <group> on|off | list | add <player> | remove <player>]"

    override suspend fun execute(args: List<String>) {
        if (args.isEmpty()) {
            showStatus()
            return
        }

        when (args[0].lowercase()) {
            "on" -> toggleGlobal(true)
            "off" -> toggleGlobal(false)
            "list" -> showWhitelist()
            "add" -> {
                if (args.size < 2) {
                    println(ConsoleFormatter.error("Usage: maintenance add <player>"))
                    return
                }
                addToWhitelist(args[1])
            }
            "remove" -> {
                if (args.size < 2) {
                    println(ConsoleFormatter.error("Usage: maintenance remove <player>"))
                    return
                }
                removeFromWhitelist(args[1])
            }
            else -> {
                // Could be a group name: maintenance <group> on|off
                val groupName = args[0]
                if (args.size < 2) {
                    // Show status for specific group
                    showGroupStatus(groupName)
                    return
                }
                when (args[1].lowercase()) {
                    "on" -> toggleGroup(groupName, true)
                    "off" -> toggleGroup(groupName, false)
                    else -> println(ConsoleFormatter.error("Usage: maintenance <group> on|off"))
                }
            }
        }
    }

    private fun showStatus() {
        println(ConsoleFormatter.header("Maintenance Mode"))
        println()

        // Global
        val globalStatus = if (proxySyncManager.globalMaintenanceEnabled) {
            ConsoleFormatter.colorize("ENABLED", ConsoleFormatter.RED)
        } else {
            ConsoleFormatter.colorize("disabled", ConsoleFormatter.DIM)
        }
        println(ConsoleFormatter.field("Global", globalStatus))

        val wl = proxySyncManager.getMaintenanceWhitelist()
        if (wl.isNotEmpty()) {
            println(ConsoleFormatter.field("Whitelist", "${wl.size} player(s)"))
        }

        // Groups
        val maintenanceGroups = proxySyncManager.getMaintenanceGroups()
        if (maintenanceGroups.isNotEmpty()) {
            println()
            println(ConsoleFormatter.section("Groups in Maintenance"))
            for (group in maintenanceGroups.sorted()) {
                println("  ${ConsoleFormatter.colorize("!", ConsoleFormatter.YELLOW)} ${ConsoleFormatter.BOLD}$group${ConsoleFormatter.RESET}")
            }
        } else {
            println(ConsoleFormatter.field("Groups", ConsoleFormatter.hint("none")))
        }

        println()
        println(ConsoleFormatter.hint("Usage: maintenance [on|off | <group> on|off | list | add/remove <player>]"))
    }

    private fun showGroupStatus(groupName: String) {
        val group = groupManager.getGroup(groupName)
        if (group == null) {
            println(ConsoleFormatter.error("Group '$groupName' not found."))
            return
        }

        val inMaintenance = proxySyncManager.isGroupInMaintenance(groupName)
        val status = if (inMaintenance) {
            ConsoleFormatter.colorize("ENABLED", ConsoleFormatter.RED)
        } else {
            ConsoleFormatter.colorize("disabled", ConsoleFormatter.DIM)
        }
        println(ConsoleFormatter.field("Maintenance", "$status ${ConsoleFormatter.DIM}($groupName)${ConsoleFormatter.RESET}"))
    }

    private suspend fun toggleGlobal(enabled: Boolean) {
        val changed = proxySyncManager.setGlobalMaintenance(enabled)
        if (!changed) {
            val state = if (enabled) "already enabled" else "already disabled"
            println(ConsoleFormatter.warn("Global maintenance is $state."))
            return
        }

        if (enabled) {
            eventBus.emit(NimbusEvent.MaintenanceEnabled("global"))
            println(ConsoleFormatter.successLine("Global maintenance ${ConsoleFormatter.BOLD}enabled${ConsoleFormatter.RESET}."))
            println(ConsoleFormatter.hint("  Players without bypass will be disconnected by the proxy."))
        } else {
            eventBus.emit(NimbusEvent.MaintenanceDisabled("global"))
            println(ConsoleFormatter.successLine("Global maintenance ${ConsoleFormatter.BOLD}disabled${ConsoleFormatter.RESET}."))
        }
    }

    private suspend fun toggleGroup(groupName: String, enabled: Boolean) {
        val group = groupManager.getGroup(groupName)
        if (group == null) {
            println(ConsoleFormatter.error("Group '$groupName' not found."))
            return
        }

        val changed = proxySyncManager.setGroupMaintenance(groupName, enabled)
        if (!changed) {
            val state = if (enabled) "already in maintenance" else "not in maintenance"
            println(ConsoleFormatter.warn("Group '$groupName' is $state."))
            return
        }

        if (enabled) {
            eventBus.emit(NimbusEvent.MaintenanceEnabled(groupName))
            println(ConsoleFormatter.successLine("Group ${ConsoleFormatter.BOLD}$groupName${ConsoleFormatter.RESET} maintenance ${ConsoleFormatter.BOLD}enabled${ConsoleFormatter.RESET}."))
            println(ConsoleFormatter.hint("  Players will not be able to join $groupName servers."))
        } else {
            eventBus.emit(NimbusEvent.MaintenanceDisabled(groupName))
            println(ConsoleFormatter.successLine("Group ${ConsoleFormatter.BOLD}$groupName${ConsoleFormatter.RESET} maintenance ${ConsoleFormatter.BOLD}disabled${ConsoleFormatter.RESET}."))
        }
    }

    private fun showWhitelist() {
        val wl = proxySyncManager.getMaintenanceWhitelist()
        if (wl.isEmpty()) {
            println(ConsoleFormatter.emptyState("Maintenance whitelist is empty."))
            println(ConsoleFormatter.hint("Add players with: maintenance add <player>"))
            return
        }

        println(ConsoleFormatter.header("Maintenance Whitelist"))
        for (entry in wl.sorted()) {
            println("  ${ConsoleFormatter.success("+")} $entry")
        }
        println(ConsoleFormatter.hint("${wl.size} player(s) can bypass maintenance."))
    }

    private fun addToWhitelist(entry: String) {
        val added = proxySyncManager.addToMaintenanceWhitelist(entry)
        if (added) {
            println(ConsoleFormatter.successLine("Added ${ConsoleFormatter.BOLD}$entry${ConsoleFormatter.RESET} to maintenance whitelist."))
        } else {
            println(ConsoleFormatter.warn("'$entry' is already whitelisted."))
        }
    }

    private fun removeFromWhitelist(entry: String) {
        val removed = proxySyncManager.removeFromMaintenanceWhitelist(entry)
        if (removed) {
            println(ConsoleFormatter.successLine("Removed ${ConsoleFormatter.BOLD}$entry${ConsoleFormatter.RESET} from maintenance whitelist."))
        } else {
            println(ConsoleFormatter.warn("'$entry' is not whitelisted."))
        }
    }
}
