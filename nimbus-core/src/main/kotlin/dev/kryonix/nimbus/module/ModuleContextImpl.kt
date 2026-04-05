package dev.kryonix.nimbus.module

import dev.kryonix.nimbus.config.NimbusConfig
import dev.kryonix.nimbus.console.CommandDispatcher
import dev.kryonix.nimbus.console.ConsoleFormatter
import dev.kryonix.nimbus.database.DatabaseManager
import dev.kryonix.nimbus.event.EventBus
import dev.kryonix.nimbus.group.GroupManager
import dev.kryonix.nimbus.service.ServiceRegistry
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.exposed.sql.Database
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * Implementation of [ModuleContext] that wires modules to core services.
 */
class ModuleContextImpl(
    private val eventBus: EventBus,
    private val databaseManager: DatabaseManager,
    private val registry: ServiceRegistry,
    private val groupManager: GroupManager,
    private val config: NimbusConfig,
    override val scope: CoroutineScope,
    override val baseDir: Path,
    override val templatesDir: Path,
    private val dispatcher: CommandDispatcher,
    private val modulesConfigDir: Path
) : ModuleContext {

    override val database: Database get() = databaseManager.database

    /** Route blocks registered by modules, to be mounted by NimbusApi. */
    private val _serviceRoutes = mutableListOf<Route.() -> Unit>()
    private val _adminRoutes = mutableListOf<Route.() -> Unit>()
    private val _publicRoutes = mutableListOf<Route.() -> Unit>()

    val serviceRoutes: List<Route.() -> Unit> get() = _serviceRoutes
    val adminRoutes: List<Route.() -> Unit> get() = _adminRoutes
    val publicRoutes: List<Route.() -> Unit> get() = _publicRoutes

    /** Plugin deployments registered by modules. */
    private val _pluginDeployments = mutableListOf<PluginDeployment>()
    val pluginDeployments: List<PluginDeployment> get() = _pluginDeployments

    /** Migrations registered by modules. */
    private val _migrations = mutableListOf<Migration>()
    val migrations: List<Migration> get() = _migrations

    /** Service registry for getService() lookups. */
    private val services = mutableMapOf<Class<*>, Any>()

    init {
        // Register core services so modules can access them via getService()
        services[EventBus::class.java] = eventBus
        services[DatabaseManager::class.java] = databaseManager
        services[ServiceRegistry::class.java] = registry
        services[GroupManager::class.java] = groupManager
        services[NimbusConfig::class.java] = config
    }

    override fun moduleConfigDir(moduleId: String): Path {
        val dir = modulesConfigDir.resolve(moduleId)
        if (!dir.exists()) dir.createDirectories()
        return dir
    }

    override fun registerCommand(command: ModuleCommand) {
        dispatcher.register(command)
    }

    override fun unregisterCommand(name: String) {
        dispatcher.unregister(name)
    }

    override fun registerCompleter(commandName: String, completer: (args: List<String>, prefix: String) -> List<String>) {
        dispatcher.registerCompleter(commandName, completer)
    }

    override fun registerRoutes(block: Route.() -> Unit, auth: AuthLevel) {
        when (auth) {
            AuthLevel.NONE -> _publicRoutes.add(block)
            AuthLevel.SERVICE -> _serviceRoutes.add(block)
            AuthLevel.ADMIN -> _adminRoutes.add(block)
        }
    }

    override fun registerPluginDeployment(deployment: PluginDeployment) {
        _pluginDeployments.add(deployment)
    }

    override fun registerMigrations(migrations: List<Migration>) {
        _migrations.addAll(migrations)
    }

    override fun registerEventFormatter(eventType: String, formatter: (data: Map<String, String>) -> String) {
        ConsoleFormatter.registerModuleEventFormatter(eventType, formatter)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getService(type: Class<T>): T? {
        return services[type] as? T
    }

    override fun <T : Any> registerService(type: Class<T>, instance: T) {
        services[type] = instance
    }
}
