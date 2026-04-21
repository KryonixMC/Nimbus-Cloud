package dev.nimbuspowered.nimbus.module.resourcepacks

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
import dev.nimbuspowered.nimbus.module.api.AuthLevel
import dev.nimbuspowered.nimbus.module.api.DashboardConfig
import dev.nimbuspowered.nimbus.module.api.DashboardSection
import dev.nimbuspowered.nimbus.module.api.ModuleContext
import dev.nimbuspowered.nimbus.module.api.NimbusModule
import dev.nimbuspowered.nimbus.module.api.PluginDeployment
import dev.nimbuspowered.nimbus.module.resourcepacks.commands.ResourcePackCommand
import dev.nimbuspowered.nimbus.module.resourcepacks.migrations.ResourcePacksV1_Baseline
import dev.nimbuspowered.nimbus.module.resourcepacks.routes.resourcePackAuthedRoutes
import dev.nimbuspowered.nimbus.module.resourcepacks.routes.resourcePackPublicRoutes
import dev.nimbuspowered.nimbus.module.api.service

class ResourcePacksModule : NimbusModule {
    override val id = "resourcepacks"
    override val name = "Resource Packs"
    override val version: String get() = NimbusVersion.version
    override val description = "Network-wide resource pack management (URL + locally hosted, multi-pack stacks)"

    override val dashboardConfig = DashboardConfig(
        icon = "Package",
        apiPrefix = "/api/resourcepacks",
        sections = listOf(
            DashboardSection("Packs", "table", ""),
            DashboardSection("Assignments", "table", "/assignments")
        )
    )

    private lateinit var manager: ResourcePackManager
    private lateinit var ctx: ModuleContext

    override suspend fun init(context: ModuleContext) {
        ctx = context
        val db = context.service<DatabaseManager>()!!
        val eventBus = context.service<EventBus>()!!
        val config = context.service<NimbusConfig>()

        context.registerMigrations(listOf(ResourcePacksV1_Baseline))

        val storageDir = context.baseDir.resolve("data").resolve("resourcepacks")
        manager = ResourcePackManager(db, storageDir)

        if (config?.resourcepacks?.deployPlugin != false) {
            context.registerPluginDeployment(PluginDeployment(
                resourcePath = "plugins/nimbus-resourcepacks.jar",
                fileName = "nimbus-resourcepacks.jar",
                displayName = "NimbusResourcePacks"
            ))
        }

        val maxUploadBytes = config?.resourcepacks?.maxUploadBytes ?: (250L * 1024 * 1024)

        registerEventFormatters(context)

        context.registerCommand(ResourcePackCommand(manager, eventBus, maxUploadBytes))
        context.registerCompleter("resourcepack") { args, prefix ->
            when (args.size) {
                1 -> listOf("list", "add", "upload", "remove", "assign", "unassign", "assignments")
                    .filter { it.startsWith(prefix, ignoreCase = true) }
                else -> emptyList()
            }
        }

        // Authenticated CRUD + assignments
        context.registerRoutes({
            resourcePackAuthedRoutes(
                manager,
                maxUploadBytes,
                { resolvePublicBaseUrl(config) },
                eventBus
            )
        }, auth = AuthLevel.SERVICE)

        // Public file download (no auth — Minecraft clients have no bearer token)
        context.registerRoutes({ resourcePackPublicRoutes(manager) }, auth = AuthLevel.NONE)

        context.registerService(ResourcePackManager::class.java, manager)
        context.registerDoctorCheck(ResourcePacksDoctorCheck(manager, storageDir))
    }

    private fun resolvePublicBaseUrl(config: NimbusConfig?): String {
        val explicit = config?.resourcepacks?.publicBaseUrl
        if (!explicit.isNullOrBlank()) return explicit.trimEnd('/')
        val api = config?.api ?: return "http://127.0.0.1:8080"
        val host = if (api.bind == "0.0.0.0") "127.0.0.1" else api.bind
        return "http://$host:${api.port}"
    }

    private fun registerEventFormatters(context: ModuleContext) {
        context.registerEventFormatter("RESOURCE_PACK_CREATED") { data ->
            "${success("+ PACK")} $BOLD${data["name"]}$RESET $DIM(${data["source"]})$RESET"
        }
        context.registerEventFormatter("RESOURCE_PACK_DELETED") { data ->
            "${warn("- PACK")} $BOLD${data["name"]}$RESET"
        }
        context.registerEventFormatter("RESOURCE_PACK_ASSIGNED") { data ->
            val target = data["target"]?.takeIf { it.isNotBlank() }?.let { " → $BOLD$it$RESET" } ?: ""
            "${info("→ ASSIGN")} pack=${data["packId"]} ${data["scope"]}$target"
        }
        context.registerEventFormatter("RESOURCE_PACK_UNASSIGNED") { data ->
            val target = data["target"]?.takeIf { it.isNotBlank() }?.let { " ← $BOLD$it$RESET" } ?: ""
            "${warn("← UNASSIGN")} pack=${data["packId"]} ${data["scope"]}$target"
        }
        context.registerEventFormatter("RESOURCE_PACK_STATUS") { data ->
            "${DIM}→ PACK STATUS$RESET ${data["player"]?.take(8)}… ${data["status"]}"
        }
    }

    override suspend fun enable() {}
    override fun disable() {}
}
