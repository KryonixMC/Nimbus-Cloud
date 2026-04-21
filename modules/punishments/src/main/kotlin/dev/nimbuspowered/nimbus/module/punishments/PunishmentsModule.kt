package dev.nimbuspowered.nimbus.module.punishments

import dev.nimbuspowered.nimbus.NimbusVersion
import dev.nimbuspowered.nimbus.config.NimbusConfig
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.BOLD
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.DIM
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.RESET
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.error
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.info
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.success
import dev.nimbuspowered.nimbus.database.DatabaseManager
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.module.api.DashboardConfig
import dev.nimbuspowered.nimbus.module.api.DashboardSection
import dev.nimbuspowered.nimbus.module.api.ModuleContext
import dev.nimbuspowered.nimbus.module.api.NimbusModule
import dev.nimbuspowered.nimbus.module.api.PluginDeployment
import dev.nimbuspowered.nimbus.module.api.PluginTarget
import dev.nimbuspowered.nimbus.module.punishments.commands.PlayerResolver
import dev.nimbuspowered.nimbus.module.punishments.commands.PunishCommand
import dev.nimbuspowered.nimbus.module.punishments.migrations.PunishmentsV1_Baseline
import dev.nimbuspowered.nimbus.module.punishments.migrations.PunishmentsV2_Scope
import dev.nimbuspowered.nimbus.module.punishments.routes.punishmentRoutes
import dev.nimbuspowered.nimbus.module.api.service
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PunishmentsModule : NimbusModule {
    override val id = "punishments"
    override val name = "Punishments"
    override val version: String get() = NimbusVersion.version
    override val description = "Network-wide bans, mutes, kicks, and warnings"

    override val dashboardConfig = DashboardConfig(
        icon = "Gavel",
        apiPrefix = "/api/punishments",
        sections = listOf(
            DashboardSection("Active", "table", "?active=true"),
            DashboardSection("All", "table", "?active=false")
        )
    )

    private lateinit var manager: PunishmentManager
    private lateinit var ctx: ModuleContext
    private lateinit var messageStore: PunishmentsMessagesStore

    override suspend fun init(context: ModuleContext) {
        ctx = context
        val db = context.service<DatabaseManager>()!!
        val eventBus = context.service<EventBus>()!!
        val config = context.service<NimbusConfig>()

        context.registerMigrations(listOf(PunishmentsV1_Baseline, PunishmentsV2_Scope))
        manager = PunishmentManager(db)

        messageStore = PunishmentsMessagesStore(
            context.moduleConfigDir(id).resolve("messages.toml")
        )
        messageStore.loadOrCreate()

        if (config?.punishments?.deployPlugin != false) {
            // Velocity: login/connect blocks + warn delivery + live-kick.
            context.registerPluginDeployment(PluginDeployment(
                resourcePath = "plugins/nimbus-punishments.jar",
                fileName = "nimbus-punishments.jar",
                displayName = "NimbusPunishments",
                target = PluginTarget.VELOCITY
            ))
            // Backend: chat mute enforcement only. Can't live on Velocity because
            // cancelling signed chat at the proxy disconnects 1.19.1+ clients with
            // "illegal protocol state". AsyncPlayerChatEvent on the backend fires
            // before the broadcast and cancels cleanly.
            context.registerPluginDeployment(PluginDeployment(
                resourcePath = "plugins/nimbus-punishments-backend.jar",
                fileName = "nimbus-punishments-backend.jar",
                displayName = "NimbusPunishmentsBackend",
                target = PluginTarget.BACKEND
            ))
        }

        registerEventFormatters(context)

        val resolver = buildPlayerResolver(context)
        context.registerCommand(PunishCommand(manager, eventBus, resolver, messageStore))
        context.registerRoutes({ punishmentRoutes(manager, messageStore, eventBus) })

        context.registerCompleter("punish") { args, prefix ->
            when (args.size) {
                1 -> listOf("ban", "tempban", "ipban", "mute", "tempmute", "kick", "warn",
                            "unban", "unmute", "history", "list")
                    .filter { it.startsWith(prefix, ignoreCase = true) }
                else -> emptyList()
            }
        }

        // Expose manager so other modules (e.g. web dashboard bridges) can query cached state
        context.registerService(PunishmentManager::class.java, manager)
    }

    /**
     * Build a [PlayerResolver] used by the console `punish` command to map
     * `<player>` arguments to a (uuid, name) pair.
     *
     * Priority order:
     *   1. UUID input (`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`) — used as-is.
     *   2. Mojang profile API lookup for the name — returns the canonical UUID
     *      and name even if the player has never joined the network. This is
     *      the common case for staff who want to pre-ban known cheaters.
     *
     * The Mojang call blocks on HTTP, so the resolver is meant to run off the
     * main thread. Console commands already execute in a coroutine context.
     */
    private fun buildPlayerResolver(context: ModuleContext): PlayerResolver {
        return PlayerResolver { input ->
            if (isUuid(input)) return@PlayerResolver input to input.take(16)
            MojangUuidLookup.resolve(input)
        }
    }

    private fun isUuid(s: String): Boolean =
        s.length == 36 && s[8] == '-' && s[13] == '-' && s[18] == '-' && s[23] == '-'

    private fun registerEventFormatters(context: ModuleContext) {
        context.registerEventFormatter("PUNISHMENT_ISSUED") { data ->
            val type = data["type"] ?: "?"
            val target = data["target"] ?: "?"
            val issuer = data["issuer"] ?: "?"
            val reason = data["reason"]?.takeIf { it.isNotBlank() } ?: "no reason"
            "${error("⚠ PUNISH")} $BOLD$type$RESET on $BOLD$target$RESET $DIM by $issuer — $reason$RESET"
        }
        context.registerEventFormatter("PUNISHMENT_REVOKED") { data ->
            val type = data["type"] ?: "?"
            val target = data["target"] ?: "?"
            val revokedBy = data["revokedBy"] ?: "?"
            "${success("✓ UNPUNISH")} $BOLD$target$RESET ($type) $DIM by $revokedBy$RESET"
        }
        context.registerEventFormatter("PUNISHMENT_EXPIRED") { data ->
            val target = data["target"] ?: "?"
            val type = data["type"] ?: "?"
            "${info("⏱ EXPIRED")} $type on $BOLD$target$RESET"
        }
    }

    override suspend fun enable() {
        manager.init()
        val config = ctx.service<NimbusConfig>()
        val interval = (config?.punishments?.expiryCheckInterval ?: 30).coerceAtLeast(5) * 1000L
        val eventBus = ctx.service<EventBus>()!!

        ctx.scope.launch {
            while (isActive) {
                delay(interval)
                try {
                    val expired = manager.expireOverdue()
                    expired.forEach { eventBus.emit(PunishmentsEvents.expired(it)) }
                } catch (_: Exception) {}
            }
        }
    }

    override fun disable() {}

    /** Exposed so tests / other modules can read the active templates without touching disk. */
    fun messages(): PunishmentsMessages = messageStore.current()
}
