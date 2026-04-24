package dev.nimbuspowered.nimbus.module.perms.commands

import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.module.api.CommandOutput
import dev.nimbuspowered.nimbus.module.perms.PermsEvents
import dev.nimbuspowered.nimbus.module.perms.PermissionManager

internal class GroupSubcommands(
    private val permissionManager: PermissionManager,
    private val eventBus: EventBus
) {

    // ════════════════════════════════════════════════════════════
    // Console execution (rich ANSI formatting via ConsoleFormatter)
    // ════════════════════════════════════════════════════════════

    suspend fun handle(args: List<String>) {
        if (args.isEmpty()) {
            printGroupUsage()
            return
        }

        when (args[0].lowercase()) {
            "list" -> groupList()
            "info" -> {
                if (args.size < 2) return println(ConsoleFormatter.error("Usage: perms group info <name>"))
                groupInfo(args[1])
            }
            "create" -> {
                if (args.size < 2) return println(ConsoleFormatter.error("Usage: perms group create <name>"))
                groupCreate(args[1])
            }
            "delete" -> {
                if (args.size < 2) return println(ConsoleFormatter.error("Usage: perms group delete <name>"))
                groupDelete(args[1])
            }
            "addperm" -> {
                if (args.size < 3) return println(ConsoleFormatter.error("Usage: perms group addperm <group> <permission>"))
                groupAddPerm(args[1], args[2])
            }
            "removeperm" -> {
                if (args.size < 3) return println(ConsoleFormatter.error("Usage: perms group removeperm <group> <permission>"))
                groupRemovePerm(args[1], args[2])
            }
            "setdefault" -> {
                if (args.size < 2) return println(ConsoleFormatter.error("Usage: perms group setdefault <group> [true/false]"))
                val value = args.getOrNull(2)?.toBooleanStrictOrNull() ?: true
                groupSetDefault(args[1], value)
            }
            "addparent" -> {
                if (args.size < 3) return println(ConsoleFormatter.error("Usage: perms group addparent <group> <parent>"))
                groupAddParent(args[1], args[2])
            }
            "removeparent" -> {
                if (args.size < 3) return println(ConsoleFormatter.error("Usage: perms group removeparent <group> <parent>"))
                groupRemoveParent(args[1], args[2])
            }
            "setprefix" -> {
                if (args.size < 3) return println(ConsoleFormatter.error("Usage: perms group setprefix <group> <prefix...>"))
                groupSetPrefix(args[1], args.drop(2).joinToString(" "))
            }
            "setsuffix" -> {
                if (args.size < 3) return println(ConsoleFormatter.error("Usage: perms group setsuffix <group> <suffix...>"))
                groupSetSuffix(args[1], args.drop(2).joinToString(" "))
            }
            "setpriority" -> {
                if (args.size < 3) return println(ConsoleFormatter.error("Usage: perms group setpriority <group> <number>"))
                val priority = args[2].toIntOrNull()
                if (priority == null) return println(ConsoleFormatter.error("Priority must be a number."))
                groupSetPriority(args[1], priority)
            }
            "setweight" -> {
                if (args.size < 3) return println(ConsoleFormatter.error("Usage: perms group setweight <group> <number>"))
                val weight = args[2].toIntOrNull()
                if (weight == null) return println(ConsoleFormatter.error("Weight must be a number."))
                groupSetWeight(args[1], weight)
            }
            "meta" -> {
                if (args.size < 2) return println(ConsoleFormatter.error("Usage: perms group meta <set|remove|list> <group> [key] [value]"))
                handleGroupMeta(args.drop(1))
            }
            else -> printGroupUsage()
        }
    }

    private fun groupList() {
        val groups = permissionManager.getAllGroups()
        if (groups.isEmpty()) {
            println(ConsoleFormatter.emptyState("No permission groups configured."))
            return
        }

        val headers = listOf("NAME", "DEFAULT", "PRIORITY", "WEIGHT", "PREFIX", "PERMISSIONS", "PARENTS")
        val rows = groups.sortedByDescending { it.priority }.map { group ->
            listOf(
                ConsoleFormatter.colorize(group.name, ConsoleFormatter.BOLD),
                if (group.default) ConsoleFormatter.colorize("yes", ConsoleFormatter.GREEN) else "no",
                group.priority.toString(),
                group.weight.toString(),
                if (group.prefix.isEmpty()) ConsoleFormatter.placeholder()
                else group.prefix,
                group.permissions.size.toString(),
                if (group.parents.isEmpty()) ConsoleFormatter.placeholder()
                else group.parents.joinToString(", ")
            )
        }

        println(ConsoleFormatter.header("Permission Groups"))
        println(ConsoleFormatter.formatTable(headers, rows))
        println(ConsoleFormatter.count(groups.size, "group"))
    }

    private fun groupInfo(name: String) {
        val group = permissionManager.getGroup(name)
        if (group == null) {
            println(ConsoleFormatter.error("Permission group '$name' not found."))
            return
        }

        println(ConsoleFormatter.header("Permission Group: ${group.name}"))
        println(ConsoleFormatter.field("Default", if (group.default) ConsoleFormatter.success("yes") else "no"))
        println(ConsoleFormatter.field("Priority", group.priority.toString()))
        println(ConsoleFormatter.field("Weight", group.weight.toString()))
        println(ConsoleFormatter.field("Prefix", if (group.prefix.isEmpty()) ConsoleFormatter.placeholder() else group.prefix))
        println(ConsoleFormatter.field("Suffix", if (group.suffix.isEmpty()) ConsoleFormatter.placeholder() else group.suffix))
        println(ConsoleFormatter.field("Parents", if (group.parents.isEmpty()) ConsoleFormatter.placeholder() else group.parents.joinToString(", ")))
        println()

        if (group.meta.isNotEmpty()) {
            println(ConsoleFormatter.section("Meta (${group.meta.size})"))
            for ((key, value) in group.meta.entries.sortedBy { it.key }) {
                println("  ${ConsoleFormatter.CYAN}$key${ConsoleFormatter.RESET} = $value")
            }
            println()
        }

        println(ConsoleFormatter.section("Permissions (${group.permissions.size})"))

        if (group.permissions.isEmpty()) {
            println("  ${ConsoleFormatter.emptyState("No permissions set.")}")
        } else {
            for (perm in group.permissions.sorted()) {
                val color = if (perm.startsWith("-")) ConsoleFormatter.RED else ConsoleFormatter.GREEN
                println("  $color$perm${ConsoleFormatter.RESET}")
            }
        }
    }

    private suspend fun groupCreate(name: String) {
        try {
            permissionManager.createGroup(name)
            permissionManager.logAudit("console", "group.create", name, "Created group '$name'")
            eventBus.emit(PermsEvents.groupCreated(name))
            println(ConsoleFormatter.success("Permission group '$name' created."))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed to create group"))
        }
    }

    private suspend fun groupDelete(name: String) {
        try {
            permissionManager.deleteGroup(name)
            permissionManager.logAudit("console", "group.delete", name, "Deleted group '$name'")
            eventBus.emit(PermsEvents.groupDeleted(name))
            println(ConsoleFormatter.success("Permission group '$name' deleted."))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed to delete group"))
        }
    }

    private suspend fun groupAddPerm(groupName: String, permission: String) {
        try {
            permissionManager.addPermission(groupName, permission)
            permissionManager.logAudit("console", "group.addperm", groupName, "Added permission '$permission'")
            eventBus.emit(PermsEvents.groupUpdated(groupName))
            println(ConsoleFormatter.success("Added '$permission' to group '$groupName'."))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed"))
        }
    }

    private suspend fun groupRemovePerm(groupName: String, permission: String) {
        try {
            permissionManager.removePermission(groupName, permission)
            permissionManager.logAudit("console", "group.removeperm", groupName, "Removed permission '$permission'")
            eventBus.emit(PermsEvents.groupUpdated(groupName))
            println(ConsoleFormatter.success("Removed '$permission' from group '$groupName'."))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed"))
        }
    }

    private suspend fun groupSetDefault(groupName: String, value: Boolean) {
        try {
            permissionManager.setDefault(groupName, value)
            eventBus.emit(PermsEvents.groupUpdated(groupName))
            println(ConsoleFormatter.success("Group '$groupName' default set to $value."))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed"))
        }
    }

    private suspend fun groupAddParent(groupName: String, parentName: String) {
        try {
            permissionManager.addParent(groupName, parentName)
            eventBus.emit(PermsEvents.groupUpdated(groupName))
            println(ConsoleFormatter.success("Added parent '$parentName' to group '$groupName'."))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed"))
        }
    }

    private suspend fun groupRemoveParent(groupName: String, parentName: String) {
        try {
            permissionManager.removeParent(groupName, parentName)
            eventBus.emit(PermsEvents.groupUpdated(groupName))
            println(ConsoleFormatter.success("Removed parent '$parentName' from group '$groupName'."))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed"))
        }
    }

    private suspend fun groupSetPrefix(groupName: String, prefix: String) {
        try {
            permissionManager.updateGroupDisplay(groupName, prefix = prefix, suffix = null, priority = null)
            eventBus.emit(PermsEvents.groupUpdated(groupName))
            println(ConsoleFormatter.success("Prefix for '$groupName' set to: $prefix"))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed"))
        }
    }

    private suspend fun groupSetSuffix(groupName: String, suffix: String) {
        try {
            permissionManager.updateGroupDisplay(groupName, prefix = null, suffix = suffix, priority = null)
            eventBus.emit(PermsEvents.groupUpdated(groupName))
            println(ConsoleFormatter.success("Suffix for '$groupName' set to: $suffix"))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed"))
        }
    }

    private suspend fun groupSetPriority(groupName: String, priority: Int) {
        try {
            permissionManager.updateGroupDisplay(groupName, prefix = null, suffix = null, priority = priority)
            eventBus.emit(PermsEvents.groupUpdated(groupName))
            println(ConsoleFormatter.success("Priority for '$groupName' set to $priority."))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed"))
        }
    }

    private suspend fun groupSetWeight(groupName: String, weight: Int) {
        try {
            permissionManager.setGroupWeight(groupName, weight)
            permissionManager.logAudit("console", "group.setweight", groupName, "Set weight to $weight")
            eventBus.emit(PermsEvents.groupUpdated(groupName))
            println(ConsoleFormatter.success("Weight for '$groupName' set to $weight."))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed"))
        }
    }

    private suspend fun handleGroupMeta(args: List<String>) {
        if (args.isEmpty()) {
            println(ConsoleFormatter.error("Usage: perms group meta <set|remove|list> <group> [key] [value]"))
            return
        }

        when (args[0].lowercase()) {
            "list" -> {
                if (args.size < 2) return println(ConsoleFormatter.error("Usage: perms group meta list <group>"))
                val meta = try { permissionManager.getGroupMeta(args[1]) } catch (e: IllegalArgumentException) {
                    return println(ConsoleFormatter.error(e.message ?: "Group not found"))
                }
                if (meta.isEmpty()) {
                    println(ConsoleFormatter.emptyState("No meta set on group '${args[1]}'."))
                } else {
                    println(ConsoleFormatter.header("Meta for group '${args[1]}'"))
                    for ((key, value) in meta.entries.sortedBy { it.key }) {
                        println("  ${ConsoleFormatter.CYAN}$key${ConsoleFormatter.RESET} = $value")
                    }
                }
            }
            "set" -> {
                if (args.size < 4) return println(ConsoleFormatter.error("Usage: perms group meta set <group> <key> <value...>"))
                val value = args.drop(3).joinToString(" ")
                try {
                    permissionManager.setGroupMeta(args[1], args[2], value)
                    eventBus.emit(PermsEvents.groupUpdated(args[1]))
                    println(ConsoleFormatter.success("Meta '${args[2]}' set to '$value' on group '${args[1]}'."))
                } catch (e: IllegalArgumentException) {
                    println(ConsoleFormatter.error(e.message ?: "Failed"))
                }
            }
            "remove" -> {
                if (args.size < 3) return println(ConsoleFormatter.error("Usage: perms group meta remove <group> <key>"))
                try {
                    permissionManager.removeGroupMeta(args[1], args[2])
                    eventBus.emit(PermsEvents.groupUpdated(args[1]))
                    println(ConsoleFormatter.success("Meta '${args[2]}' removed from group '${args[1]}'."))
                } catch (e: IllegalArgumentException) {
                    println(ConsoleFormatter.error(e.message ?: "Failed"))
                }
            }
            else -> println(ConsoleFormatter.error("Usage: perms group meta <set|remove|list> <group> [key] [value]"))
        }
    }

    // ════════════════════════════════════════════════════════════
    // Remote execution (typed output via CommandOutput for Bridge)
    // ════════════════════════════════════════════════════════════

    suspend fun handleRemote(args: List<String>, out: CommandOutput) {
        if (args.isEmpty()) {
            out.error("Usage: perms group <list|info|create|delete|addperm|removeperm|setdefault|addparent|removeparent|setprefix|setsuffix|setpriority|setweight|meta>")
            return
        }

        when (args[0].lowercase()) {
            "list" -> {
                val groups = permissionManager.getAllGroups()
                if (groups.isEmpty()) { out.info("No permission groups configured."); return }
                out.header("Permission Groups (${groups.size})")
                for (group in groups.sortedByDescending { it.priority }) {
                    val def = if (group.default) " [default]" else ""
                    val parents = if (group.parents.isEmpty()) "" else " parents: ${group.parents.joinToString(", ")}"
                    out.item("  ${group.name}$def - priority: ${group.priority}, weight: ${group.weight}, ${group.permissions.size} perm(s)$parents")
                }
            }
            "info" -> {
                if (args.size < 2) { out.error("Usage: perms group info <name>"); return }
                val group = permissionManager.getGroup(args[1])
                if (group == null) { out.error("Permission group '${args[1]}' not found."); return }
                out.header("Permission Group: ${group.name}")
                out.item("  Default: ${if (group.default) "yes" else "no"}")
                out.item("  Priority: ${group.priority}")
                out.item("  Weight: ${group.weight}")
                out.item("  Prefix: ${group.prefix.ifEmpty { "-" }}")
                out.item("  Suffix: ${group.suffix.ifEmpty { "-" }}")
                out.item("  Parents: ${if (group.parents.isEmpty()) "-" else group.parents.joinToString(", ")}")
                if (group.meta.isNotEmpty()) {
                    out.info("  Meta (${group.meta.size}):")
                    for ((key, value) in group.meta.entries.sortedBy { it.key }) {
                        out.item("    $key = $value")
                    }
                }
                out.info("  Permissions (${group.permissions.size}):")
                if (group.permissions.isEmpty()) {
                    out.info("    No permissions set.")
                } else {
                    for (perm in group.permissions.sorted()) {
                        out.item("    $perm")
                    }
                }
            }
            "create" -> {
                if (args.size < 2) { out.error("Usage: perms group create <name>"); return }
                try {
                    permissionManager.createGroup(args[1])
                    permissionManager.logAudit("bridge", "group.create", args[1], "Created group '${args[1]}'")
                    eventBus.emit(PermsEvents.groupCreated(args[1]))
                    out.success("Permission group '${args[1]}' created.")
                } catch (e: IllegalArgumentException) { out.error(e.message ?: "Failed to create group") }
            }
            "delete" -> {
                if (args.size < 2) { out.error("Usage: perms group delete <name>"); return }
                try {
                    permissionManager.deleteGroup(args[1])
                    permissionManager.logAudit("bridge", "group.delete", args[1], "Deleted group '${args[1]}'")
                    eventBus.emit(PermsEvents.groupDeleted(args[1]))
                    out.success("Permission group '${args[1]}' deleted.")
                } catch (e: IllegalArgumentException) { out.error(e.message ?: "Failed to delete group") }
            }
            "addperm" -> {
                if (args.size < 3) { out.error("Usage: perms group addperm <group> <permission>"); return }
                try {
                    permissionManager.addPermission(args[1], args[2])
                    permissionManager.logAudit("bridge", "group.addperm", args[1], "Added permission '${args[2]}'")
                    eventBus.emit(PermsEvents.groupUpdated(args[1]))
                    out.success("Added '${args[2]}' to group '${args[1]}'.")
                } catch (e: IllegalArgumentException) { out.error(e.message ?: "Failed") }
            }
            "removeperm" -> {
                if (args.size < 3) { out.error("Usage: perms group removeperm <group> <permission>"); return }
                try {
                    permissionManager.removePermission(args[1], args[2])
                    permissionManager.logAudit("bridge", "group.removeperm", args[1], "Removed permission '${args[2]}'")
                    eventBus.emit(PermsEvents.groupUpdated(args[1]))
                    out.success("Removed '${args[2]}' from group '${args[1]}'.")
                } catch (e: IllegalArgumentException) { out.error(e.message ?: "Failed") }
            }
            "setdefault" -> {
                if (args.size < 2) { out.error("Usage: perms group setdefault <group> [true/false]"); return }
                val value = args.getOrNull(2)?.toBooleanStrictOrNull() ?: true
                try {
                    permissionManager.setDefault(args[1], value)
                    eventBus.emit(PermsEvents.groupUpdated(args[1]))
                    out.success("Group '${args[1]}' default set to $value.")
                } catch (e: IllegalArgumentException) { out.error(e.message ?: "Failed") }
            }
            "addparent" -> {
                if (args.size < 3) { out.error("Usage: perms group addparent <group> <parent>"); return }
                try {
                    permissionManager.addParent(args[1], args[2])
                    eventBus.emit(PermsEvents.groupUpdated(args[1]))
                    out.success("Added parent '${args[2]}' to group '${args[1]}'.")
                } catch (e: IllegalArgumentException) { out.error(e.message ?: "Failed") }
            }
            "removeparent" -> {
                if (args.size < 3) { out.error("Usage: perms group removeparent <group> <parent>"); return }
                try {
                    permissionManager.removeParent(args[1], args[2])
                    eventBus.emit(PermsEvents.groupUpdated(args[1]))
                    out.success("Removed parent '${args[2]}' from group '${args[1]}'.")
                } catch (e: IllegalArgumentException) { out.error(e.message ?: "Failed") }
            }
            "setprefix" -> {
                if (args.size < 3) { out.error("Usage: perms group setprefix <group> <prefix...>"); return }
                val prefix = args.drop(2).joinToString(" ")
                try {
                    permissionManager.updateGroupDisplay(args[1], prefix = prefix, suffix = null, priority = null)
                    eventBus.emit(PermsEvents.groupUpdated(args[1]))
                    out.success("Prefix for '${args[1]}' set to: $prefix")
                } catch (e: IllegalArgumentException) { out.error(e.message ?: "Failed") }
            }
            "setsuffix" -> {
                if (args.size < 3) { out.error("Usage: perms group setsuffix <group> <suffix...>"); return }
                val suffix = args.drop(2).joinToString(" ")
                try {
                    permissionManager.updateGroupDisplay(args[1], prefix = null, suffix = suffix, priority = null)
                    eventBus.emit(PermsEvents.groupUpdated(args[1]))
                    out.success("Suffix for '${args[1]}' set to: $suffix")
                } catch (e: IllegalArgumentException) { out.error(e.message ?: "Failed") }
            }
            "setpriority" -> {
                if (args.size < 3) { out.error("Usage: perms group setpriority <group> <number>"); return }
                val priority = args[2].toIntOrNull()
                if (priority == null) { out.error("Priority must be a number."); return }
                try {
                    permissionManager.updateGroupDisplay(args[1], prefix = null, suffix = null, priority = priority)
                    eventBus.emit(PermsEvents.groupUpdated(args[1]))
                    out.success("Priority for '${args[1]}' set to $priority.")
                } catch (e: IllegalArgumentException) { out.error(e.message ?: "Failed") }
            }
            "setweight" -> {
                if (args.size < 3) { out.error("Usage: perms group setweight <group> <number>"); return }
                val weight = args[2].toIntOrNull()
                if (weight == null) { out.error("Weight must be a number."); return }
                try {
                    permissionManager.setGroupWeight(args[1], weight)
                    permissionManager.logAudit("bridge", "group.setweight", args[1], "Set weight to $weight")
                    eventBus.emit(PermsEvents.groupUpdated(args[1]))
                    out.success("Weight for '${args[1]}' set to $weight.")
                } catch (e: IllegalArgumentException) { out.error(e.message ?: "Failed") }
            }
            "meta" -> remoteGroupMeta(args.drop(1), out)
            else -> out.error("Unknown subcommand: ${args[0]}. Use: list, info, create, delete, addperm, removeperm, setdefault, addparent, removeparent, setprefix, setsuffix, setpriority, setweight, meta")
        }
    }

    private suspend fun remoteGroupMeta(args: List<String>, out: CommandOutput) {
        if (args.isEmpty()) { out.error("Usage: perms group meta <set|remove|list> <group> [key] [value]"); return }
        when (args[0].lowercase()) {
            "list" -> {
                if (args.size < 2) { out.error("Usage: perms group meta list <group>"); return }
                val meta = try { permissionManager.getGroupMeta(args[1]) } catch (e: IllegalArgumentException) {
                    out.error(e.message ?: "Group not found"); return
                }
                if (meta.isEmpty()) { out.info("No meta set on group '${args[1]}'."); return }
                out.header("Meta for group '${args[1]}'")
                for ((key, value) in meta.entries.sortedBy { it.key }) { out.item("  $key = $value") }
            }
            "set" -> {
                if (args.size < 4) { out.error("Usage: perms group meta set <group> <key> <value...>"); return }
                val value = args.drop(3).joinToString(" ")
                try {
                    permissionManager.setGroupMeta(args[1], args[2], value)
                    eventBus.emit(PermsEvents.groupUpdated(args[1]))
                    out.success("Meta '${args[2]}' set to '$value' on group '${args[1]}'.")
                } catch (e: IllegalArgumentException) { out.error(e.message ?: "Failed") }
            }
            "remove" -> {
                if (args.size < 3) { out.error("Usage: perms group meta remove <group> <key>"); return }
                try {
                    permissionManager.removeGroupMeta(args[1], args[2])
                    eventBus.emit(PermsEvents.groupUpdated(args[1]))
                    out.success("Meta '${args[2]}' removed from group '${args[1]}'.")
                } catch (e: IllegalArgumentException) { out.error(e.message ?: "Failed") }
            }
            else -> out.error("Usage: perms group meta <set|remove|list> <group> [key] [value]")
        }
    }

    // ── Help ────────────────────────────────────────────────

    private fun printGroupUsage() {
        println(ConsoleFormatter.error("Usage: perms group <list|info|create|delete|addperm|removeperm|setdefault|addparent|removeparent|setprefix|setsuffix|setpriority|setweight|meta>"))
    }
}
