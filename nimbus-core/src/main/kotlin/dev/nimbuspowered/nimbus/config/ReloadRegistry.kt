package dev.nimbuspowered.nimbus.config

import kotlinx.serialization.Serializable

/**
 * Declares the reload behavior of every config section the operator might
 * touch. Scopes let the `/api/reload` endpoint (and the console `reload`
 * command) tell the operator *clearly* what just became active vs. what
 * needs a controller restart — the single most common source of "I edited
 * the file and nothing changed" confusion.
 */
@Serializable
enum class ReloadScope {
    /** Config change takes effect immediately — no action required. */
    LIVE,

    /** Existing services keep their old value; new starts use the new value. */
    NEXT_SERVICE_PREPARE,

    /** Controller must be restarted for the change to take effect. */
    REQUIRES_RESTART
}

@Serializable
data class ReloadSection(
    /** Dotted TOML path, e.g. "groups", "cluster", "sandbox.default_mode". */
    val name: String,
    val scope: ReloadScope,
    /** True if this section was actually picked up by the current reload. */
    val applied: Boolean,
    /** Operator-readable one-liner about what this section controls. */
    val description: String
)

@Serializable
data class ReloadReport(
    val success: Boolean,
    /** Backwards-compatible field — dashboard versions before v0.12 read this directly. */
    val groupsLoaded: Int,
    /** Short human summary; kept for backwards compat with legacy dashboard field. */
    val message: String,
    val sections: List<ReloadSection> = emptyList(),
    /**
     * Reference list — the names of every section whose reload scope is
     * [ReloadScope.REQUIRES_RESTART]. **This is not a per-request delta** — it
     * does NOT mean the operator just changed these sections, only that if they
     * were to change them, a controller restart would be required for the new
     * values to take effect. Useful as a lookup for dashboard tooltips and for
     * the console `reload` command's scope summary. For a per-request "did I
     * just touch a restart-only section" signal, diff changes and compare
     * against this list client-side.
     */
    val requiresRestartIfChanged: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

/**
 * Central, static catalog of what *each* known config section does on reload.
 * Kept flat and explicit so adding a section is one line. Order affects
 * display order in the console output.
 *
 * Keep this in sync with the [NimbusConfig] top-level fields and the module
 * config TOMLs. When in doubt, err on the side of REQUIRES_RESTART — it's
 * better to surprise an operator with "restart needed" than with "nothing
 * happened".
 */
object ReloadRegistry {

    private val SECTIONS: List<Section> = listOf(
        Section("groups", ReloadScope.LIVE,
            "Group definitions (scaling, lifecycle, JVM, placement, sync, sandbox overrides)."),
        Section("dedicated", ReloadScope.LIVE,
            "Dedicated service definitions — new services appear, removed ones keep running until stopped."),
        Section("modules.syncproxy.motd", ReloadScope.LIVE,
            "Proxy MOTD + maintenance mode."),
        Section("modules.syncproxy.tablist", ReloadScope.LIVE,
            "Proxy tab list header, footer, and player format."),
        Section("modules.syncproxy.chat", ReloadScope.LIVE,
            "Proxy chat format rules."),
        Section("modules.backup", ReloadScope.LIVE,
            "Backup schedules, retention, scope, excludes, compression. Re-applied via PUT /api/backups/config."),
        Section("network", ReloadScope.REQUIRES_RESTART,
            "Network name and bind — display-only changes still require a restart for consistency."),
        Section("controller", ReloadScope.REQUIRES_RESTART,
            "Controller memory caps, service caps, heartbeat, and scaling tick interval."),
        Section("api", ReloadScope.REQUIRES_RESTART,
            "API bind/port/token/JWT/CORS settings — the HTTP server is bound once at boot."),
        Section("database", ReloadScope.REQUIRES_RESTART,
            "Database type, credentials, pool size — connections are opened once at boot."),
        Section("audit", ReloadScope.REQUIRES_RESTART,
            "Audit enable flag and retention — the cleanup loop reads this once at boot."),
        Section("metrics", ReloadScope.REQUIRES_RESTART,
            "Metrics retention — the cleanup loop reads this once at boot."),
        Section("bedrock", ReloadScope.NEXT_SERVICE_PREPARE,
            "Bedrock enable flag + base port — effective when the next service is prepared."),
        Section("cluster", ReloadScope.REQUIRES_RESTART,
            "Cluster TLS material, agent port, placement strategy, disk quota — the cluster server binds once at boot."),
        Section("java", ReloadScope.NEXT_SERVICE_PREPARE,
            "Java executable paths per version — applied when the next service is prepared."),
        Section("loadbalancer", ReloadScope.REQUIRES_RESTART,
            "Load balancer bind/port/strategy — the TCP listener is bound once at boot."),
        Section("sandbox", ReloadScope.NEXT_SERVICE_PREPARE,
            "Global sandbox defaults (cgroup overhead, default mode) — applied at the next service spawn."),
        Section("punishments", ReloadScope.REQUIRES_RESTART,
            "Punishments module plugin deploy, cache TTL, expiry interval."),
        Section("resourcepacks", ReloadScope.REQUIRES_RESTART,
            "Resource packs module plugin deploy, upload limits, public URL."),
        Section("dashboard", ReloadScope.REQUIRES_RESTART,
            "Dashboard public URL — used by auth magic-link generation at request time, but cached at boot."),
        Section("curseforge", ReloadScope.LIVE,
            "CurseForge API key — read at request time by modpack import flows."),
        Section("console", ReloadScope.REQUIRES_RESTART,
            "Console color flag, event-log flag, history file, geo-lookup opt-in.")
    )

    private data class Section(val name: String, val scope: ReloadScope, val description: String)

    /**
     * Builds a ReloadReport given which sections the current reload pass actually
     * touched. Sections not listed as "applied" still appear with `applied=false`
     * so the dashboard/console shows the full scope landscape, not just the
     * delta, and the operator learns what exists.
     */
    fun buildReport(
        success: Boolean,
        groupsLoaded: Int,
        appliedSections: Set<String>,
        message: String,
        warnings: List<String> = emptyList()
    ): ReloadReport {
        val sections = SECTIONS.map { s ->
            ReloadSection(
                name = s.name,
                scope = s.scope,
                applied = s.name in appliedSections,
                description = s.description
            )
        }
        val restartScopeSections = sections
            .filter { it.scope == ReloadScope.REQUIRES_RESTART }
            .map { it.name }
        return ReloadReport(
            success = success,
            groupsLoaded = groupsLoaded,
            message = message,
            sections = sections,
            requiresRestartIfChanged = restartScopeSections,
            warnings = warnings
        )
    }
}
