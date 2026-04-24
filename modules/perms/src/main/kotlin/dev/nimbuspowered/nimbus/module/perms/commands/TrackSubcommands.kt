package dev.nimbuspowered.nimbus.module.perms.commands

import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.module.api.CommandOutput
import dev.nimbuspowered.nimbus.module.perms.PermsEvents
import dev.nimbuspowered.nimbus.module.perms.PermissionManager

internal class TrackSubcommands(
    private val permissionManager: PermissionManager,
    private val eventBus: EventBus
) {

    // ════════════════════════════════════════════════════════════
    // Console execution (rich ANSI formatting via ConsoleFormatter)
    // ════════════════════════════════════════════════════════════

    suspend fun handle(args: List<String>) {
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

    // ════════════════════════════════════════════════════════════
    // Remote execution (typed output via CommandOutput for Bridge)
    // ════════════════════════════════════════════════════════════

    suspend fun handleRemote(args: List<String>, out: CommandOutput) {
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

    // ── Help ────────────────────────────────────────────────

    private fun printTrackUsage() {
        println(ConsoleFormatter.error("Usage: perms track <list|info|create|delete>"))
    }
}
