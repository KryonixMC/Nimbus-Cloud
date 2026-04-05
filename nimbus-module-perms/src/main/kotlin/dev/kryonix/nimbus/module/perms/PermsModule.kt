package dev.kryonix.nimbus.module.perms

import dev.kryonix.nimbus.NimbusVersion
import dev.kryonix.nimbus.config.NimbusConfig
import dev.kryonix.nimbus.console.ConsoleFormatter.BOLD
import dev.kryonix.nimbus.console.ConsoleFormatter.DIM
import dev.kryonix.nimbus.console.ConsoleFormatter.RESET
import dev.kryonix.nimbus.console.ConsoleFormatter.info
import dev.kryonix.nimbus.console.ConsoleFormatter.success
import dev.kryonix.nimbus.console.ConsoleFormatter.warn
import dev.kryonix.nimbus.database.DatabaseManager
import dev.kryonix.nimbus.event.EventBus
import dev.kryonix.nimbus.module.ModuleContext
import dev.kryonix.nimbus.module.NimbusModule
import dev.kryonix.nimbus.module.PluginDeployment
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

        // Register permission table migrations
        context.registerMigrations(listOf(
            dev.kryonix.nimbus.module.perms.migrations.PermsV1_Baseline
        ))

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

        // Register plugin deployments (respects deploy_plugin config as opt-out)
        val config = context.service<NimbusConfig>()
        if (config?.permissions?.deployPlugin != false) {
            context.registerPluginDeployment(PluginDeployment(
                resourcePath = "plugins/nimbus-perms.jar",
                fileName = "nimbus-perms.jar",
                displayName = "NimbusPerms",
                foliaRequiresPacketEvents = true
            ))
        }

        // Register console event formatters
        registerEventFormatters(context)

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

    private fun registerEventFormatters(context: ModuleContext) {
        context.registerEventFormatter("PERMISSION_GROUP_CREATED") { data ->
            "${success("+ PERM")} group ${BOLD}${data["group"]}${RESET} created"
        }
        context.registerEventFormatter("PERMISSION_GROUP_UPDATED") { data ->
            "${info("~ PERM")} group ${BOLD}${data["group"]}${RESET} updated"
        }
        context.registerEventFormatter("PERMISSION_GROUP_DELETED") { data ->
            "${warn("- PERM")} group ${BOLD}${data["group"]}${RESET} deleted"
        }
        context.registerEventFormatter("PLAYER_PERMISSIONS_UPDATED") { data ->
            "${info("~ PERM")} ${BOLD}${data["player"]}${RESET} ${DIM}(${data["uuid"]})${RESET} updated"
        }
        context.registerEventFormatter("PERMISSION_TRACK_CREATED") { data ->
            "${success("+ TRACK")} ${BOLD}${data["track"]}${RESET} created"
        }
        context.registerEventFormatter("PERMISSION_TRACK_DELETED") { data ->
            "${warn("- TRACK")} ${BOLD}${data["track"]}${RESET} deleted"
        }
        context.registerEventFormatter("PLAYER_PROMOTED") { data ->
            "${success("↑ PROMOTE")} ${BOLD}${data["player"]}${RESET} → ${BOLD}${data["newGroup"]}${RESET} ${DIM}(track=${data["track"]})${RESET}"
        }
        context.registerEventFormatter("PLAYER_DEMOTED") { data ->
            "${warn("↓ DEMOTE")} ${BOLD}${data["player"]}${RESET} → ${BOLD}${data["newGroup"]}${RESET} ${DIM}(track=${data["track"]})${RESET}"
        }
    }

    override suspend fun enable() {}

    override fun disable() {}
}
