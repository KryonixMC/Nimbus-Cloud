package dev.nimbus.service

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
    var isStatic: Boolean = false
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(Service::class.java)

    @Volatile
    private var _state: ServiceState = initialState
    val state: ServiceState get() = _state

    fun transitionTo(newState: ServiceState): Boolean {
        val allowed = when (_state) {
            ServiceState.PREPARING -> setOf(ServiceState.STARTING, ServiceState.STOPPED)
            ServiceState.STARTING -> setOf(ServiceState.READY, ServiceState.CRASHED, ServiceState.STOPPED)
            ServiceState.READY -> setOf(ServiceState.STOPPING, ServiceState.CRASHED)
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
