package dev.nimbus.event

import java.time.Instant

sealed class NimbusEvent {
    val timestamp: Instant = Instant.now()

    // Service lifecycle
    data class ServiceStarting(val serviceName: String, val groupName: String, val port: Int) : NimbusEvent()
    data class ServiceReady(val serviceName: String, val groupName: String) : NimbusEvent()
    data class ServiceStopping(val serviceName: String) : NimbusEvent()
    data class ServiceStopped(val serviceName: String) : NimbusEvent()
    data class ServiceCrashed(val serviceName: String, val exitCode: Int, val restartAttempt: Int) : NimbusEvent()

    // Scaling
    data class ScaleUp(val groupName: String, val currentInstances: Int, val targetInstances: Int, val reason: String) : NimbusEvent()
    data class ScaleDown(val groupName: String, val serviceName: String, val reason: String) : NimbusEvent()

    // Custom state (set by plugins via SDK)
    data class ServiceCustomStateChanged(
        val serviceName: String,
        val groupName: String,
        val oldState: String?,
        val newState: String?
    ) : NimbusEvent()

    // Player (for future use)
    data class PlayerConnected(val playerName: String, val serviceName: String) : NimbusEvent()
    data class PlayerDisconnected(val playerName: String, val serviceName: String) : NimbusEvent()

    // Group management
    data class GroupCreated(val groupName: String) : NimbusEvent()
    data class GroupUpdated(val groupName: String) : NimbusEvent()
    data class GroupDeleted(val groupName: String) : NimbusEvent()

    // Config
    data class ConfigReloaded(val groupsLoaded: Int) : NimbusEvent()

    // API lifecycle
    data class ApiStarted(val bind: String, val port: Int) : NimbusEvent()
    data class ApiStopped(val reason: String = "shutdown") : NimbusEvent()
    data class ApiWarning(val message: String) : NimbusEvent()
    data class ApiError(val error: String) : NimbusEvent()
}
