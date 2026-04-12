package dev.nimbuspowered.nimbus.service

import java.nio.file.Path
import java.time.Instant

class Service(
    val name: String,
    val groupName: String,
    val port: Int,
    var host: String = "127.0.0.1",
    var nodeId: String = "local",
    initialState: ServiceState = ServiceState.PREPARING,
    @Volatile var customState: String? = null,
    var pid: Long? = null,
    @Volatile var playerCount: Int = 0,
    /** Last time playerCount was reliably updated (via SDK report or successful SLP ping). */
    @Volatile var lastPlayerCountUpdate: Instant? = null,
    var startedAt: Instant? = null,
    var restartCount: Int = 0,
    var workingDirectory: Path,
    var isStatic: Boolean = false,
    var bedrockPort: Int? = null,
    /** Current TPS (ticks per second, 20.0 = perfect). Updated by SDK health reports. */
    @Volatile var tps: Double = 20.0,
    /**
     * Cached resident memory of the service process in MB. For local services,
     * populated by [ServiceMemoryResolver] by reading /proc on demand. For remote
     * (agent) services, pushed from agent cluster heartbeats.
     */
    @Volatile var memoryUsedMb: Long = 0,
    /** Whether this service is considered healthy (TPS >= 15). */
    @Volatile var healthy: Boolean = true,
    /** Last time a TPS report was received from the SDK. */
    @Volatile var lastHealthReport: Instant? = null,
    var isDedicated: Boolean = false,
    var proxyEnabled: Boolean = true
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(Service::class.java)

    @Volatile
    private var _state: ServiceState = initialState
    val state: ServiceState get() = _state

    /** Updates TPS from the SDK. Memory is read separately from /proc. */
    fun updateTps(tps: Double) {
        this.tps = tps
        this.lastHealthReport = Instant.now()
        this.healthy = tps >= 15.0
    }

    @Synchronized
    fun transitionTo(newState: ServiceState): Boolean {
        // Idempotent: the same state is a silent no-op. The agent and the local
        // tracker both emit state-changed events for the same transition, and we
        // used to log a warning every time the second one arrived.
        if (newState == _state) return true

        val allowed = when (_state) {
            ServiceState.PREPARING -> setOf(ServiceState.PREPARED, ServiceState.STARTING, ServiceState.STOPPING, ServiceState.STOPPED, ServiceState.CRASHED)
            // Shutdown during startup must be able to stop a service that's still in
            // PREPARED/STARTING — previously this threw "Invalid state transition" and
            // the backend process leaked.
            ServiceState.PREPARED -> setOf(ServiceState.STARTING, ServiceState.STOPPING, ServiceState.STOPPED)
            ServiceState.STARTING -> setOf(ServiceState.READY, ServiceState.STOPPING, ServiceState.CRASHED, ServiceState.STOPPED)
            // READY can jump straight to STOPPED on a clean remote exit (no DRAINING phase).
            ServiceState.READY -> setOf(ServiceState.DRAINING, ServiceState.STOPPING, ServiceState.STOPPED, ServiceState.CRASHED)
            ServiceState.DRAINING -> setOf(ServiceState.STOPPING, ServiceState.STOPPED, ServiceState.CRASHED)
            ServiceState.STOPPING -> setOf(ServiceState.STOPPED, ServiceState.CRASHED)
            // STOPPED is terminal in principle but the ready-timeout handler may
            // also fire on a service that has already been marked STOPPED — treat
            // CRASHED as an allowed relabel rather than a state error.
            ServiceState.STOPPED -> setOf(ServiceState.CRASHED)
            // CRASHED must allow STOPPING so the operator can clean up a dead remote
            // service via the stop/restart API. Without this the service is stuck
            // until controller restart.
            ServiceState.CRASHED -> setOf(ServiceState.PREPARING, ServiceState.STOPPING, ServiceState.STOPPED)
        }
        if (newState !in allowed) {
            logger.warn("Invalid state transition for '{}': {} -> {}", name, _state, newState)
            return false
        }
        _state = newState
        return true
    }
}
