package dev.nimbuspowered.nimbus.module

/**
 * Interface for Nimbus controller modules.
 *
 * Modules are loaded from JAR files in the `modules/` directory at startup.
 * Each module can register commands, API routes, and event listeners.
 *
 * Lifecycle: [init] → [enable] → [disable]
 */
interface NimbusModule {

    /** Unique identifier, e.g. "perms", "display" */
    val id: String

    /** Human-readable name, e.g. "Permissions" */
    val name: String

    /** Module version */
    val version: String

    /** Short description shown in the setup wizard and `modules` command */
    val description: String

    /**
     * Called when the module is loaded. Use this to initialize managers,
     * register commands via [ModuleContext.registerCommand], and
     * register API routes via [ModuleContext.registerRoutes].
     */
    suspend fun init(context: ModuleContext)

    /**
     * Called after all modules have been initialized.
     * Use this for cross-module interactions or deferred setup.
     */
    suspend fun enable()

    /**
     * Called during shutdown. Clean up resources, cancel jobs.
     */
    fun disable()
}
