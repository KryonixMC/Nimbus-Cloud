package dev.nimbuspowered.nimbus.service

import kotlinx.coroutines.flow.SharedFlow
import kotlin.time.Duration

/**
 * Abstraction over a running service process.
 * - ProcessHandle: local process (existing implementation)
 * - RemoteServiceHandle: proxy to a service running on a remote agent node
 */
interface ServiceHandle {
    val stdoutLines: SharedFlow<String>
    fun isAlive(): Boolean
    fun pid(): Long?
    fun exitCode(): Int?
    suspend fun sendCommand(command: String)
    suspend fun waitForReady(timeout: Duration): Boolean
    suspend fun stopGracefully(timeout: Duration)
    suspend fun awaitExit(): Int?
    fun destroy()

    /**
     * Tag describing how the service is running. Used by dashboards + APIs to
     * surface (e.g.) a Docker badge without importing module-specific types.
     *
     * Values: `"process"` (bare Java process, default), `"docker"` (containerized),
     * `"remote"` (proxied to an agent node). Callers should treat unknown values
     * as `"process"`.
     */
    val kind: String get() = "process"

    /**
     * Returns up to the last ~30-50 stdout lines seen so far (oldest first).
     * Used by [StartupDiagnostic] to classify crashes without replaying the
     * full stdout stream. Implementations that do not buffer (remote, Docker)
     * return an empty list.
     */
    fun snapshotTail(): List<String> = emptyList()
}
