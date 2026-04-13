package dev.nimbuspowered.nimbus.module.anomaly

import dev.nimbuspowered.nimbus.NimbusVersion
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.BOLD
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.DIM
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.RED
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.RESET
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.error
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.warn
import dev.nimbuspowered.nimbus.database.DatabaseManager
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.module.ModuleContext
import dev.nimbuspowered.nimbus.module.NimbusModule
import dev.nimbuspowered.nimbus.module.anomaly.commands.AnomalyCommand
import dev.nimbuspowered.nimbus.module.anomaly.migrations.AnomalyV1_Baseline
import dev.nimbuspowered.nimbus.module.anomaly.routes.anomalyRoutes
import dev.nimbuspowered.nimbus.module.service
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class AnomalyModule : NimbusModule {

    override val id = "anomaly"
    override val name = "Anomaly Detection"
    override val version: String get() = NimbusVersion.version
    override val description = "Statistical anomaly detection for service metrics"

    private val logger = LoggerFactory.getLogger(AnomalyModule::class.java)

    private lateinit var manager: AnomalyManager
    private lateinit var configManager: AnomalyConfigManager
    private lateinit var context: ModuleContext

    private var evaluationJob: Job? = null

    override suspend fun init(context: ModuleContext) {
        this.context = context

        val db = context.service<DatabaseManager>()!!
        val eventBus = context.service<EventBus>()!!
        val registry = context.service<ServiceRegistry>()!!
        val groupManager = context.service<GroupManager>()!!

        // Register migrations
        context.registerMigrations(listOf(AnomalyV1_Baseline))

        // Initialize config
        val configDir = context.moduleConfigDir("anomaly")
        configManager = AnomalyConfigManager(configDir)
        configManager.init()

        // Initialize manager
        manager = AnomalyManager(db, configManager, registry, groupManager, eventBus)

        // Register command
        context.registerCommand(AnomalyCommand(manager))
        context.registerCompleter("anomaly") { args, prefix ->
            when (args.size) {
                1 -> listOf("status", "history")
                    .filter { it.startsWith(prefix, ignoreCase = true) }
                else -> emptyList()
            }
        }

        // Register API routes
        context.registerRoutes({ anomalyRoutes(manager) })

        // Register console event formatters
        registerEventFormatters(context)
    }

    private fun registerEventFormatters(context: ModuleContext) {
        context.registerEventFormatter("ANOMALY_WARNING") { data ->
            val service = data["service"] ?: "?"
            val group = data["group"]?.let { if (it.isNotEmpty()) " $DIM($it)$RESET" else "" } ?: ""
            val metric = data["metric"] ?: "?"
            val z = data["zscore"] ?: "?"
            val value = data["value"] ?: "?"
            val baseline = data["baseline"] ?: "?"
            val type = data["type"] ?: "?"
            "${warn("! ANOMALY")}$RESET $BOLD$service$RESET$group " +
                "$DIM$metric$RESET val=$value baseline=$baseline z=$z $DIM[$type]$RESET"
        }

        context.registerEventFormatter("ANOMALY_CRITICAL") { data ->
            val service = data["service"] ?: "?"
            val group = data["group"]?.let { if (it.isNotEmpty()) " $DIM($it)$RESET" else "" } ?: ""
            val metric = data["metric"] ?: "?"
            val z = data["zscore"] ?: "?"
            val value = data["value"] ?: "?"
            val baseline = data["baseline"] ?: "?"
            val type = data["type"] ?: "?"
            "${error("✖ ANOMALY")}$RESET $RED$BOLD$service$RESET$group " +
                "$DIM$metric$RESET val=$value baseline=$baseline z=$z $DIM[$type]$RESET"
        }
    }

    override suspend fun enable() {
        val cfg = configManager.getConfig()
        if (!cfg.enabled) {
            logger.info("Anomaly detection is disabled in config — evaluation loop not started")
            return
        }

        evaluationJob = context.scope.launch {
            // Wait for services to warm up before the first evaluation.
            delay(30_000)
            while (isActive) {
                try {
                    manager.runEvaluation()
                } catch (e: Exception) {
                    logger.error("Error during anomaly evaluation", e)
                }
                val intervalMs = configManager.getConfig().evaluationIntervalSeconds * 1_000L
                delay(intervalMs)
            }
        }

        logger.info(
            "Anomaly detection started: interval={}s, zscoreThreshold={}, peerComparison={}",
            cfg.evaluationIntervalSeconds,
            cfg.zscoreThreshold,
            cfg.peerComparisonEnabled
        )
    }

    override fun disable() {
        evaluationJob?.cancel()
        evaluationJob = null
    }
}
