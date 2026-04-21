package dev.nimbuspowered.nimbus.module.api

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

    /**
     * Optional dashboard configuration for the Web UI.
     * Return null if this module has no dashboard page.
     *
     * The dashboard automatically renders a page for each module that provides
     * a [DashboardConfig]. The page path is `/modules/{id}`.
     *
     * Custom modules can provide their own dashboard panels by overriding this.
     * The [DashboardConfig.apiPrefix] tells the dashboard which API prefix
     * to use for fetching data (e.g. "/api/permissions", "/api/players").
     */
    val dashboardConfig: DashboardConfig? get() = null
}

/**
 * Configuration for a module's dashboard page in the Web UI.
 *
 * @param icon Lucide icon name (e.g. "Shield", "Users", "Monitor")
 * @param apiPrefix API route prefix registered by this module (e.g. "/api/permissions")
 * @param sections List of dashboard sections to render
 */
data class DashboardConfig(
    val icon: String = "Box",
    val apiPrefix: String = "",
    val sections: List<DashboardSection> = emptyList()
)

/**
 * A section on a module's dashboard page.
 *
 * @param title Section heading
 * @param type Section type: "table", "stats", "config"
 * @param endpoint API endpoint relative to [DashboardConfig.apiPrefix]
 */
data class DashboardSection(
    val title: String,
    val type: String,
    val endpoint: String
)
