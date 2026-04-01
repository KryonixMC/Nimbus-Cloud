package dev.kryonix.nimbus.console.commands

import dev.kryonix.nimbus.console.Command
import dev.kryonix.nimbus.console.ConsoleFormatter
import dev.kryonix.nimbus.event.EventBus
import dev.kryonix.nimbus.event.NimbusEvent
import dev.kryonix.nimbus.permissions.PermissionManager

class PermsCommand(
    private val permissionManager: PermissionManager,
    private val eventBus: EventBus
) : Command {

    override val name = "perms"
    override val description = "Manage permission groups and player assignments"
    override val usage = "perms <group|user|track|audit|reload> [subcommand] [args]"

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
            eventBus.emit(NimbusEvent.PermissionGroupCreated(name))
            println(ConsoleFormatter.success("Permission group '$name' created."))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed to create group"))
        }
    }

    private suspend fun groupDelete(name: String) {
        try {
            permissionManager.deleteGroup(name)
            permissionManager.logAudit("console", "group.delete", name, "Deleted group '$name'")
            eventBus.emit(NimbusEvent.PermissionGroupDeleted(name))
            println(ConsoleFormatter.success("Permission group '$name' deleted."))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed to delete group"))
        }
    }

    private suspend fun groupAddPerm(groupName: String, permission: String) {
        try {
            permissionManager.addPermission(groupName, permission)
            permissionManager.logAudit("console", "group.addperm", groupName, "Added permission '$permission'")
            eventBus.emit(NimbusEvent.PermissionGroupUpdated(groupName))
            println(ConsoleFormatter.success("Added '$permission' to group '$groupName'."))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed"))
        }
    }

    private suspend fun groupRemovePerm(groupName: String, permission: String) {
        try {
            permissionManager.removePermission(groupName, permission)
            permissionManager.logAudit("console", "group.removeperm", groupName, "Removed permission '$permission'")
            eventBus.emit(NimbusEvent.PermissionGroupUpdated(groupName))
            println(ConsoleFormatter.success("Removed '$permission' from group '$groupName'."))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed"))
        }
    }

    private suspend fun groupSetDefault(groupName: String, value: Boolean) {
        try {
            permissionManager.setDefault(groupName, value)
            eventBus.emit(NimbusEvent.PermissionGroupUpdated(groupName))
            println(ConsoleFormatter.success("Group '$groupName' default set to $value."))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed"))
        }
    }

    private suspend fun groupAddParent(groupName: String, parentName: String) {
        try {
            permissionManager.addParent(groupName, parentName)
            eventBus.emit(NimbusEvent.PermissionGroupUpdated(groupName))
            println(ConsoleFormatter.success("Added parent '$parentName' to group '$groupName'."))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed"))
        }
    }

    private suspend fun groupRemoveParent(groupName: String, parentName: String) {
        try {
            permissionManager.removeParent(groupName, parentName)
            eventBus.emit(NimbusEvent.PermissionGroupUpdated(groupName))
            println(ConsoleFormatter.success("Removed parent '$parentName' from group '$groupName'."))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed"))
        }
    }

    private suspend fun groupSetPrefix(groupName: String, prefix: String) {
        try {
            permissionManager.updateGroupDisplay(groupName, prefix = prefix, suffix = null, priority = null)
            eventBus.emit(NimbusEvent.PermissionGroupUpdated(groupName))
            println(ConsoleFormatter.success("Prefix for '$groupName' set to: $prefix"))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed"))
        }
    }

    private suspend fun groupSetSuffix(groupName: String, suffix: String) {
        try {
            permissionManager.updateGroupDisplay(groupName, prefix = null, suffix = suffix, priority = null)
            eventBus.emit(NimbusEvent.PermissionGroupUpdated(groupName))
            println(ConsoleFormatter.success("Suffix for '$groupName' set to: $suffix"))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed"))
        }
    }

    private suspend fun groupSetPriority(groupName: String, priority: Int) {
        try {
            permissionManager.updateGroupDisplay(groupName, prefix = null, suffix = null, priority = priority)
            eventBus.emit(NimbusEvent.PermissionGroupUpdated(groupName))
            println(ConsoleFormatter.success("Priority for '$groupName' set to $priority."))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed"))
        }
    }

    private suspend fun groupSetWeight(groupName: String, weight: Int) {
        try {
            permissionManager.setGroupWeight(groupName, weight)
            permissionManager.logAudit("console", "group.setweight", groupName, "Set weight to $weight")
            eventBus.emit(NimbusEvent.PermissionGroupUpdated(groupName))
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
                    eventBus.emit(NimbusEvent.PermissionGroupUpdated(args[1]))
                    println(ConsoleFormatter.success("Meta '${args[2]}' set to '$value' on group '${args[1]}'."))
                } catch (e: IllegalArgumentException) {
                    println(ConsoleFormatter.error(e.message ?: "Failed"))
                }
            }
            "remove" -> {
                if (args.size < 3) return println(ConsoleFormatter.error("Usage: perms group meta remove <group> <key>"))
                try {
                    permissionManager.removeGroupMeta(args[1], args[2])
                    eventBus.emit(NimbusEvent.PermissionGroupUpdated(args[1]))
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
            eventBus.emit(NimbusEvent.PlayerPermissionsUpdated(uuid, playerName))
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
            eventBus.emit(NimbusEvent.PlayerPermissionsUpdated(uuid, playerName))
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
                eventBus.emit(NimbusEvent.PlayerPromoted(uuid, playerName, trackName, newGroup))
                eventBus.emit(NimbusEvent.PlayerPermissionsUpdated(uuid, playerName))
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
                eventBus.emit(NimbusEvent.PlayerDemoted(uuid, playerName, trackName, newGroup))
                eventBus.emit(NimbusEvent.PlayerPermissionsUpdated(uuid, playerName))
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
                    eventBus.emit(NimbusEvent.PlayerPermissionsUpdated(uuid, playerName))
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
                    eventBus.emit(NimbusEvent.PlayerPermissionsUpdated(uuid, playerName))
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
            eventBus.emit(NimbusEvent.PermissionTrackCreated(name))
            println(ConsoleFormatter.success("Track '$name' created: ${track.groups.joinToString(" → ")}"))
        } catch (e: IllegalArgumentException) {
            println(ConsoleFormatter.error(e.message ?: "Failed to create track"))
        }
    }

    private suspend fun trackDelete(name: String) {
        try {
            permissionManager.deleteTrack(name)
            permissionManager.logAudit("console", "track.delete", name, "Deleted track '$name'")
            eventBus.emit(NimbusEvent.PermissionTrackDeleted(name))
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
}
