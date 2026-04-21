package dev.nimbuspowered.nimbus.module.api

/**
 * Severity of a doctor finding.
 *
 * - [OK]   — the check passed, informational only.
 * - [WARN] — the deployment is functional but has a suboptimal state the operator should review.
 * - [FAIL] — the deployment is (partly) broken and requires operator action.
 */
enum class DoctorLevel { OK, WARN, FAIL }

/**
 * A single finding produced by a [DoctorCheck].
 *
 * @param level    severity of the finding
 * @param message  short, human-readable description of what was observed
 * @param hint     optional remediation advice — ideally actionable ("run `x`", "edit `y`")
 */
data class DoctorFinding(
    val level: DoctorLevel,
    val message: String,
    val hint: String? = null
)

/**
 * Pluggable diagnostic check registered by a core area or a module.
 *
 * Modules register checks via [ModuleContext.registerDoctorCheck]; the core
 * doctor runner invokes [run] and groups findings under [section].
 *
 * Implementations **must be quick** (<1s wall time) and **must not mutate**
 * state — doctor can be run at any time, including against production clusters.
 */
interface DoctorCheck {
    /**
     * Display label for this check's section in the report (e.g. `"Punishments"`).
     * Multiple checks with the same section are merged in order of registration.
     */
    val section: String

    /** Run the check and return any findings. Returning an empty list implies "nothing to say". */
    suspend fun run(): List<DoctorFinding>
}
