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
    /** Used heap memory in MB. Updated by SDK health reports. */
    @Volatile var memoryUsedMb: Long = 0,
    /** Max heap memory in MB. Updated by SDK health reports. */
    @Volatile var memoryMaxMb: Long = 0,
    /** Whether this service is considered healthy (TPS >= 15, memory < 95%). */
    @Volatile var healthy: Boolean = true,
    /** Last time a health report was received from the SDK. */
    @Volatile var lastHealthReport: Instant? = null
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(Service::class.java)

    @Volatile
    private var _state: ServiceState = initialState
    val state: ServiceState get() = _state

    fun updateHealth(tps: Double, usedMb: Long, maxMb: Long) {
        this.tps = tps
        this.memoryUsedMb = usedMb
        this.memoryMaxMb = maxMb
        this.lastHealthReport = Instant.now()
        this.healthy = tps >= 15.0 && (maxMb == 0L || usedMb.toDouble() / maxMb < 0.95)
    }

    @Synchronized
    fun transitionTo(newState: ServiceState): Boolean {
        val allowed = when (_state) {
            ServiceState.PREPARING -> setOf(ServiceState.STARTING, ServiceState.STOPPED)
            ServiceState.STARTING -> setOf(ServiceState.READY, ServiceState.CRASHED, ServiceState.STOPPED)
            ServiceState.READY -> setOf(ServiceState.DRAINING, ServiceState.STOPPING, ServiceState.CRASHED)
            ServiceState.DRAINING -> setOf(ServiceState.STOPPING, ServiceState.CRASHED)
            ServiceState.STOPPING -> setOf(ServiceState.STOPPED)
            ServiceState.STOPPED -> emptySet()
            ServiceState.CRASHED -> setOf(ServiceState.PREPARING)
        }
        if (newState !in allowed) {
            logger.warn("Invalid state transition for '{}': {} -> {}", name, _state, newState)
            return false
        }
        _state = newState
        return true
    }
}
