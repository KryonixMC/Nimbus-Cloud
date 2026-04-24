package dev.nimbuspowered.nimbus.module.perms.commands

import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.module.api.CommandOutput
import dev.nimbuspowered.nimbus.module.perms.PermsEvents
import dev.nimbuspowered.nimbus.module.perms.PermissionManager

internal class UserSubcommands(
    private val permissionManager: PermissionManager,
    private val eventBus: EventBus
) {

    // ════════════════════════════════════════════════════════════
    // Console execution (rich ANSI formatting via ConsoleFormatter)
    // ════════════════════════════════════════════════════════════

    suspend fun handle(args: List<String>) {
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

    // ════════════════════════════════════════════════════════════
    // Remote execution (typed output via CommandOutput for Bridge)
    // ════════════════════════════════════════════════════════════

    suspend fun handleRemote(args: List<String>, out: CommandOutput) {
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

    // ── Resolve Helpers ─────────────────────────────────────

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

    // ── Help ────────────────────────────────────────────────

    private fun printUserUsage() {
        println(ConsoleFormatter.error("Usage: perms user <list|info|check|addgroup|removegroup|promote|demote|meta>"))
    }
}
