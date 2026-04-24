package dev.nimbuspowered.nimbus.module.perms.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleFormatter
import dev.nimbuspowered.nimbus.event.EventBus
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

    private val groupCmds = GroupSubcommands(permissionManager, eventBus)
    private val userCmds = UserSubcommands(permissionManager, eventBus)
    private val trackCmds = TrackSubcommands(permissionManager, eventBus)

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
            "group" -> groupCmds.handle(args.drop(1))
            "user"  -> userCmds.handle(args.drop(1))
            "track" -> trackCmds.handle(args.drop(1))
            "audit" -> handleAudit(args.drop(1))
            "reload" -> handleReload()
            else -> printUsage()
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

    // ── Reload ──────────────────────────────────────────────

    private suspend fun handleReload() {
        permissionManager.reload()
        println(ConsoleFormatter.success("Permissions reloaded."))
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
            "group" -> groupCmds.handleRemote(args.drop(1), output)
            "user"  -> userCmds.handleRemote(args.drop(1), output)
            "track" -> trackCmds.handleRemote(args.drop(1), output)
            "audit" -> remoteAudit(args.drop(1), output)
            "reload" -> {
                permissionManager.reload()
                output.success("Permissions reloaded.")
            }
            else -> remoteHelp(output)
        }
        return true
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

    private fun remoteHelp(out: CommandOutput) {
        out.header("Permissions")
        for (sub in subcommandMeta) {
            out.item("  ${sub.usage} - ${sub.description}")
        }
    }
}
