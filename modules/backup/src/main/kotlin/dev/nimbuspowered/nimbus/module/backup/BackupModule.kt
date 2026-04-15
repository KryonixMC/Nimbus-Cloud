package dev.nimbuspowered.nimbus.module.backup

import dev.nimbuspowered.nimbus.NimbusVersion
import dev.nimbuspowered.nimbus.config.NimbusConfig
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.BOLD
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.DIM
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.RESET
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.error
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.info
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.success
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.warn
import dev.nimbuspowered.nimbus.database.DatabaseManager
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.module.AuthLevel
import dev.nimbuspowered.nimbus.module.DashboardConfig
import dev.nimbuspowered.nimbus.module.DashboardSection
import dev.nimbuspowered.nimbus.module.ModuleContext
import dev.nimbuspowered.nimbus.module.NimbusModule
import dev.nimbuspowered.nimbus.module.backup.commands.BackupCommand
import dev.nimbuspowered.nimbus.module.backup.migrations.BackupV1_Baseline
import dev.nimbuspowered.nimbus.module.backup.routes.backupRoutes
import dev.nimbuspowered.nimbus.module.service
import dev.nimbuspowered.nimbus.service.DedicatedServiceManager
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BackupModule : NimbusModule {
    override val id = "backup"
    override val name = "Backup"
    override val version: String get() = NimbusVersion.version
    override val description = "Scheduled backups of services, templates, config, and the database (native tar+zstd, multi-threaded)"

    override val dashboardConfig = DashboardConfig(
        icon = "Archive",
        apiPrefix = "/api/backups",
        sections = listOf(
            DashboardSection("Recent", "table", ""),
            DashboardSection("Schedules", "table", "/schedules")
        )
    )

    private lateinit var manager: BackupManager
    private lateinit var retention: BackupRetention
    private lateinit var scheduler: BackupScheduler
    private lateinit var configManager: BackupConfigManager

    override suspend fun init(context: ModuleContext) {
        val dbm = context.service<DatabaseManager>()!!
        val registry = context.service<ServiceRegistry>()!!
        val eventBus = context.service<EventBus>()!!
        val dedicated = context.service<DedicatedServiceManager>()
        val nimbusConfig = context.service<NimbusConfig>()

        context.registerMigrations(listOf(BackupV1_Baseline))

        val moduleConfigDir = context.moduleConfigDir("backup")
        configManager = BackupConfigManager(moduleConfigDir)
        configManager.init()

        manager = BackupManager(
            dbm = dbm,
            configManager = configManager,
            registry = registry,
            dedicatedManager = dedicated,
            nimbusConfig = nimbusConfig,
            eventBus = eventBus,
            baseDir = context.baseDir
        )

        retention = BackupRetention(dbm.database, manager.localDestination, configManager.getConfig().retention)
        scheduler = BackupScheduler(manager, configManager, retention, dbm.database, context.scope)

        // Late-bound ServiceManager (registered after module init) for quiesce commands
        context.scope.launch {
            delay(10_000)
            manager.serviceManager = context.service<ServiceManager>()
        }

        scheduler.start()

        registerEventFormatters(context)
        context.registerCommand(BackupCommand(manager, retention, scheduler))
        context.registerCompleter("backup") { args, prefix ->
            when (args.size) {
                1 -> listOf("now", "list", "status", "restore", "verify", "prune", "schedule")
                    .filter { it.startsWith(prefix, ignoreCase = true) }
                2 -> when (args[0].lowercase()) {
                    "schedule" -> listOf("list", "reload").filter { it.startsWith(prefix, ignoreCase = true) }
                    else -> emptyList()
                }
                else -> emptyList()
            }
        }

        context.registerRoutes({ backupRoutes(manager, retention, scheduler, configManager) }, auth = AuthLevel.ADMIN)
        context.registerService(BackupManager::class.java, manager)
    }

    private fun registerEventFormatters(context: ModuleContext) {
        context.registerEventFormatter("BACKUP_STARTED") { data ->
            "${info("▸ BACKUP")} ${BOLD}${data["targetType"]}/${data["targetName"]}${RESET} " +
                    "${DIM}started (${data["triggeredBy"]})${RESET}"
        }
        context.registerEventFormatter("BACKUP_COMPLETED") { data ->
            val kb = (data["sizeBytes"]?.toLongOrNull() ?: 0L) / 1024
            val ms = data["durationMs"] ?: "?"
            val status = data["status"] ?: "SUCCESS"
            val marker = if (status == "PARTIAL") warn("◐ BACKUP") else success("✓ BACKUP")
            "$marker ${BOLD}${data["targetName"]}${RESET} ${DIM}${kb} KB, ${ms} ms${RESET}"
        }
        context.registerEventFormatter("BACKUP_FAILED") { data ->
            "${error("✗ BACKUP")} ${BOLD}${data["targetName"]}${RESET} ${DIM}${data["reason"]}${RESET}"
        }
        context.registerEventFormatter("BACKUP_RESTORED") { data ->
            "${success("↩ RESTORE")} ${BOLD}${data["targetName"]}${RESET} → ${DIM}${data["targetPath"]}${RESET}"
        }
        context.registerEventFormatter("BACKUP_PRUNED") { data ->
            val mb = (data["freedBytes"]?.toLongOrNull() ?: 0L) / 1024 / 1024
            "${warn("- PRUNE")} ${data["count"]} backup(s) ${DIM}freed ${mb} MB (${data["scheduleClass"]})${RESET}"
        }
    }

    override suspend fun enable() {}

    override fun disable() {
        if (::scheduler.isInitialized) scheduler.stop()
    }
}
