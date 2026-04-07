package dev.nimbuspowered.nimbus.module.scaling

import dev.nimbuspowered.nimbus.NimbusVersion
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.BOLD
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.DIM
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.RESET
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.info
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.success
import dev.nimbuspowered.nimbus.database.DatabaseManager
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.module.ModuleContext
import dev.nimbuspowered.nimbus.module.NimbusModule
import dev.nimbuspowered.nimbus.module.scaling.commands.ScalingCommand
import dev.nimbuspowered.nimbus.module.scaling.routes.scalingRoutes
import dev.nimbuspowered.nimbus.module.service
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class SmartScalingModule : NimbusModule {
    override val id = "scaling"
    override val name = "Smart Scaling"
    override val version: String get() = NimbusVersion.version
    override val description = "Time-based schedules, predictive warmup, player history"

    private val logger = LoggerFactory.getLogger(SmartScalingModule::class.java)
    private lateinit var manager: SmartScalingManager
    private lateinit var configManager: SmartScalingConfigManager
    private lateinit var context: ModuleContext
    private var snapshotJob: Job? = null
    private var evaluationJob: Job? = null
    private var pruneJob: Job? = null

    override suspend fun init(context: ModuleContext) {
        this.context = context
        val db = context.service<DatabaseManager>()!!
        val eventBus = context.service<EventBus>()!!
        val groupManager = context.service<GroupManager>()!!
        val registry = context.service<ServiceRegistry>()!!

        // Register scaling table migrations
        context.registerMigrations(listOf(
            dev.nimbuspowered.nimbus.module.scaling.migrations.ScalingV1_Baseline
        ))

        // Initialize config
        val configDir = context.moduleConfigDir("scaling")
        configManager = SmartScalingConfigManager(configDir)
        configManager.init()

        // Initialize manager
        manager = SmartScalingManager(db, configManager, groupManager, registry, eventBus)

        // Generate default configs for existing groups
        for (group in groupManager.getAllGroups()) {
            if (group.isStatic) continue
            configManager.ensureConfig(group.name)
        }
        configManager.reload()

        // Create configs for new groups
        eventBus.on<NimbusEvent.GroupCreated> { event ->
            configManager.ensureConfig(event.groupName)
            configManager.reload()
        }

        // Snapshot collection loop (every 60s)
        snapshotJob = context.scope.launch {
            delay(10_000) // Initial delay to let services start
            while (isActive) {
                try {
                    manager.collectSnapshots()
                } catch (e: Exception) {
                    logger.error("Error collecting snapshots", e)
                }
                delay(60_000)
            }
        }

        // Schedule + prediction evaluation loop (every 30s)
        evaluationJob = context.scope.launch {
            delay(15_000) // Wait for ServiceManager to be registered
            // Resolve ServiceManager lazily (registered after module init)
            manager.serviceManager = context.service<ServiceManager>()
            if (manager.serviceManager == null) {
                logger.warn("ServiceManager not available — smart scaling actions disabled")
            }
            while (isActive) {
                try {
                    manager.evaluateSchedules()
                    manager.evaluatePredictions()
                } catch (e: Exception) {
                    logger.error("Error during smart scaling evaluation", e)
                }
                delay(30_000)
            }
        }

        // Prune old data daily (check every hour)
        pruneJob = context.scope.launch {
            while (isActive) {
                delay(3_600_000) // 1 hour
                try {
                    manager.pruneHistory()
                } catch (e: Exception) {
                    logger.error("Error pruning scaling history", e)
                }
            }
        }

        // Register command
        context.registerCommand(ScalingCommand(manager, configManager))
        context.registerCompleter("scaling") { args, prefix ->
            when (args.size) {
                1 -> listOf("status", "schedule", "history", "predict", "reload")
                    .filter { it.startsWith(prefix, ignoreCase = true) }
                2 -> when (args[0].lowercase()) {
                    "schedule" -> listOf("list", "info")
                        .filter { it.startsWith(prefix, ignoreCase = true) }
                    "history", "predict" -> groupManager.getAllGroups()
                        .filter { !it.isStatic }
                        .map { it.name }
                        .filter { it.startsWith(prefix, ignoreCase = true) }
                    else -> emptyList()
                }
                3 -> when (args[0].lowercase()) {
                    "schedule" -> if (args[1].lowercase() == "info") {
                        groupManager.getAllGroups()
                            .filter { !it.isStatic }
                            .map { it.name }
                            .filter { it.startsWith(prefix, ignoreCase = true) }
                    } else emptyList()
                    else -> emptyList()
                }
                else -> emptyList()
            }
        }

        // Register API routes
        context.registerRoutes({ scalingRoutes(manager, configManager) })

        // Register console event formatters
        registerEventFormatters(context)
    }

    private fun registerEventFormatters(context: ModuleContext) {
        context.registerEventFormatter("SMART_SCHEDULE") { data ->
            "${success("+ SMART")} ${BOLD}${data["group"]}${RESET} schedule \"${data["rule"]}\" " +
                    "${DIM}(min=${data["min"]}, started ${data["started"]})${RESET}"
        }
        context.registerEventFormatter("SMART_WARMUP") { data ->
            "${success("+ SMART")} ${BOLD}${data["group"]}${RESET} pre-warmed for \"${data["rule"]}\" " +
                    "${DIM}(min=${data["min"]}, started ${data["started"]})${RESET}"
        }
        context.registerEventFormatter("SMART_PREDICTION") { data ->
            "${info("~ SMART")} ${BOLD}${data["group"]}${RESET} predicted ${data["predicted"]} players " +
                    "${DIM}(started ${data["started"]}, ${data["samples"]} samples)${RESET}"
        }
    }

    override suspend fun enable() {}

    override fun disable() {
        snapshotJob?.cancel()
        evaluationJob?.cancel()
        pruneJob?.cancel()
    }
}
