package dev.nimbuspowered.nimbus.module.notifications

import dev.nimbuspowered.nimbus.NimbusVersion
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.BOLD
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.DIM
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.RESET
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.error
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.info
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.success
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.module.AuthLevel
import dev.nimbuspowered.nimbus.module.ModuleContext
import dev.nimbuspowered.nimbus.module.NimbusModule
import dev.nimbuspowered.nimbus.module.notifications.commands.NotificationsCommand
import dev.nimbuspowered.nimbus.module.notifications.migrations.NotificationsV1_Baseline
import dev.nimbuspowered.nimbus.module.notifications.routes.notificationsRoutes
import dev.nimbuspowered.nimbus.module.service
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import org.slf4j.LoggerFactory

class NotificationsModule : NimbusModule {
    override val id = "notifications"
    override val name = "Notifications"
    override val version: String get() = NimbusVersion.version
    override val description = "Discord and Slack webhook notifications for Nimbus events"

    private val logger = LoggerFactory.getLogger(NotificationsModule::class.java)
    private lateinit var manager: NotificationsManager
    private lateinit var configManager: NotificationsConfigManager
    private lateinit var eventBus: EventBus
    private lateinit var registry: ServiceRegistry

    override suspend fun init(context: ModuleContext) {
        eventBus = context.service<EventBus>()!!
        registry = context.service<ServiceRegistry>()!!

        // Register migration (reserves 4000+ range, no tables needed)
        context.registerMigrations(listOf(NotificationsV1_Baseline))

        // Initialize config
        val configDir = context.moduleConfigDir("notifications")
        configManager = NotificationsConfigManager(configDir)
        configManager.init()

        // Initialize manager
        manager = NotificationsManager(configManager.getConfig(), context.scope)

        // Register console command
        context.registerCommand(NotificationsCommand(manager, configManager))
        context.registerCompleter("notifications") { args, prefix ->
            when (args.size) {
                1 -> listOf("list", "test", "reload", "status")
                    .filter { it.startsWith(prefix, ignoreCase = true) }
                2 -> when (args[0].lowercase()) {
                    "test" -> configManager.getConfig().webhooks
                        .map { it.id }
                        .filter { it.startsWith(prefix, ignoreCase = true) }
                    else -> emptyList()
                }
                else -> emptyList()
            }
        }

        // Register API routes (ADMIN only — webhooks contain sensitive URLs)
        context.registerRoutes({ notificationsRoutes(manager, configManager) }, AuthLevel.ADMIN)

        // Register console event formatters
        registerEventFormatters(context)

        logger.info("Notifications module initialized ({} webhook(s) configured)",
            configManager.getConfig().webhooks.size)
    }

    private fun registerEventFormatters(context: ModuleContext) {
        context.registerEventFormatter("WEBHOOK_SENT") { data ->
            "${success("~ NOTIF")} ${BOLD}${data["webhook_id"]}${RESET} sent ${data["count"] ?: "1"} notification(s)" +
                    " ${DIM}(${data["event_type"] ?: ""})${RESET}"
        }
        context.registerEventFormatter("WEBHOOK_FAILED") { data ->
            "${error("! NOTIF")} ${BOLD}${data["webhook_id"]}${RESET} failed to deliver notification" +
                    " ${DIM}(${data["error"] ?: "unknown error"})${RESET}"
        }
        context.registerEventFormatter("WEBHOOK_RATE_LIMITED") { data ->
            "${info("~ NOTIF")} ${BOLD}${data["webhook_id"]}${RESET} rate-limited, dropped ${data["count"] ?: "1"} notification(s)"
        }
    }

    override suspend fun enable() {
        manager.start(eventBus, registry)
    }

    override fun disable() {
        manager.shutdown()
    }
}
