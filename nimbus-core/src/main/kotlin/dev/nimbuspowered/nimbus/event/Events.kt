package dev.nimbuspowered.nimbus.event

import java.time.Instant

sealed class NimbusEvent {
    val timestamp: Instant = Instant.now()

    /** Who triggered this event. Set by console/API before emitting. */
    open var actor: String = "system"

    // Service lifecycle
    data class ServiceStarting(val serviceName: String, val groupName: String, val port: Int, val nodeId: String = "local") : NimbusEvent()
    data class ServiceReady(val serviceName: String, val groupName: String) : NimbusEvent()
    data class ServiceDraining(val serviceName: String, val groupName: String) : NimbusEvent()
    data class ServiceStopping(val serviceName: String) : NimbusEvent()
    data class ServiceStopped(val serviceName: String) : NimbusEvent()
    data class ServiceCrashed(
        val serviceName: String,
        val exitCode: Int,
        val restartAttempt: Int,
        /** Operator-readable one-liner from StartupDiagnostic. Null for non-startup crashes. */
        val diagnosis: String? = null,
        /** Up to ~50 tail lines of the service's stdout, oldest first. Empty when unavailable. */
        val logTail: List<String> = emptyList()
    ) : NimbusEvent()
    data class ServiceRecovered(val serviceName: String, val groupName: String, val pid: Long, val port: Int) : NimbusEvent()

    // Scaling
    data class ScaleUp(val groupName: String, val currentInstances: Int, val targetInstances: Int, val reason: String) : NimbusEvent()
    data class ScaleDown(val groupName: String, val serviceName: String, val reason: String) : NimbusEvent()
    /** A scheduled start was skipped because placement constraints could not be satisfied (e.g. pinned node offline). */
    data class PlacementBlocked(val groupName: String, val reason: String) : NimbusEvent()

    // State sync
    /** A state sync push committed successfully to the controller's canonical store. */
    data class SyncCompleted(
        val serviceName: String,
        val filesInManifest: Int,
        val filesReceived: Int,
        val bytesReceived: Long,
        val durationMs: Long
    ) : NimbusEvent()
    /** A state sync push failed — canonical copy may be stale or the push was rolled back. */
    data class SyncFailed(
        val serviceName: String,
        val reason: String
    ) : NimbusEvent()

    // Warm Pool
    data class ServicePrepared(val serviceName: String, val groupName: String) : NimbusEvent()
    data class WarmPoolReplenished(val groupName: String, val poolSize: Int) : NimbusEvent()

    // Custom state (set by plugins via SDK)
    data class ServiceCustomStateChanged(
        val serviceName: String,
        val groupName: String,
        val oldState: String?,
        val newState: String?
    ) : NimbusEvent()

    // Service Deployments
    data class ServiceDeployed(val serviceName: String, val groupName: String, val filesChanged: Int) : NimbusEvent()

    // Player
    data class PlayerConnected(val playerName: String, val uuid: String, val serviceName: String) : NimbusEvent()
    data class PlayerDisconnected(val playerName: String, val uuid: String, val serviceName: String) : NimbusEvent()
    data class PlayerServerSwitch(val playerName: String, val uuid: String, val fromService: String, val toService: String) : NimbusEvent()

    // Group management
    data class GroupCreated(val groupName: String) : NimbusEvent()
    data class GroupUpdated(val groupName: String) : NimbusEvent()
    data class GroupDeleted(val groupName: String) : NimbusEvent()

    // Messaging (service-to-service)
    data class ServiceMessage(
        val fromService: String,
        val toService: String,
        val channel: String,
        val data: Map<String, String>
    ) : NimbusEvent()

    // Module events (fired by controller modules — perms, display, etc.)
    data class ModuleEvent(val moduleId: String, val type: String, val data: Map<String, String>) : NimbusEvent()

    // Updates
    data class ProxyUpdateAvailable(val currentVersion: String, val newVersion: String) : NimbusEvent()
    data class ProxyUpdateApplied(val oldVersion: String, val newVersion: String) : NimbusEvent()

    // Nimbus self-update
    data class NimbusUpdateAvailable(val currentVersion: String, val newVersion: String, val updateType: String) : NimbusEvent()
    data class NimbusUpdateApplied(val oldVersion: String, val newVersion: String) : NimbusEvent()

