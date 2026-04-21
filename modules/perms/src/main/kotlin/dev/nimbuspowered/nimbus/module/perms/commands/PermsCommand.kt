package dev.nimbuspowered.nimbus.module.perms.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.module.perms.PermsEvents
import dev.nimbuspowered.nimbus.module.api.CommandOutput
import dev.nimbuspowered.nimbus.module.api.CompletionMeta
import dev.nimbuspowered.nimbus.module.api.CompletionType
import dev.nimbuspowered.nimbus.module.api.SubcommandMeta
import dev.nimbuspowered.nimbus.module.perms.PermissionManager

class PermsCommand(
    private val permissionManager: PermissionManager,
    private val eventBus: EventBus
) : Command {

    override val name = "perms"
    override val description = "Manage permission groups and player assignments"
    override val usage = "perms <group|user|track|audit|reload> [subcommand] [args]"
    override val permission = "nimbus.cloud.perms"

    override val subcommandMeta: List<SubcommandMeta> get() = listOf(
        // Group subcommands
        SubcommandMeta("group list", "List all groups", "perms group list"),
        SubcommandMeta("group info", "Show group details", "perms group info <name>",
            listOf(CompletionMeta(0, CompletionType.PERMISSION_GROUP))),
        SubcommandMeta("group create", "Create a group", "perms group create <name>"),
        SubcommandMeta("group delete", "Delete a group", "perms group delete <name>",
            listOf(CompletionMeta(0, CompletionType.PERMISSION_GROUP))),
        SubcommandMeta("group addperm", "Add permission", "perms group addperm <group> <perm>",
            listOf(CompletionMeta(0, CompletionType.PERMISSION_GROUP))),
        SubcommandMeta("group removeperm", "Remove permission", "perms group removeperm <group> <perm>",
            listOf(CompletionMeta(0, CompletionType.PERMISSION_GROUP))),
        SubcommandMeta("group setdefault", "Set as default group", "perms group setdefault <group> [true/false]",
            listOf(CompletionMeta(0, CompletionType.PERMISSION_GROUP))),
        SubcommandMeta("group addparent", "Add inheritance", "perms group addparent <group> <parent>",
            listOf(CompletionMeta(0, CompletionType.PERMISSION_GROUP), CompletionMeta(1, CompletionType.PERMISSION_GROUP))),
        SubcommandMeta("group removeparent", "Remove inheritance", "perms group removeparent <group> <parent>",
            listOf(CompletionMeta(0, CompletionType.PERMISSION_GROUP), CompletionMeta(1, CompletionType.PERMISSION_GROUP))),
        SubcommandMeta("group setprefix", "Set display prefix", "perms group setprefix <group> <prefix...>",
            listOf(CompletionMeta(0, CompletionType.PERMISSION_GROUP))),
        SubcommandMeta("group setsuffix", "Set display suffix", "perms group setsuffix <group> <suffix...>",
            listOf(CompletionMeta(0, CompletionType.PERMISSION_GROUP))),
        SubcommandMeta("group setpriority", "Set display priority", "perms group setpriority <group> <number>",
            listOf(CompletionMeta(0, CompletionType.PERMISSION_GROUP))),
        SubcommandMeta("group setweight", "Set conflict weight", "perms group setweight <group> <number>",
            listOf(CompletionMeta(0, CompletionType.PERMISSION_GROUP))),
        SubcommandMeta("group meta set", "Set meta value", "perms group meta set <group> <key> <value>",
            listOf(CompletionMeta(0, CompletionType.PERMISSION_GROUP))),
        SubcommandMeta("group meta remove", "Remove meta key", "perms group meta remove <group> <key>",
            listOf(CompletionMeta(0, CompletionType.PERMISSION_GROUP))),
        SubcommandMeta("group meta list", "List meta values", "perms group meta list <group>",
            listOf(CompletionMeta(0, CompletionType.PERMISSION_GROUP))),
        // User subcommands
        SubcommandMeta("user list", "List all players", "perms user list"),
        SubcommandMeta("user info", "Show player perms", "perms user info <name|uuid>",
            listOf(CompletionMeta(0, CompletionType.PLAYER))),
        SubcommandMeta("user check", "Debug permission check", "perms user check <name|uuid> <perm>",
            listOf(CompletionMeta(0, CompletionType.PLAYER))),
        SubcommandMeta("user addgroup", "Assign group", "perms user addgroup <name|uuid> <group>",
            listOf(CompletionMeta(0, CompletionType.PLAYER), CompletionMeta(1, CompletionType.PERMISSION_GROUP))),
        SubcommandMeta("user removegroup", "Remove group", "perms user removegroup <name|uuid> <group>",
            listOf(CompletionMeta(0, CompletionType.PLAYER), CompletionMeta(1, CompletionType.PERMISSION_GROUP))),
        SubcommandMeta("user promote", "Promote on track", "perms user promote <name|uuid> <track>",
            listOf(CompletionMeta(0, CompletionType.PLAYER))),
        SubcommandMeta("user demote", "Demote on track", "perms user demote <name|uuid> <track>",
            listOf(CompletionMeta(0, CompletionType.PLAYER))),
        SubcommandMeta("user meta set", "Set player meta", "perms user meta set <id> <key> <value>",
            listOf(CompletionMeta(0, CompletionType.PLAYER))),
        SubcommandMeta("user meta remove", "Remove player meta", "perms user meta remove <id> <key>",
            listOf(CompletionMeta(0, CompletionType.PLAYER))),
        SubcommandMeta("user meta list", "List player meta", "perms user meta list <id>",
            listOf(CompletionMeta(0, CompletionType.PLAYER))),
        // Track subcommands
        SubcommandMeta("track list", "List all tracks", "perms track list"),
        SubcommandMeta("track info", "Show track details", "perms track info <name>"),
        SubcommandMeta("track create", "Create track (comma-separated)", "perms track create <name> <groups>"),
        SubcommandMeta("track delete", "Delete track", "perms track delete <name>"),
        // Other
        SubcommandMeta("audit", "Show audit log", "perms audit [limit]"),
        SubcommandMeta("reload", "Reload from database", "perms reload"),
    )

    // ════════════════════════════════════════════════════════════
    // Console execution (rich ANSI formatting via ConsoleFormatter)
    // ════════════════════════════════════════════════════════════

    override suspend fun execute(args: List<String>) {
        if (args.isEmpty()) {
            printUsage()
            return
        }

        when (args[0].lowercase()) {
            "group" -> handleGroup(args.drop(1))
            "user" -> handleUser(args.drop(1))
            "track" -> handleTrack(args.drop(1))
            "audit" -> handleAudit(args.drop(1))
            "reload" -> handleReload()
            else -> printUsage()
        }
    }

    private suspend fun handleGroup(args: List<String>) {
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

    private suspend fun handleUser(args: List<String>) {
        if (args.isEmpty()) {
            printUserUsage()
            return
        }

        when (args[0].lowercase()) {
            "info" -> {
                if (args.size < 2) return println(ConsoleFormatter.error("Usage: perms user info <name|uuid>"))
                userInfo(args[1])
            }
            "addgroup" -> {
                if (args.size < 3) return println(ConsoleFormatter.error("Usage: perms user addgroup <name|uuid> <group>"))
                userAddGroup(args[1], args[2])
            }
            "removegroup" -> {
                if (args.size < 3) return println(ConsoleFormatter.error("Usage: perms user removegroup <name|uuid> <group>"))
                userRemoveGroup(args[1], args[2])
            }
            "list" -> userList()
            "check" -> {
                if (args.size < 3) return println(ConsoleFormatter.error("Usage: perms user check <name|uuid> <permission>"))
                userCheck(args[1], args[2])
            }
            "promote" -> {
                if (args.size < 3) return println(ConsoleFormatter.error("Usage: perms user promote <name|uuid> <track>"))
                userPromote(args[1], args[2])
            }
            "demote" -> {
                if (args.size < 3) return println(ConsoleFormatter.error("Usage: perms user demote <name|uuid> <track>"))
                userDemote(args[1], args[2])
            }
            "meta" -> {
                if (args.size < 2) return println(ConsoleFormatter.error("Usage: perms user meta <set|remove|list> <name|uuid> [key] [value]"))
                handleUserMeta(args.drop(1))
            }
            else -> printUserUsage()
        }
    }

    // ── Group subcommands ───────────────────────────────────

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

    // ── Group Meta ──────────────────────────────────────────

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

    // ── User subcommands ────────────────────────────────────

    private fun userInfo(identifier: String) {
        val uuid: String
        val playerName: String
        val groups: List<String>

        if (identifier.contains("-")) {
            uuid = identifier
            val entry = permissionManager.getPlayer(identifier)
            playerName = entry?.name ?: identifier
            groups = entry?.groups ?: emptyList()
        } else {
            val result = permissionManager.getPlayerByName(identifier)
            if (result != null) {
                uuid = result.first
                playerName = result.second.name
                groups = result.second.groups
            } else {
                println(ConsoleFormatter.error("Player '$identifier' not found. Use UUID for new players."))
                return
            }
        }

        val effective = permissionManager.getEffectivePermissions(uuid)
        val defaultGroup = permissionManager.getDefaultGroup()
        val display = permissionManager.getPlayerDisplay(uuid)
        val meta = permissionManager.getPlayerMeta(uuid)

        println(ConsoleFormatter.header("Player: $playerName"))
        println(ConsoleFormatter.field("UUID", uuid))
        println(ConsoleFormatter.field("Groups", if (groups.isEmpty()) ConsoleFormatter.placeholder() else groups.joinToString(", ")))
        if (defaultGroup != null) {
            println(ConsoleFormatter.field("Default", defaultGroup.name))
        }
        println(ConsoleFormatter.field("Display", "${display.prefix}$playerName${display.suffix} ${ConsoleFormatter.hint("(group: ${display.groupName}, priority: ${display.priority})")}"))

        if (meta.isNotEmpty()) {
            println()
            println(ConsoleFormatter.section("Meta (${meta.size})"))
            for ((key, value) in meta.entries.sortedBy { it.key }) {
                println("  ${ConsoleFormatter.CYAN}$key${ConsoleFormatter.RESET} = $value")
            }
        }

        println()
        println(ConsoleFormatter.section("Effective Permissions (${effective.size})"))

        if (effective.isEmpty()) {
            println("  ${ConsoleFormatter.emptyState("No permissions.")}")
        } else {
            for (perm in effective.sorted()) {
                println("  ${ConsoleFormatter.GREEN}$perm${ConsoleFormatter.RESET}")
            }
        }
    }

    private fun userCheck(identifier: String, permission: String) {
        val (uuid, playerName) = resolvePlayerReadOnly(identifier) ?: return

        val result = permissionManager.checkPermission(uuid, permission)
        val statusIcon = if (result.result) ConsoleFormatter.success("GRANTED") else ConsoleFormatter.error("DENIED")

        println(ConsoleFormatter.header("Permission Check: $playerName"))
        println(ConsoleFormatter.field("Permission", permission))
        println(ConsoleFormatter.field("Result", statusIcon))
        println(ConsoleFormatter.field("Reason", result.reason))

        if (result.chain.isNotEmpty()) {
            println()
            println(ConsoleFormatter.section("Resolution Chain"))
            for (step in result.chain) {
                val icon = if (step.granted) "${ConsoleFormatter.GREEN}+" else "${ConsoleFormatter.RED}-"
                println("  $icon ${step.source}${ConsoleFormatter.RESET} → ${ConsoleFormatter.BOLD}${step.permission}${ConsoleFormatter.RESET} ${ConsoleFormatter.hint("(${step.type})")}")
            }
        }
    }

    private suspend fun userAddGroup(identifier: String, groupName: String) {
        val (uuid, playerName) = resolvePlayer(identifier) ?: return
        try {
            permissionManager.setPlayerGroup(uuid, playerName, groupName)
            permissionManager.logAudit("console", "user.addgroup", uuid, "Added group '$groupName' to '$playerName'")
            eventBus.emit(PermsEvents.playerUpdated(uuid, playerName))
            println(ConsoleFormatter.success("Added group '$groupName' to player '$playerName'."))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed"))
        }
    }

    private suspend fun userRemoveGroup(identifier: String, groupName: String) {
        val (uuid, playerName) = resolvePlayer(identifier) ?: return
        try {
            permissionManager.removePlayerGroup(uuid, groupName)
            permissionManager.logAudit("console", "user.removegroup", uuid, "Removed group '$groupName' from '$playerName'")
            eventBus.emit(PermsEvents.playerUpdated(uuid, playerName))
            println(ConsoleFormatter.success("Removed group '$groupName' from player '$playerName'."))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed"))
        }
    }

    private suspend fun userPromote(identifier: String, trackName: String) {
        val (uuid, playerName) = resolvePlayer(identifier) ?: return
        try {
            val newGroup = permissionManager.promote(uuid, trackName)
            if (newGroup != null) {
                permissionManager.logAudit("console", "user.promote", uuid, "Promoted '$playerName' to '$newGroup' on track '$trackName'")
                eventBus.emit(PermsEvents.playerPromoted(uuid, playerName, trackName, newGroup))
                eventBus.emit(PermsEvents.playerUpdated(uuid, playerName))
                println(ConsoleFormatter.success("Promoted '$playerName' to '$newGroup' on track '$trackName'."))
            } else {
                println(ConsoleFormatter.warn("Player '$playerName' is already at the highest rank on track '$trackName'."))
            }
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed"))
        }
    }

    private suspend fun userDemote(identifier: String, trackName: String) {
        val (uuid, playerName) = resolvePlayer(identifier) ?: return
        try {
            val newGroup = permissionManager.demote(uuid, trackName)
            if (newGroup != null) {
                permissionManager.logAudit("console", "user.demote", uuid, "Demoted '$playerName' to '$newGroup' on track '$trackName'")
                eventBus.emit(PermsEvents.playerDemoted(uuid, playerName, trackName, newGroup))
                eventBus.emit(PermsEvents.playerUpdated(uuid, playerName))
                println(ConsoleFormatter.success("Demoted '$playerName' to '$newGroup' on track '$trackName'."))
            } else {
                println(ConsoleFormatter.warn("Player '$playerName' is already at the lowest rank on track '$trackName'."))
            }
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed"))
        }
    }

    private fun userList() {
        val players = permissionManager.getAllPlayers()
        if (players.isEmpty()) {
            println(ConsoleFormatter.emptyState("No player assignments."))
            return
        }

        val headers = listOf("NAME", "UUID", "GROUPS")
        val rows = players.entries.sortedBy { it.value.name }.map { (uuid, entry) ->
            listOf(
                ConsoleFormatter.colorize(entry.name, ConsoleFormatter.BOLD),
                ConsoleFormatter.colorize(uuid, ConsoleFormatter.DIM),
                if (entry.groups.isEmpty()) "-" else entry.groups.joinToString(", ")
            )
        }

        println(ConsoleFormatter.header("Player Assignments"))
        println(ConsoleFormatter.formatTable(headers, rows))
        println(ConsoleFormatter.count(players.size, "player"))
    }

    // ── User Meta ───────────────────────────────────────────

    private suspend fun handleUserMeta(args: List<String>) {
        if (args.isEmpty()) {
            println(ConsoleFormatter.error("Usage: perms user meta <set|remove|list> <name|uuid> [key] [value]"))
            return
        }

        when (args[0].lowercase()) {
            "list" -> {
                if (args.size < 2) return println(ConsoleFormatter.error("Usage: perms user meta list <name|uuid>"))
                val (uuid, playerName) = resolvePlayerReadOnly(args[1]) ?: return
                val meta = permissionManager.getPlayerMeta(uuid)
                if (meta.isEmpty()) {
                    println(ConsoleFormatter.emptyState("No meta set on player '$playerName'."))
                } else {
                    println(ConsoleFormatter.header("Meta for player '$playerName'"))
                    for ((key, value) in meta.entries.sortedBy { it.key }) {
                        println("  ${ConsoleFormatter.CYAN}$key${ConsoleFormatter.RESET} = $value")
                    }
                }
            }
            "set" -> {
                if (args.size < 4) return println(ConsoleFormatter.error("Usage: perms user meta set <name|uuid> <key> <value...>"))
                val (uuid, playerName) = resolvePlayerReadOnly(args[1]) ?: return
                val value = args.drop(3).joinToString(" ")
                try {
                    permissionManager.setPlayerMeta(uuid, args[2], value)
                    eventBus.emit(PermsEvents.playerUpdated(uuid, playerName))
                    println(ConsoleFormatter.success("Meta '${args[2]}' set to '$value' on player '$playerName'."))
                } catch (e: IllegalArgumentException) {
                    println(ConsoleFormatter.error(e.message ?: "Failed"))
                }
            }
            "remove" -> {
                if (args.size < 3) return println(ConsoleFormatter.error("Usage: perms user meta remove <name|uuid> <key>"))
                val (uuid, playerName) = resolvePlayerReadOnly(args[1]) ?: return
                try {
                    permissionManager.removePlayerMeta(uuid, args[2])
                    eventBus.emit(PermsEvents.playerUpdated(uuid, playerName))
                    println(ConsoleFormatter.success("Meta '${args[2]}' removed from player '$playerName'."))
                } catch (e: IllegalArgumentException) {
                    println(ConsoleFormatter.error(e.message ?: "Failed"))
                }
            }
            else -> println(ConsoleFormatter.error("Usage: perms user meta <set|remove|list> <name|uuid> [key] [value]"))
        }
    }

    // ── Track subcommands ───────────────────────────────────

    private suspend fun handleTrack(args: List<String>) {
        if (args.isEmpty()) {
            printTrackUsage()
            return
        }

        when (args[0].lowercase()) {
            "list" -> trackList()
            "info" -> {
                if (args.size < 2) return println(ConsoleFormatter.error("Usage: perms track info <name>"))
                trackInfo(args[1])
            }
            "create" -> {
                if (args.size < 3) return println(ConsoleFormatter.error("Usage: perms track create <name> <group1,group2,group3,...>"))
                trackCreate(args[1], args[2])
            }
            "delete" -> {
                if (args.size < 2) return println(ConsoleFormatter.error("Usage: perms track delete <name>"))
                trackDelete(args[1])
            }
            else -> printTrackUsage()
        }
    }

    private fun trackList() {
        val tracks = permissionManager.getAllTracks()
        if (tracks.isEmpty()) {
            println(ConsoleFormatter.emptyState("No permission tracks configured."))
            return
        }

        val headers = listOf("NAME", "GROUPS")
        val rows = tracks.sortedBy { it.name }.map { track ->
            listOf(
                ConsoleFormatter.colorize(track.name, ConsoleFormatter.BOLD),
                track.groups.joinToString(" → ")
            )
        }

        println(ConsoleFormatter.header("Permission Tracks"))
        println(ConsoleFormatter.formatTable(headers, rows))
        println(ConsoleFormatter.count(tracks.size, "track"))
    }

    private fun trackInfo(name: String) {
        val track = permissionManager.getTrack(name)
        if (track == null) {
            println(ConsoleFormatter.error("Track '$name' not found."))
            return
        }

        println(ConsoleFormatter.header("Track: ${track.name}"))
        println(ConsoleFormatter.field("Groups", track.groups.joinToString(" → ")))
        println()
        println(ConsoleFormatter.section("Rank Order"))
        for ((i, group) in track.groups.withIndex()) {
            val exists = permissionManager.getGroup(group) != null
            val status = if (exists) ConsoleFormatter.GREEN else ConsoleFormatter.RED
            println("  ${i + 1}. $status$group${ConsoleFormatter.RESET}")
        }
    }

    private suspend fun trackCreate(name: String, groupsStr: String) {
        val groups = groupsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        try {
            val track = permissionManager.createTrack(name, groups)
            permissionManager.logAudit("console", "track.create", name, "Created track with groups: ${track.groups.joinToString(", ")}")
            eventBus.emit(PermsEvents.trackCreated(name))
            println(ConsoleFormatter.success("Track '$name' created: ${track.groups.joinToString(" → ")}"))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed to create track"))
        }
    }

    private suspend fun trackDelete(name: String) {
        try {
            permissionManager.deleteTrack(name)
            permissionManager.logAudit("console", "track.delete", name, "Deleted track '$name'")
            eventBus.emit(PermsEvents.trackDeleted(name))
            println(ConsoleFormatter.success("Track '$name' deleted."))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed to delete track"))
        }
    }

    // ── Audit ───────────────────────────────────────────────

    private suspend fun handleAudit(args: List<String>) {
        val limit = args.firstOrNull()?.toIntOrNull() ?: 20
        val entries = permissionManager.getAuditLog(limit)
        if (entries.isEmpty()) {
            println(ConsoleFormatter.emptyState("No audit log entries."))
            return
        }

        val headers = listOf("TIME", "ACTOR", "ACTION", "TARGET", "DETAILS")
        val rows = entries.map {
            listOf(
                ConsoleFormatter.colorize(it.timestamp.substringAfter("T").substringBefore("."), ConsoleFormatter.DIM),
                it.actor,
                ConsoleFormatter.colorize(it.action, ConsoleFormatter.CYAN),
                ConsoleFormatter.colorize(it.target, ConsoleFormatter.BOLD),
                if (it.details.length > 50) it.details.take(47) + "..." else it.details
            )
        }

        println(ConsoleFormatter.header("Permission Audit Log"))
        println(ConsoleFormatter.formatTable(headers, rows))
        println(ConsoleFormatter.count(entries.size, "entry"))
    }

    // ── Helpers ─────────────────────────────────────────────

    private fun resolvePlayer(identifier: String): Pair<String, String>? {
        if (identifier.contains("-")) {
            val entry = permissionManager.getPlayer(identifier)
            return if (entry != null) identifier to entry.name
            else identifier to identifier // UUID not seen before
        } else {
            val result = permissionManager.getPlayerByName(identifier)
            if (result != null) return result.first to result.second.name
            println(ConsoleFormatter.error("Player '$identifier' not found. Use UUID for first-time assignment."))
            return null
        }
    }

    private fun resolvePlayerReadOnly(identifier: String): Pair<String, String>? {
        if (identifier.contains("-")) {
            val entry = permissionManager.getPlayer(identifier)
            return if (entry != null) identifier to entry.name
            else {
                println(ConsoleFormatter.error("Player '$identifier' not found."))
                null
            }
        } else {
            val result = permissionManager.getPlayerByName(identifier)
            if (result != null) return result.first to result.second.name
            println(ConsoleFormatter.error("Player '$identifier' not found."))
            return null
        }
    }

    // ── Reload ──────────────────────────────────────────────

    private suspend fun handleReload() {
        permissionManager.reload()
        println(ConsoleFormatter.success("Permissions reloaded."))
    }

    // ── Help ────────────────────────────────────────────────

    private fun printUsage() {
        val pad = 52
        println(ConsoleFormatter.header("Permissions"))
        println(ConsoleFormatter.commandEntry("perms group list", "List all groups", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms group info <group>", "Show group details", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms group create <name>", "Create a group", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms group delete <name>", "Delete a group", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms group addperm <group> <perm>", "Add permission", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms group removeperm <group> <perm>", "Remove permission", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms group setdefault <group>", "Set as default group", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms group addparent <group> <parent>", "Add inheritance", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms group removeparent <group> <parent>", "Remove inheritance", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms group setprefix <group> <prefix...>", "Set display prefix", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms group setsuffix <group> <suffix...>", "Set display suffix", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms group setpriority <group> <number>", "Set display priority", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms group setweight <group> <number>", "Set conflict weight", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms group meta set <group> <key> <value>", "Set meta value", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms group meta remove <group> <key>", "Remove meta key", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms group meta list <group>", "List meta values", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms user list", "List all players", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms user info <name|uuid>", "Show player perms", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms user check <name|uuid> <perm>", "Debug permission check", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms user addgroup <name|uuid> <group>", "Assign group", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms user removegroup <name|uuid> <group>", "Remove group", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms user promote <name|uuid> <track>", "Promote on track", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms user demote <name|uuid> <track>", "Demote on track", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms user meta set <id> <key> <value>", "Set player meta", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms user meta remove <id> <key>", "Remove player meta", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms user meta list <id>", "List player meta", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms track list", "List all tracks", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms track info <name>", "Show track details", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms track create <name> <groups>", "Create track (comma-separated)", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms track delete <name>", "Delete track", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms audit [limit]", "Show audit log", padWidth = pad))
        println(ConsoleFormatter.commandEntry("perms reload", "Reload from database", padWidth = pad))
    }

    private fun printGroupUsage() {
        println(ConsoleFormatter.error("Usage: perms group <list|info|create|delete|addperm|removeperm|setdefault|addparent|removeparent|setprefix|setsuffix|setpriority|setweight|meta>"))
    }

    private fun printUserUsage() {
        println(ConsoleFormatter.error("Usage: perms user <list|info|check|addgroup|removegroup|promote|demote|meta>"))
    }

    private fun printTrackUsage() {
        println(ConsoleFormatter.error("Usage: perms track <list|info|create|delete>"))
    }

    // ════════════════════════════════════════════════════════════
    // Remote execution (typed output via CommandOutput for Bridge)
    // ════════════════════════════════════════════════════════════

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        if (args.isEmpty()) {
            remoteHelp(output)
            return true
        }

        when (args[0].lowercase()) {
            "group" -> remoteGroup(args.drop(1), output)
            "user" -> remoteUser(args.drop(1), output)
            "track" -> remoteTrack(args.drop(1), output)
            "audit" -> remoteAudit(args.drop(1), output)
            "reload" -> {
                permissionManager.reload()
                output.success("Permissions reloaded.")
            }
            else -> remoteHelp(output)
        }
        return true
    }

    // ── Remote Group ────────────────────────────────────────

    private suspend fun remoteGroup(args: List<String>, out: CommandOutput) {
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

    // ── Remote User ─────────────────────────────────────────

    private suspend fun remoteUser(args: List<String>, out: CommandOutput) {
        if (args.isEmpty()) {
            out.error("Usage: perms user <list|info|check|addgroup|removegroup|promote|demote|meta>")
            return
        }

        when (args[0].lowercase()) {
            "list" -> {
                val players = permissionManager.getAllPlayers()
                if (players.isEmpty()) { out.info("No player assignments."); return }
                out.header("Player Assignments (${players.size})")
                for ((uuid, entry) in players.entries.sortedBy { it.value.name }) {
                    val groups = if (entry.groups.isEmpty()) "-" else entry.groups.joinToString(", ")
                    out.item("  ${entry.name} ($uuid) - groups: $groups")
                }
            }
            "info" -> {
                if (args.size < 2) { out.error("Usage: perms user info <name|uuid>"); return }
                val (uuid, playerName, groups) = resolvePlayerForRemote(args[1], out) ?: return
                val effective = permissionManager.getEffectivePermissions(uuid)
                val display = permissionManager.getPlayerDisplay(uuid)
                val meta = permissionManager.getPlayerMeta(uuid)
                out.header("Player: $playerName")
                out.item("  UUID: $uuid")
                out.item("  Groups: ${if (groups.isEmpty()) "-" else groups.joinToString(", ")}")
                out.item("  Display: ${display.prefix}$playerName${display.suffix} (group: ${display.groupName})")
                if (meta.isNotEmpty()) {
                    out.info("  Meta (${meta.size}):")
                    for ((key, value) in meta.entries.sortedBy { it.key }) { out.item("    $key = $value") }
                }
                out.info("  Effective Permissions (${effective.size}):")
                if (effective.isEmpty()) { out.info("    No permissions.") }
                else { for (perm in effective.sorted()) { out.item("    $perm") } }
            }
            "check" -> {
                if (args.size < 3) { out.error("Usage: perms user check <name|uuid> <permission>"); return }
                val resolved = resolvePlayerReadOnlyForRemote(args[1], out) ?: return
                val result = permissionManager.checkPermission(resolved.first, args[2])
                out.header("Permission Check: ${resolved.second}")
                out.item("  Permission: ${args[2]}")
                if (result.result) out.success("  Result: GRANTED") else out.error("  Result: DENIED")
                out.item("  Reason: ${result.reason}")
                if (result.chain.isNotEmpty()) {
                    out.info("  Resolution Chain:")
                    for (step in result.chain) {
                        val icon = if (step.granted) "+" else "-"
                        out.item("    $icon ${step.source} -> ${step.permission} (${step.type})")
                    }
                }
            }
            "addgroup" -> {
                if (args.size < 3) { out.error("Usage: perms user addgroup <name|uuid> <group>"); return }
                val (uuid, playerName) = resolvePlayerForRemoteWrite(args[1], out) ?: return
                try {
                    permissionManager.setPlayerGroup(uuid, playerName, args[2])
                    permissionManager.logAudit("bridge", "user.addgroup", uuid, "Added group '${args[2]}' to '$playerName'")
                    eventBus.emit(PermsEvents.playerUpdated(uuid, playerName))
                    out.success("Added group '${args[2]}' to player '$playerName'.")
                } catch (e: IllegalArgumentException) { out.error(e.message ?: "Failed") }
            }
            "removegroup" -> {
                if (args.size < 3) { out.error("Usage: perms user removegroup <name|uuid> <group>"); return }
                val (uuid, playerName) = resolvePlayerForRemoteWrite(args[1], out) ?: return
                try {
                    permissionManager.removePlayerGroup(uuid, args[2])
                    permissionManager.logAudit("bridge", "user.removegroup", uuid, "Removed group '${args[2]}' from '$playerName'")
                    eventBus.emit(PermsEvents.playerUpdated(uuid, playerName))
                    out.success("Removed group '${args[2]}' from player '$playerName'.")
                } catch (e: IllegalArgumentException) { out.error(e.message ?: "Failed") }
            }
            "promote" -> {
                if (args.size < 3) { out.error("Usage: perms user promote <name|uuid> <track>"); return }
                val (uuid, playerName) = resolvePlayerForRemoteWrite(args[1], out) ?: return
                try {
                    val newGroup = permissionManager.promote(uuid, args[2])
                    if (newGroup != null) {
                        permissionManager.logAudit("bridge", "user.promote", uuid, "Promoted '$playerName' to '$newGroup' on track '${args[2]}'")
                        eventBus.emit(PermsEvents.playerPromoted(uuid, playerName, args[2], newGroup))
                        eventBus.emit(PermsEvents.playerUpdated(uuid, playerName))
                        out.success("Promoted '$playerName' to '$newGroup' on track '${args[2]}'.")
                    } else {
                        out.info("Player '$playerName' is already at the highest rank on track '${args[2]}'.")
                    }
                } catch (e: IllegalArgumentException) { out.error(e.message ?: "Failed") }
            }
            "demote" -> {
                if (args.size < 3) { out.error("Usage: perms user demote <name|uuid> <track>"); return }
                val (uuid, playerName) = resolvePlayerForRemoteWrite(args[1], out) ?: return
                try {
                    val newGroup = permissionManager.demote(uuid, args[2])
                    if (newGroup != null) {
                        permissionManager.logAudit("bridge", "user.demote", uuid, "Demoted '$playerName' to '$newGroup' on track '${args[2]}'")
                        eventBus.emit(PermsEvents.playerDemoted(uuid, playerName, args[2], newGroup))
                        eventBus.emit(PermsEvents.playerUpdated(uuid, playerName))
                        out.success("Demoted '$playerName' to '$newGroup' on track '${args[2]}'.")
                    } else {
                        out.info("Player '$playerName' is already at the lowest rank on track '${args[2]}'.")
                    }
                } catch (e: IllegalArgumentException) { out.error(e.message ?: "Failed") }
            }
            "meta" -> remoteUserMeta(args.drop(1), out)
            else -> out.error("Usage: perms user <list|info|check|addgroup|removegroup|promote|demote|meta>")
        }
    }

    private suspend fun remoteUserMeta(args: List<String>, out: CommandOutput) {
        if (args.isEmpty()) { out.error("Usage: perms user meta <set|remove|list> <name|uuid> [key] [value]"); return }
        when (args[0].lowercase()) {
            "list" -> {
                if (args.size < 2) { out.error("Usage: perms user meta list <name|uuid>"); return }
                val resolved = resolvePlayerReadOnlyForRemote(args[1], out) ?: return
                val meta = permissionManager.getPlayerMeta(resolved.first)
                if (meta.isEmpty()) { out.info("No meta set on player '${resolved.second}'."); return }
                out.header("Meta for player '${resolved.second}'")
                for ((key, value) in meta.entries.sortedBy { it.key }) { out.item("  $key = $value") }
            }
            "set" -> {
                if (args.size < 4) { out.error("Usage: perms user meta set <name|uuid> <key> <value...>"); return }
                val resolved = resolvePlayerReadOnlyForRemote(args[1], out) ?: return
                val value = args.drop(3).joinToString(" ")
                try {
                    permissionManager.setPlayerMeta(resolved.first, args[2], value)
                    eventBus.emit(PermsEvents.playerUpdated(resolved.first, resolved.second))
                    out.success("Meta '${args[2]}' set to '$value' on player '${resolved.second}'.")
                } catch (e: IllegalArgumentException) { out.error(e.message ?: "Failed") }
            }
            "remove" -> {
                if (args.size < 3) { out.error("Usage: perms user meta remove <name|uuid> <key>"); return }
                val resolved = resolvePlayerReadOnlyForRemote(args[1], out) ?: return
                try {
                    permissionManager.removePlayerMeta(resolved.first, args[2])
                    eventBus.emit(PermsEvents.playerUpdated(resolved.first, resolved.second))
                    out.success("Meta '${args[2]}' removed from player '${resolved.second}'.")
                } catch (e: IllegalArgumentException) { out.error(e.message ?: "Failed") }
            }
            else -> out.error("Usage: perms user meta <set|remove|list> <name|uuid> [key] [value]")
        }
    }

    // ── Remote Track ────────────────────────────────────────

    private suspend fun remoteTrack(args: List<String>, out: CommandOutput) {
        if (args.isEmpty()) { out.error("Usage: perms track <list|info|create|delete>"); return }
        when (args[0].lowercase()) {
            "list" -> {
                val tracks = permissionManager.getAllTracks()
                if (tracks.isEmpty()) { out.info("No permission tracks configured."); return }
                out.header("Permission Tracks (${tracks.size})")
                for (track in tracks.sortedBy { it.name }) {
                    out.item("  ${track.name}: ${track.groups.joinToString(" -> ")}")
                }
            }
            "info" -> {
                if (args.size < 2) { out.error("Usage: perms track info <name>"); return }
                val track = permissionManager.getTrack(args[1])
                if (track == null) { out.error("Track '${args[1]}' not found."); return }
                out.header("Track: ${track.name}")
                out.item("  Groups: ${track.groups.joinToString(" -> ")}")
                out.info("  Rank Order:")
                for ((i, group) in track.groups.withIndex()) {
                    out.item("    ${i + 1}. $group")
                }
            }
            "create" -> {
                if (args.size < 3) { out.error("Usage: perms track create <name> <group1,group2,...>"); return }
                val groups = args[2].split(",").map { it.trim() }.filter { it.isNotEmpty() }
                try {
                    val track = permissionManager.createTrack(args[1], groups)
                    permissionManager.logAudit("bridge", "track.create", args[1], "Created track with groups: ${track.groups.joinToString(", ")}")
                    eventBus.emit(PermsEvents.trackCreated(args[1]))
                    out.success("Track '${args[1]}' created: ${track.groups.joinToString(" -> ")}")
                } catch (e: IllegalArgumentException) { out.error(e.message ?: "Failed to create track") }
            }
            "delete" -> {
                if (args.size < 2) { out.error("Usage: perms track delete <name>"); return }
                try {
                    permissionManager.deleteTrack(args[1])
                    permissionManager.logAudit("bridge", "track.delete", args[1], "Deleted track '${args[1]}'")
                    eventBus.emit(PermsEvents.trackDeleted(args[1]))
                    out.success("Track '${args[1]}' deleted.")
                } catch (e: IllegalArgumentException) { out.error(e.message ?: "Failed to delete track") }
            }
            else -> out.error("Usage: perms track <list|info|create|delete>")
        }
    }

    // ── Remote Audit ────────────────────────────────────────

    private suspend fun remoteAudit(args: List<String>, out: CommandOutput) {
        val limit = args.firstOrNull()?.toIntOrNull() ?: 20
        val entries = permissionManager.getAuditLog(limit)
        if (entries.isEmpty()) { out.info("No audit log entries."); return }
        out.header("Permission Audit Log (${entries.size})")
        for (entry in entries) {
            val time = entry.timestamp.substringAfter("T").substringBefore(".")
            out.item("  [$time] ${entry.actor} ${entry.action} ${entry.target}: ${entry.details}")
        }
    }

    // ── Remote Helpers ──────────────────────────────────────

    /** Resolve player for remote read — returns (uuid, name, groups) or null. */
    private fun resolvePlayerForRemote(identifier: String, out: CommandOutput): Triple<String, String, List<String>>? {
        if (identifier.contains("-")) {
            val entry = permissionManager.getPlayer(identifier)
            return Triple(identifier, entry?.name ?: identifier, entry?.groups ?: emptyList())
        }
        val result = permissionManager.getPlayerByName(identifier)
        if (result != null) return Triple(result.first, result.second.name, result.second.groups)
        out.error("Player '$identifier' not found. Use UUID for new players.")
        return null
    }

    /** Resolve player for remote read-only — returns (uuid, name) or null. */
    private fun resolvePlayerReadOnlyForRemote(identifier: String, out: CommandOutput): Pair<String, String>? {
        if (identifier.contains("-")) {
            val entry = permissionManager.getPlayer(identifier)
            if (entry != null) return identifier to entry.name
            out.error("Player '$identifier' not found.")
            return null
        }
        val result = permissionManager.getPlayerByName(identifier)
        if (result != null) return result.first to result.second.name
        out.error("Player '$identifier' not found.")
        return null
    }

    /** Resolve player for remote write — returns (uuid, name) or null. Allows unknown UUIDs. */
    private fun resolvePlayerForRemoteWrite(identifier: String, out: CommandOutput): Pair<String, String>? {
        if (identifier.contains("-")) {
            val entry = permissionManager.getPlayer(identifier)
            return identifier to (entry?.name ?: identifier)
        }
        val result = permissionManager.getPlayerByName(identifier)
        if (result != null) return result.first to result.second.name
        out.error("Player '$identifier' not found. Use UUID for first-time assignment.")
        return null
    }

    // ── Remote Help ─────────────────────────────────────────

    private fun remoteHelp(out: CommandOutput) {
        out.header("Permissions")
        for (sub in subcommandMeta) {
            out.item("  ${sub.usage} - ${sub.description}")
        }
    }
}
