package dev.nimbuspowered.nimbus.module

import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.exposed.sql.Database
import java.nio.file.Path

/**
 * Provides access to core services for modules.
 * Passed to [NimbusModule.init] during module loading.
 *
 * For full access to core types (e.g. EventBus, PermissionManager),
 * modules can add `compileOnly(project(":nimbus-core"))` to their build
 * and cast the returned objects. The typed accessors here cover the
 * common cases without requiring a core dependency.
 */
interface ModuleContext {

    /** The coroutine scope tied to the Nimbus lifecycle. */
    val scope: CoroutineScope

    /** Base directory of the Nimbus installation. */
    val baseDir: Path

    /** Templates directory. */
    val templatesDir: Path

    /** The Exposed database instance for direct SQL access. */
    val database: Database

    /**
     * Returns the config directory for a module: `config/modules/<moduleId>/`
     * The directory is created if it doesn't exist.
     */
    fun moduleConfigDir(moduleId: String): Path

    /** Register a console command. */
    fun registerCommand(command: ModuleCommand)

    /** Unregister a previously registered console command by name. */
    fun unregisterCommand(name: String)

    /**
     * Register a tab completer for a command.
     * The completer receives the argument parts (after the command name) and the prefix being typed.
     * It should return matching candidates.
     */
    fun registerCompleter(commandName: String, completer: (args: List<String>, prefix: String) -> List<String>)

    /**
     * Register API routes for this module.
     * Routes are mounted when the API server starts.
     *
     * @param block Route definition block (Ktor routing DSL)
     * @param auth Authentication level required for these routes
     */
    fun registerRoutes(block: Route.() -> Unit, auth: AuthLevel = AuthLevel.SERVICE)

    /**
     * Register database migrations for this module.
     * Migrations are collected during [NimbusModule.init] and executed
     * after all modules have been enabled.
     *
     * Module migrations should use version ranges to avoid conflicts:
     * - Core: 1–999
     * - Perms module: 1000–1999
     * - Scaling module: 2000–2999
     */
    fun registerMigrations(migrations: List<Migration>) {}

    /**
     * Register a server-side plugin that should be deployed to backend services.
     * Called during [NimbusModule.init]. The core ServiceFactory will deploy
     * all registered plugins when creating service instances.
     */
    fun registerPluginDeployment(deployment: PluginDeployment)

    /**
     * Register a console formatter for a module event type.
     * When a [ModuleEvent][dev.nimbuspowered.nimbus.event.NimbusEvent.ModuleEvent] with the
     * given [eventType] is logged, the [formatter] produces the console output.
     *
     * @param eventType The event type string (e.g. "PERMISSION_GROUP_CREATED")
     * @param formatter Receives the event data map, returns formatted ANSI string
     */
    fun registerEventFormatter(eventType: String, formatter: (data: Map<String, String>) -> String)

    // ── Typed service accessors ─────────────────────────────
    // These return core types. Modules with compileOnly on nimbus-core
    // can cast them to the concrete types. Without core dependency,
    // use the generic getService() method.

    /**
     * Retrieve a core service by its class.
     * Returns null if the service is not available.
     *
     * Common services:
     * - `dev.nimbuspowered.nimbus.event.EventBus`
     * - `dev.nimbuspowered.nimbus.database.DatabaseManager`
     * - `dev.nimbuspowered.nimbus.service.ServiceRegistry`
     * - `dev.nimbuspowered.nimbus.service.ServiceManager`
     * - `dev.nimbuspowered.nimbus.group.GroupManager`
     * - `dev.nimbuspowered.nimbus.config.NimbusConfig`
     */
    fun <T : Any> getService(type: Class<T>): T?

    /**
     * Register an additional service that modules can access via [getService].
     * Used by the core to expose services that are created after module loading.
     */
    fun <T : Any> registerService(type: Class<T>, instance: T) {}

    /**
     * Look up a registered service by its fully-qualified class name.
     *
     * Each module JAR is loaded with its own `URLClassLoader`, so `Class<?>`
     * instances from different modules are never `==`-equal even when their
     * FQCN matches — which means [getService] cannot find a cross-module
     * service when the caller can't reference the concrete `Class<T>` at
     * compile time. This name-based lookup bridges that gap by scanning the
     * registered services for one whose class name matches.
     *
     * Returns `null` if no registered service has a matching name.
     */
    fun getServiceByClassName(fqcn: String): Any? = null

    /**
     * Register a diagnostic check that shows up in `doctor` / `GET /api/doctor`.
     * Call from [NimbusModule.init]; checks are invoked every time the doctor runs.
     */
    fun registerDoctorCheck(check: DoctorCheck) {}
}

/** Convenience reified version of [ModuleContext.getService]. */
inline fun <reified T : Any> ModuleContext.service(): T? = getService(T::class.java)

/** Authentication level for module API routes. */
enum class AuthLevel {
    /** No authentication required (public endpoints) */
    NONE,
    /** Service-level token (accessible by game servers and admins) */
    SERVICE,
    /** Admin-only token (master API token required) */
    ADMIN
}
