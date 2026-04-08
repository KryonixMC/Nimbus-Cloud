package dev.nimbuspowered.nimbus.module.players

import dev.nimbuspowered.nimbus.NimbusVersion
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.BOLD
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.CYAN
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.RESET
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.success
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.error
import dev.nimbuspowered.nimbus.database.DatabaseManager
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.module.DashboardConfig
import dev.nimbuspowered.nimbus.module.DashboardSection
import dev.nimbuspowered.nimbus.module.ModuleContext
import dev.nimbuspowered.nimbus.module.NimbusModule
import dev.nimbuspowered.nimbus.module.players.commands.PlayersModuleCommand
import dev.nimbuspowered.nimbus.module.players.routes.playerRoutes
import dev.nimbuspowered.nimbus.module.service
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import org.slf4j.LoggerFactory

class PlayersModule : NimbusModule {
    override val id = "players"
    override val name = "Players"
    override val version: String get() = NimbusVersion.version
    override val description = "Centralized player tracking, cross-server management, session history"

    override val dashboardConfig = DashboardConfig(
        icon = "Users",
        apiPrefix = "/api/players",
        sections = listOf(
            DashboardSection("Online Players", "table", "/online"),
            DashboardSection("Statistics", "stats", "/stats")
        )
    )

    private val logger = LoggerFactory.getLogger(PlayersModule::class.java)
    private lateinit var tracker: PlayerTracker

    override suspend fun init(context: ModuleContext) {
        val db = context.service<DatabaseManager>()!!
        val eventBus = context.service<EventBus>()!!
        val registry = context.service<ServiceRegistry>()!!

        // Register migrations
        context.registerMigrations(listOf(
            dev.nimbuspowered.nimbus.module.players.migrations.PlayersV1_Baseline
        ))

        // Initialize tracker
        tracker = PlayerTracker(db)

        // Listen to player events from Bridge/SDK
        eventBus.on<NimbusEvent.PlayerConnected> { event ->
            val group = registry.get(event.serviceName)?.groupName ?: "unknown"
            tracker.onPlayerConnect(event.uuid, event.playerName, event.serviceName, group)
        }
        eventBus.on<NimbusEvent.PlayerDisconnected> { event ->
            tracker.onPlayerDisconnect(event.uuid, event.playerName, event.serviceName)
        }
        eventBus.on<NimbusEvent.PlayerServerSwitch> { event ->
            val group = registry.get(event.toService)?.groupName ?: "unknown"
            tracker.onPlayerServerSwitch(event.uuid, event.playerName, event.fromService, event.toService, group)
        }

        // Register command
        context.registerCommand(PlayersModuleCommand(tracker))
        context.registerCompleter("players") { args, prefix ->
            when (args.size) {
                1 -> listOf("list", "info", "history", "stats")
                    .filter { it.startsWith(prefix, ignoreCase = true) }
                2 -> when (args[0].lowercase()) {
                    "list" -> registry.getAll().map { it.name }
                        .filter { it.startsWith(prefix, ignoreCase = true) }
                    "info", "history" -> tracker.getOnlinePlayers().map { it.name }
                        .filter { it.startsWith(prefix, ignoreCase = true) }
                    else -> emptyList()
                }
                else -> emptyList()
            }
        }

        // Register API routes
        context.registerRoutes({ playerRoutes(tracker) })

        // Register event formatters
        context.registerEventFormatter("PLAYER_SESSION_START") { data ->
            "${success("+")} ${BOLD}${data["player"]}${RESET} joined ${CYAN}${data["service"]}${RESET}"
        }
        context.registerEventFormatter("PLAYER_SESSION_END") { data ->
            "${error("-")} ${BOLD}${data["player"]}${RESET} left ${CYAN}${data["service"]}${RESET}"
        }
    }

    override suspend fun enable() {}

    override fun disable() {}
}
