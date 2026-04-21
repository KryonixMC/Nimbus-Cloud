package dev.nimbuspowered.nimbus.module.perms

import dev.nimbuspowered.nimbus.NimbusVersion
import dev.nimbuspowered.nimbus.config.NimbusConfig
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.BOLD
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.DIM
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.RESET
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.info
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.success
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.warn
import dev.nimbuspowered.nimbus.database.DatabaseManager
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.module.api.DashboardConfig
import dev.nimbuspowered.nimbus.module.api.DashboardSection
import dev.nimbuspowered.nimbus.module.api.ModuleContext
import dev.nimbuspowered.nimbus.module.api.NimbusModule
import dev.nimbuspowered.nimbus.module.api.PluginDeployment
import dev.nimbuspowered.nimbus.module.api.service
import dev.nimbuspowered.nimbus.module.perms.commands.PermsCommand
import dev.nimbuspowered.nimbus.module.perms.routes.permissionRoutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PermsModule : NimbusModule {
    override val id = "perms"
    override val name = "Permissions"
    override val version: String get() = NimbusVersion.version
    override val description = "Permission groups, tracks, prefix/suffix, audit log"

    override val dashboardConfig = DashboardConfig(
        icon = "Shield",
        apiPrefix = "/api/permissions",
        sections = listOf(
            DashboardSection("Groups", "table", "/groups"),
            DashboardSection("Players", "table", "/players"),
            DashboardSection("Tracks", "table", "/tracks"),
            DashboardSection("Audit Log", "table", "/audit")
        )
    )

    private lateinit var permissionManager: PermissionManager
    private lateinit var moduleContext: ModuleContext

    override suspend fun init(context: ModuleContext) {
        moduleContext = context
        val db = context.service<DatabaseManager>()!!
        val eventBus = context.service<EventBus>()!!

        // Register permission table migrations (run before enable())
        context.registerMigrations(listOf(
            dev.nimbuspowered.nimbus.module.perms.migrations.PermsV1_Baseline
        ))

        permissionManager = PermissionManager(db)

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

        // Expose PermissionManager so other modules (Auth / PermissionResolver)
        // can ask for the flattened permission set of a UUID without reaching
        // through the REST API.
        context.registerService(PermissionManager::class.java, permissionManager)

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

    override suspend fun enable() {
        // Load data from DB (tables guaranteed to exist — migrations run between init and enable)
        permissionManager.init()

        // Auto-cleanup expired permission contexts every 60 seconds
        moduleContext.scope.launch {
            while (isActive) {
                delay(60_000)
                try {
                    permissionManager.cleanupExpired()
                } catch (_: Exception) {}
            }
        }
    }

    override fun disable() {}
}