    // Proxy Sync
    data class TabListUpdated(val header: String, val footer: String, val playerFormat: String, val updateInterval: Int) : NimbusEvent()
    data class MotdUpdated(val line1: String, val line2: String, val maxPlayers: Int, val playerCountOffset: Int) : NimbusEvent()
    data class PlayerTabUpdated(val uuid: String, val format: String?) : NimbusEvent()
    data class ChatFormatUpdated(val format: String, val enabled: Boolean) : NimbusEvent()

    // Cluster
    data class ClusterStarted(val bind: String, val port: Int, val strategy: String) : NimbusEvent()
    data class NodeConnected(val nodeId: String, val host: String) : NimbusEvent()
    data class NodeDisconnected(val nodeId: String) : NimbusEvent()
    data class NodeHeartbeat(val nodeId: String, val cpuUsage: Double, val services: Int) : NimbusEvent()

    // Load Balancer
    data class LoadBalancerStarted(val bind: String, val port: Int, val strategy: String) : NimbusEvent()
    data class LoadBalancerStopped(val reason: String = "shutdown") : NimbusEvent()
    data class LoadBalancerBackendHealthChanged(val host: String, val port: Int, val oldStatus: String, val newStatus: String) : NimbusEvent()

    // Maintenance
    data class MaintenanceEnabled(val scope: String, val reason: String = "") : NimbusEvent()  // scope = "global" or group name
    data class MaintenanceDisabled(val scope: String) : NimbusEvent()

    // Stress Test
    data class StressTestUpdated(val simulatedPlayers: Int, val targetPlayers: Int, val targetGroup: String?, val perService: Map<String, Int>) : NimbusEvent()

    // Dedicated
    data class DedicatedCreated(val name: String) : NimbusEvent()
    data class DedicatedDeleted(val name: String) : NimbusEvent()

    // Config
    data class ConfigReloaded(val groupsLoaded: Int) : NimbusEvent()

    // Module lifecycle
    data class ModuleLoaded(val moduleId: String, val moduleName: String, val moduleVersion: String) : NimbusEvent()
    data class ModuleEnabled(val moduleId: String, val moduleName: String) : NimbusEvent()
    data class ModuleDisabled(val moduleId: String, val moduleName: String) : NimbusEvent()

    // API lifecycle
    data class ApiStarted(val bind: String, val port: Int) : NimbusEvent()
    data class ApiStopped(val reason: String = "shutdown") : NimbusEvent()
    data class ApiWarning(val message: String) : NimbusEvent()
    data class ApiError(val error: String) : NimbusEvent()

    // Dashboard auth (forensic — fed into AuditCollector)
    /** A dashboard login completed end-to-end (incl. TOTP if required). */
    data class DashboardLoginSucceeded(
        val uuid: String,
        val name: String,
        val method: String,           // "code" | "magic_link" | "passkey"
        val totpUsed: Boolean,
        val ip: String?
    ) : NimbusEvent()
    /** A dashboard login attempt failed (invalid challenge, wrong TOTP, throttled, …). */
    data class DashboardLoginFailed(
        val reason: String,           // e.g. "invalid_challenge" | "totp_invalid" | "rate_limited"
        val uuid: String? = null,     // populated when the attempt resolved a user (e.g. TOTP step)
        val ip: String?
    ) : NimbusEvent()
    /** A dashboard session was revoked (logout, sibling-revoke, logout-all, admin action). */
    data class DashboardSessionRevoked(
        val uuid: String?,
        val sessionId: String?,
        val scope: String,            // "self" | "sibling" | "others" | "all" | "admin"
        val count: Int = 1
    ) : NimbusEvent()

    // Remote CLI sessions
    data class CliSessionConnected(
        val sessionId: Int, val remoteIp: String, val clientUsername: String,
        val clientHostname: String, val clientOs: String, val location: String
    ) : NimbusEvent()
    data class CliSessionDisconnected(
        val sessionId: Int, val remoteIp: String, val clientUsername: String,
        val durationSeconds: Long, val commandCount: Int
    ) : NimbusEvent()
}
