package dev.nimbuspowered.nimbus.module.backup

import dev.nimbuspowered.nimbus.NimbusVersion
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.BOLD
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.DIM
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.GREEN
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.RED
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.RESET
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.YELLOW
import dev.nimbuspowered.nimbus.database.DatabaseManager
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.module.AuthLevel
import dev.nimbuspowered.nimbus.module.ModuleContext
import dev.nimbuspowered.nimbus.module.NimbusModule
import dev.nimbuspowered.nimbus.module.backup.commands.BackupCommand
import dev.nimbuspowered.nimbus.module.backup.routes.backupRoutes
import dev.nimbuspowered.nimbus.module.service
import org.slf4j.LoggerFactory

class BackupModule : NimbusModule {
    override val id = "backup"
    override val name = "Backup"
    override val version: String get() = NimbusVersion.version
    override val description = "Scheduled and on-demand backups of service data and templates"

    private val logger = LoggerFactory.getLogger(BackupModule::class.java)
    private lateinit var manager: BackupManager
    private lateinit var configManager: BackupConfigManager

    override suspend fun init(context: ModuleContext) {
        val db = context.service<DatabaseManager>()!!
        val eventBus = context.service<EventBus>()!!

        // Register migration
        context.registerMigrations(listOf(
            dev.nimbuspowered.nimbus.module.backup.migrations.BackupV1_Baseline
        ))

        // Initialize config
        val configDir = context.moduleConfigDir("backup")
        configManager = BackupConfigManager(configDir)
        configManager.init()

        // Initialize manager
        manager = BackupManager(db, configManager, context.baseDir, eventBus, context.scope)
        manager.init()

        // Register command + completer
        val command = BackupCommand(manager, configManager)
        context.registerCommand(command)
        context.registerCompleter("backup") { args, prefix ->
            when (args.size) {
                1 -> listOf("create", "restore", "list", "prune", "status")
                    .filter { it.startsWith(prefix, ignoreCase = true) }
                2 -> when (args[0].lowercase()) {
                    "create", "list", "prune" -> configManager.getConfig().targets
                        .map { it.id }
                        .filter { it.startsWith(prefix, ignoreCase = true) }
                    else -> emptyList()
                }
                3 -> when (args[0].lowercase()) {
                    "list" -> listOf("--limit")
                        .filter { it.startsWith(prefix, ignoreCase = true) }
                    else -> emptyList()
                }
                else -> emptyList()
            }
        }

        // Register API routes (admin-only — backups contain sensitive data)
        context.registerRoutes({ backupRoutes(manager) }, AuthLevel.ADMIN)

        // Register console event formatters
        registerEventFormatters(context)
    }

    private fun registerEventFormatters(context: ModuleContext) {
        context.registerEventFormatter("BACKUP_CREATED") { data ->
            val target = data["targetId"] ?: "?"
            val size = data["sizeBytes"]?.toLongOrNull()
                ?.let { " ${"%.1f".format(it / 1_048_576.0)}MB" } ?: ""
            val path = data["archivePath"]?.substringAfterLast("/") ?: ""
            "${GREEN}+ BACKUP${RESET} ${BOLD}$target${RESET} ${DIM}$path$size${RESET}"
        }
        context.registerEventFormatter("BACKUP_FAILED") { data ->
            val target = data["targetId"] ?: "?"
            val error = data["error"] ?: "unknown error"
            "${RED}! BACKUP${RESET} ${BOLD}$target${RESET} failed ${DIM}— $error${RESET}"
        }
        context.registerEventFormatter("BACKUP_RESTORED") { data ->
            val id = data["backupId"] ?: "?"
            val path = data["archivePath"]?.substringAfterLast("/") ?: ""
            "${YELLOW}~ RESTORE${RESET} backup #${BOLD}$id${RESET} ${DIM}$path${RESET}"
        }
    }

    override suspend fun enable() {
        manager.startScheduler()
    }

    override fun disable() {
        manager.shutdown()
    }
}
