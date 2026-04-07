package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.module.CommandOutput
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.proxy.ProxySyncManager

class MaintenanceCommand(
    private val proxySyncManager: ProxySyncManager,
    private val groupManager: GroupManager,
    private val eventBus: EventBus
) : Command {

    override val name = "maintenance"
    override val description = "Toggle maintenance mode (global or per-group)"
    override val usage = "maintenance [on|off | <group> on|off | list | add <player> | remove <player>]"

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        if (args.isEmpty()) {
            showStatus(output)
            return true
        }

        when (args[0].lowercase()) {
            "on" -> {
                val changed = proxySyncManager.setGlobalMaintenance(true)
                if (!changed) {
                    output.info("Global maintenance is already enabled.")
                } else {
                    eventBus.emit(NimbusEvent.MaintenanceEnabled("global"))
                    output.success("Global maintenance enabled.")
                    output.info("Players without bypass will be disconnected by the proxy.")
                }
            }
            "off" -> {
                val changed = proxySyncManager.setGlobalMaintenance(false)
                if (!changed) {
                    output.info("Global maintenance is already disabled.")
                } else {
                    eventBus.emit(NimbusEvent.MaintenanceDisabled("global"))
                    output.success("Global maintenance disabled.")
                }
            }
            "list" -> {
                val wl = proxySyncManager.getMaintenanceWhitelist()
                if (wl.isEmpty()) {
                    output.info("Maintenance whitelist is empty.")
                    output.info("Add players with: maintenance add <player>")
                } else {
                    output.header("Maintenance Whitelist")
                    for (entry in wl.sorted()) {
                        output.text("  ${ConsoleFormatter.success("+")} $entry")
                    }
                    output.info("${wl.size} player(s) can bypass maintenance.")
                }
            }
            "add" -> {
                if (args.size < 2) {
                    output.error("Usage: maintenance add <player>")
                } else {
                    val added = proxySyncManager.addToMaintenanceWhitelist(args[1])
                    if (added) {
                        output.success("Added ${args[1]} to maintenance whitelist.")
                    } else {
                        output.info("'${args[1]}' is already whitelisted.")
                    }
                }
            }
            "remove" -> {
                if (args.size < 2) {
                    output.error("Usage: maintenance remove <player>")
                } else {
                    val removed = proxySyncManager.removeFromMaintenanceWhitelist(args[1])
                    if (removed) {
                        output.success("Removed ${args[1]} from maintenance whitelist.")
                    } else {
                        output.info("'${args[1]}' is not whitelisted.")
                    }
                }
            }
            else -> {
                // Could be a group name: maintenance <group> on|off
                val groupName = args[0]
                if (args.size < 2) {
                    // Show status for specific group
                    val group = groupManager.getGroup(groupName)
                    if (group == null) {
                        output.error("Group '$groupName' not found.")
                    } else {
                        val inMaintenance = proxySyncManager.isGroupInMaintenance(groupName)
                        val status = if (inMaintenance) {
                            ConsoleFormatter.colorize("ENABLED", ConsoleFormatter.RED)
                        } else {
                            ConsoleFormatter.colorize("disabled", ConsoleFormatter.DIM)
                        }
                        output.text(ConsoleFormatter.field("Maintenance", "$status ${ConsoleFormatter.DIM}($groupName)${ConsoleFormatter.RESET}"))
                    }
                    return true
                }
                when (args[1].lowercase()) {
                    "on" -> {
                        val group = groupManager.getGroup(groupName)
                        if (group == null) {
                            output.error("Group '$groupName' not found.")
                        } else {
                            val changed = proxySyncManager.setGroupMaintenance(groupName, true)
                            if (!changed) {
                                output.info("Group '$groupName' is already in maintenance.")
                            } else {
                                eventBus.emit(NimbusEvent.MaintenanceEnabled(groupName))
                                output.success("Group $groupName maintenance enabled.")
                                output.info("Players will not be able to join $groupName servers.")
                            }
                        }
                    }
                    "off" -> {
                        val group = groupManager.getGroup(groupName)
                        if (group == null) {
                            output.error("Group '$groupName' not found.")
                        } else {
                            val changed = proxySyncManager.setGroupMaintenance(groupName, false)
                            if (!changed) {
                                output.info("Group '$groupName' is not in maintenance.")
                            } else {
                                eventBus.emit(NimbusEvent.MaintenanceDisabled(groupName))
                                output.success("Group $groupName maintenance disabled.")
                            }
                        }
                    }
                    else -> output.error("Usage: maintenance <group> on|off")
                }
            }
        }
        return true
    }

    private fun showStatus(output: CommandOutput) {
        output.header("Maintenance Mode")
        output.text("")

        // Global
        val globalStatus = if (proxySyncManager.globalMaintenanceEnabled) {
            ConsoleFormatter.colorize("ENABLED", ConsoleFormatter.RED)
        } else {
            ConsoleFormatter.colorize("disabled", ConsoleFormatter.DIM)
        }
        output.text(ConsoleFormatter.field("Global", globalStatus))

        val wl = proxySyncManager.getMaintenanceWhitelist()
        if (wl.isNotEmpty()) {
            output.text(ConsoleFormatter.field("Whitelist", "${wl.size} player(s)"))
        }

        // Groups
        val maintenanceGroups = proxySyncManager.getMaintenanceGroups()
        if (maintenanceGroups.isNotEmpty()) {
            output.text("")
            output.text(ConsoleFormatter.section("Groups in Maintenance"))
            for (group in maintenanceGroups.sorted()) {
                output.text("  ${ConsoleFormatter.colorize("!", ConsoleFormatter.YELLOW)} ${ConsoleFormatter.BOLD}$group${ConsoleFormatter.RESET}")
            }
        } else {
            output.text(ConsoleFormatter.field("Groups", ConsoleFormatter.hint("none")))
        }

        output.text("")
        output.info("Usage: maintenance [on|off | <group> on|off | list | add/remove <player>]")
    }

    override suspend fun execute(args: List<String>) {
        execute(args, ConsoleOutput())
    }
}
