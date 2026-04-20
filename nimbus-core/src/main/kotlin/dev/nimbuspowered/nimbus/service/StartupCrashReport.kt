package dev.nimbuspowered.nimbus.service

import java.time.Instant

/**
 * Operator-facing summary of why a service failed to start. Emitted once per
 * STARTING -> CRASHED transition and attached to the service (until the next
 * successful start) so the dashboard, console, and audit log can surface it
 * without replaying the full stdout stream.
 */
data class StartupCrashReport(
    /** One-line diagnosis, e.g. "Port 30001 already bound". */
    val diagnosis: String,
    /** Up to ~30 tail lines from the service's stdout, oldest first. */
    val logTail: List<String>,
    /** Process exit code if the JVM actually exited, or null for a ready-timeout. */
    val exitCode: Int?,
    /** Timestamp for sorting in dashboards and audit trails. */
    val at: Instant = Instant.now()
)
