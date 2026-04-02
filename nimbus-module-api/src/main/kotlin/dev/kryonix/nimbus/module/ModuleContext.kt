package dev.kryonix.nimbus.module

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

    // ── Typed service accessors ─────────────────────────────
    // These return core types. Modules with compileOnly on nimbus-core
    // can cast them to the concrete types. Without core dependency,
    // use the generic getService() method.

    /**
     * Retrieve a core service by its class.
     * Returns null if the service is not available.
     *
     * Common services:
     * - `dev.kryonix.nimbus.event.EventBus`
     * - `dev.kryonix.nimbus.database.DatabaseManager`
     * - `dev.kryonix.nimbus.service.ServiceRegistry`
     * - `dev.kryonix.nimbus.group.GroupManager`
     * - `dev.kryonix.nimbus.config.NimbusConfig`
     */
    fun <T : Any> getService(type: Class<T>): T?
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
