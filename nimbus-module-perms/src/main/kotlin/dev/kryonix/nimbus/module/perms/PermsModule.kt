package dev.kryonix.nimbus.module.perms

import dev.kryonix.nimbus.NimbusVersion
import dev.kryonix.nimbus.database.DatabaseManager
import dev.kryonix.nimbus.event.EventBus
import dev.kryonix.nimbus.module.ModuleContext
import dev.kryonix.nimbus.module.NimbusModule
import dev.kryonix.nimbus.module.service
import dev.kryonix.nimbus.module.perms.commands.PermsCommand
import dev.kryonix.nimbus.module.perms.routes.permissionRoutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PermsModule : NimbusModule {
    override val id = "perms"
    override val name = "Permissions"
    override val version: String get() = NimbusVersion.version
    override val description = "Permission groups, tracks, prefix/suffix, audit log"

    private lateinit var permissionManager: PermissionManager

    override suspend fun init(context: ModuleContext) {
        val db = context.service<DatabaseManager>()!!
        val eventBus = context.service<EventBus>()!!

        // Create permission tables if they don't exist
        db.createTables(
            PermissionGroups, GroupPermissions, GroupParents,
            Players, PlayerGroups,
            GroupMeta, PlayerMeta,
            GroupPermissionContexts, PlayerGroupContexts,
            PermissionTracks, PermissionAuditLog
        )

        permissionManager = PermissionManager(db)
        permissionManager.init()

        // Auto-cleanup expired permission contexts every 60 seconds
        context.scope.launch {
            while (isActive) {
                delay(60_000)
                try {
                    permissionManager.cleanupExpired()
                } catch (_: Exception) {}
            }
        }

        context.registerCommand(PermsCommand(permissionManager, eventBus))
        context.registerRoutes({ permissionRoutes(permissionManager, eventBus) })

        // Tab completion for perms command
        context.registerCompleter("perms") { args, prefix ->
            when (args.size) {
                1 -> listOf("group", "user", "track", "audit", "reload").filter { it.startsWith(prefix, ignoreCase = true) }
                2 -> when (args[0].lowercase()) {
                    "group" -> listOf("list", "info", "create", "delete", "addperm", "removeperm", "setdefault", "addparent", "removeparent", "display", "weight", "meta")
                        .filter { it.startsWith(prefix, ignoreCase = true) }
                    "user" -> listOf("list", "info", "check", "addgroup", "removegroup", "promote", "demote", "meta")
                        .filter { it.startsWith(prefix, ignoreCase = true) }
                    "track" -> listOf("list", "info", "create", "delete")
                        .filter { it.startsWith(prefix, ignoreCase = true) }
                    else -> emptyList()
                }
                3 -> when (args[0].lowercase()) {
                    "group" -> when (args[1].lowercase()) {
                        "info", "delete", "addperm", "removeperm", "setdefault", "addparent", "removeparent", "display", "weight", "meta" ->
                            permissionManager.getAllGroups().map { it.name }.filter { it.startsWith(prefix, ignoreCase = true) }
                        else -> emptyList()
                    }
                    else -> emptyList()
                }
                else -> emptyList()
            }
        }
    }

    override suspend fun enable() {}

    override fun disable() {}
}
