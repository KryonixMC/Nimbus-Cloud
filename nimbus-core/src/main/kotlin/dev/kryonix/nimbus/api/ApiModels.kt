package dev.kryonix.nimbus.api

import kotlinx.serialization.Serializable

// ── Service DTOs ────────────────────────────────────────────────────

@Serializable
data class ServiceResponse(
    val name: String,
    val groupName: String,
    val port: Int,
    val host: String = "127.0.0.1",
    val nodeId: String = "local",
    val state: String,
    val customState: String? = null,
    val pid: Long?,
    val playerCount: Int,
    val startedAt: String?,
    val restartCount: Int,
    val uptime: String?,
    val isStatic: Boolean = false,
    val bedrockPort: Int? = null
)

@Serializable
data class ServiceListResponse(
    val services: List<ServiceResponse>,
    val total: Int
)

// ── Group DTOs ──────────────────────────────────────────────────────

@Serializable
data class GroupResponse(
    val name: String,
    val type: String,
    val software: String,
    val version: String,
    val template: String,
    val resources: GroupResourcesResponse,
    val scaling: GroupScalingResponse,
    val lifecycle: GroupLifecycleResponse,
    val jvmArgs: List<String>,
    val jvmOptimize: Boolean,
    val activeInstances: Int
)

@Serializable
data class GroupResourcesResponse(
    val memory: String,
    val maxPlayers: Int
)

@Serializable
data class GroupScalingResponse(
    val minInstances: Int,
    val maxInstances: Int,
    val playersPerInstance: Int,
    val scaleThreshold: Double,
    val idleTimeout: Long
)

@Serializable
data class GroupLifecycleResponse(
    val stopOnEmpty: Boolean,
    val restartOnCrash: Boolean,
    val maxRestarts: Int
)

@Serializable
data class GroupListResponse(
    val groups: List<GroupResponse>,
    val total: Int
)

@Serializable
data class CreateGroupRequest(
    val name: String,
    val type: String = "DYNAMIC",
    val template: String,
    val software: String = "PAPER",
    val version: String = "1.21.4",
    val modloaderVersion: String = "",
    val jarName: String = "",
    val readyPattern: String = "",
    val memory: String = "1G",
    val maxPlayers: Int = 50,
    val minInstances: Int = 1,
    val maxInstances: Int = 4,
    val playersPerInstance: Int = 40,
    val scaleThreshold: Double = 0.8,
    val idleTimeout: Long = 0,
    val stopOnEmpty: Boolean = false,
    val restartOnCrash: Boolean = true,
    val maxRestarts: Int = 5,
    val jvmArgs: List<String> = emptyList(),
    val jvmOptimize: Boolean = true
)

// ── Network / Status DTOs ───────────────────────────────────────────

@Serializable
data class StatusResponse(
    val networkName: String,
    val online: Boolean,
    val uptimeSeconds: Long,
    val totalServices: Int,
    val totalPlayers: Int,
    val groups: List<GroupStatusResponse>
)

@Serializable
data class GroupStatusResponse(
    val name: String,
    val instances: Int,
    val maxInstances: Int,
    val players: Int,
    val maxPlayers: Int,
    val software: String,
    val version: String
)

@Serializable
data class PlayerInfo(
    val name: String,
    val service: String
)

@Serializable
data class PlayersResponse(
    val players: List<PlayerInfo>,
    val total: Int
)

@Serializable
data class SendPlayerRequest(
    val targetService: String
)

@Serializable
data class KickPlayerRequest(
    val reason: String = "You have been kicked from the network."
)

@Serializable
data class BroadcastRequest(
    val message: String,
    val group: String? = null
)

@Serializable
data class BroadcastResponse(
    val success: Boolean,
    val message: String,
    val services: Int
)

// ── System DTOs ─────────────────────────────────────────────────────

@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    val uptimeSeconds: Long,
    val services: Int,
    val apiEnabled: Boolean
)

@Serializable
data class ReloadResponse(
    val success: Boolean,
    val groupsLoaded: Int,
    val message: String
)

// ── Log DTOs ────────────────────────────────────────────────────────

@Serializable
data class LogsResponse(
    val service: String,
    val lines: List<String>,
    val total: Int
)

// ── Command DTOs ────────────────────────────────────────────────────

@Serializable
data class ExecRequest(
    val command: String
)

@Serializable
data class ExecResponse(
    val success: Boolean,
    val service: String,
    val command: String
)

// ── Custom State DTOs ───────────────────────────────────────────────

@Serializable
data class SetCustomStateRequest(
    val customState: String?
)

@Serializable
data class CustomStateResponse(
    val service: String,
    val customState: String?
)

// ── Player Count DTOs ───────────────────────────────────────────────

@Serializable
data class ReportPlayerCountRequest(
    val playerCount: Int
)

@Serializable
data class PlayerCountResponse(
    val service: String,
    val playerCount: Int
)

// ── Messaging DTOs ──────────────────────────────────────────────────

@Serializable
data class SendMessageRequest(
    val from: String,
    val channel: String,
    val data: Map<String, String> = emptyMap()
)

// ── Generic Response ────────────────────────────────────────────────

@Serializable
data class ApiMessage(
    val success: Boolean,
    val message: String
)

// ── File API DTOs ──────────────────────────────────────────────────

@Serializable
data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: String? = null
)

@Serializable
data class FileListResponse(
    val scope: String,
    val path: String,
    val entries: List<FileEntry>,
    val total: Int
)

@Serializable
data class FileContentResponse(
    val scope: String,
    val path: String,
    val content: String,
    val size: Long
)

@Serializable
data class FileWriteRequest(
    val content: String
)

@Serializable
data class FileUploadResponse(
    val success: Boolean,
    val path: String,
    val size: Long
)

// ── Config DTOs ────────────────────────────────────────────────────

@Serializable
data class ConfigResponse(
    val network: ConfigNetworkResponse,
    val controller: ConfigControllerResponse,
    val console: ConfigConsoleResponse,
    val paths: ConfigPathsResponse,
    val api: ConfigApiResponse
)

@Serializable
data class ConfigNetworkResponse(
    val name: String,
    val bind: String
)

@Serializable
data class ConfigControllerResponse(
    val maxMemory: String,
    val maxServices: Int,
    val heartbeatInterval: Long
)

@Serializable
data class ConfigConsoleResponse(
    val colored: Boolean,
    val logEvents: Boolean
)

@Serializable
data class ConfigPathsResponse(
    val templates: String,
    val services: String,
    val logs: String
)

@Serializable
data class ConfigApiResponse(
    val enabled: Boolean,
    val bind: String,
    val port: Int,
    val hasToken: Boolean,
    val allowedOrigins: List<String>
)

@Serializable
data class ConfigUpdateRequest(
    val networkName: String? = null,
    val consoleColored: Boolean? = null,
    val consoleLogEvents: Boolean? = null
)

// ── Display DTOs ───────────────────────────────────────────────────

@Serializable
data class DisplayResponse(
    val name: String,
    val sign: SignDisplayResponse,
    val npc: NpcDisplayResponse,
    val states: Map<String, String>
)

@Serializable
data class SignDisplayResponse(
    val line1: String,
    val line2: String,
    val line3: String,
    val line4Online: String,
    val line4Offline: String
)

@Serializable
data class NpcDisplayResponse(
    val displayName: String,
    val subtitle: String,
    val subtitleOffline: String,
    val floatingItem: String,
    val statusItems: Map<String, String>,
    val inventory: NpcInventoryResponse
)

@Serializable
data class NpcInventoryResponse(
    val title: String,
    val size: Int,
    val itemName: String,
    val itemLore: List<String>
)

@Serializable
data class DisplayListResponse(
    val displays: List<DisplayResponse>,
    val total: Int
)

@Serializable
data class UpdateDisplayRequest(
    val sign: SignDisplayResponse? = null,
    val npc: NpcDisplayResponse? = null,
    val states: Map<String, String>? = null
)

// ── Permission DTOs ─────────────────────────────────────────────────

@Serializable
data class PermissionGroupResponse(
    val name: String,
    val default: Boolean,
    val prefix: String = "",
    val suffix: String = "",
    val priority: Int = 0,
    val weight: Int = 0,
    val permissions: List<String>,
    val parents: List<String>,
    val meta: Map<String, String> = emptyMap()
)

@Serializable
data class PermissionGroupListResponse(
    val groups: List<PermissionGroupResponse>,
    val total: Int
)

@Serializable
data class CreatePermissionGroupRequest(
    val name: String,
    val default: Boolean = false
)

@Serializable
data class UpdatePermissionGroupRequest(
    val default: Boolean? = null,
    val prefix: String? = null,
    val suffix: String? = null,
    val priority: Int? = null,
    val weight: Int? = null,
    val permissions: List<String>? = null,
    val parents: List<String>? = null,
    val meta: Map<String, String>? = null
)

@Serializable
data class PermissionModifyRequest(
    val permission: String,
    val server: String? = null,
    val world: String? = null,
    val expiresAt: String? = null
)

@Serializable
data class ParentModifyRequest(
    val parent: String
)

@Serializable
data class PlayerPermissionResponse(
    val uuid: String,
    val name: String,
    val groups: List<String>,
    val effectivePermissions: List<String>,
    val prefix: String = "",
    val suffix: String = "",
    val displayGroup: String = "",
    val priority: Int = 0,
    val meta: Map<String, String> = emptyMap()
)

@Serializable
data class PlayerRegisterRequest(
    val name: String
)

@Serializable
data class PlayerGroupRequest(
    val group: String,
    val name: String? = null,
    val server: String? = null,
    val world: String? = null,
    val expiresAt: String? = null
)

@Serializable
data class PermissionCheckResponse(
    val uuid: String,
    val permission: String,
    val allowed: Boolean
)

// ── Tracks ──────────────────────────────────────────────────────────

@Serializable
data class PermissionTrackResponse(
    val name: String,
    val groups: List<String>
)

@Serializable
data class PermissionTrackListResponse(
    val tracks: List<PermissionTrackResponse>,
    val total: Int
)

@Serializable
data class CreateTrackRequest(
    val name: String,
    val groups: List<String>
)

@Serializable
data class PromoteDemoteResponse(
    val success: Boolean,
    val previousGroup: String? = null,
    val newGroup: String? = null,
    val message: String
)

// ── Meta ────────────────────────────────────────────────────────────

@Serializable
data class MetaResponse(
    val meta: Map<String, String>
)

@Serializable
data class MetaSetRequest(
    val key: String,
    val value: String
)

// ── Debug ───────────────────────────────────────────────────────────

@Serializable
data class PermissionDebugResponse(
    val uuid: String,
    val permission: String,
    val result: Boolean,
    val reason: String,
    val chain: List<DebugStepResponse>
)

@Serializable
data class DebugStepResponse(
    val source: String,
    val permission: String,
    val type: String,
    val granted: Boolean
)

// ── Bulk Operations ────────────────────────────────────────────────

@Serializable
data class BulkPermissionRequest(
    val groups: List<String>,
    val permission: String,
    val server: String? = null,
    val world: String? = null,
    val expiresAt: String? = null
)

@Serializable
data class BulkGroupAssignRequest(
    val players: List<String>,
    val group: String,
    val server: String? = null,
    val world: String? = null,
    val expiresAt: String? = null
)

@Serializable
data class BulkOperationResponse(
    val success: Boolean,
    val processed: Int,
    val failed: Int,
    val errors: List<String> = emptyList()
)

// ── Audit ───────────────────────────────────────────────────────────

@Serializable
data class AuditEntryResponse(
    val timestamp: String,
    val actor: String,
    val action: String,
    val target: String,
    val details: String
)

@Serializable
data class AuditLogResponse(
    val entries: List<AuditEntryResponse>,
    val total: Int
)

// ── Proxy Sync DTOs ────────────────────────────────────────────────

@Serializable
data class ProxySyncResponse(
    val tablist: TabListResponse,
    val motd: MotdResponse,
    val chat: ChatResponse,
    val maintenance: ProxyMaintenanceResponse? = null,
    val version: String? = null
)

@Serializable
data class ProxyMaintenanceResponse(
    val globalEnabled: Boolean,
    val motdLine1: String,
    val motdLine2: String,
    val protocolText: String,
    val kickMessage: String,
    val whitelist: List<String>,
    val groups: Map<String, String> = emptyMap()  // groupName -> kickMessage (only enabled groups)
)

@Serializable
data class TabListResponse(
    val header: String,
    val footer: String,
    val playerFormat: String,
    val updateInterval: Int
)

@Serializable
data class TabListUpdateRequest(
    val header: String? = null,
    val footer: String? = null,
    val playerFormat: String? = null,
    val updateInterval: Int? = null
)

@Serializable
data class MotdResponse(
    val line1: String,
    val line2: String,
    val maxPlayers: Int,
    val playerCountOffset: Int
)

@Serializable
data class MotdUpdateRequest(
    val line1: String? = null,
    val line2: String? = null,
    val maxPlayers: Int? = null,
    val playerCountOffset: Int? = null
)

@Serializable
data class PlayerTabFormatRequest(
    val format: String
)

@Serializable
data class PlayerTabOverridesResponse(
    val overrides: Map<String, String>,
    val total: Int
)

@Serializable
data class ChatResponse(
    val format: String,
    val enabled: Boolean
)

@Serializable
data class ChatUpdateRequest(
    val format: String? = null,
    val enabled: Boolean? = null
)

// ── Node / Cluster DTOs ────────────────────────────────────────────

@Serializable
data class NodeResponse(
    val nodeId: String,
    val host: String,
    val maxMemory: String,
    val maxServices: Int,
    val currentServices: Int,
    val cpuUsage: Double,
    val memoryUsedMb: Long,
    val memoryTotalMb: Long,
    val isConnected: Boolean,
    val agentVersion: String,
    val os: String,
    val arch: String,
    val services: List<String>
)

@Serializable
data class NodeListResponse(
    val nodes: List<NodeResponse>,
    val total: Int
)

@Serializable
data class LoadBalancerResponse(
    val enabled: Boolean,
    val bind: String,
    val port: Int,
    val strategy: String,
    val proxyProtocol: Boolean,
    val totalConnections: Long,
    val activeConnections: Int,
    val rejectedConnections: Long,
    val backends: List<LbBackendResponse>
)

@Serializable
data class LbBackendResponse(
    val name: String,
    val host: String,
    val port: Int,
    val playerCount: Int,
    val health: String,
    val connectionCount: Int
)

// ── Maintenance DTOs ───────────────────────────────────────────────

@Serializable
data class MaintenanceStatusResponse(
    val global: GlobalMaintenanceResponse,
    val groups: Map<String, GroupMaintenanceResponse>
)

@Serializable
data class GlobalMaintenanceResponse(
    val enabled: Boolean,
    val motdLine1: String,
    val motdLine2: String,
    val protocolText: String,
    val kickMessage: String,
    val whitelist: List<String>
)

@Serializable
data class GroupMaintenanceResponse(
    val enabled: Boolean,
    val kickMessage: String
)

@Serializable
data class MaintenanceToggleRequest(
    val enabled: Boolean,
    val reason: String = ""
)

@Serializable
data class GlobalMaintenanceUpdateRequest(
    val motdLine1: String? = null,
    val motdLine2: String? = null,
    val protocolText: String? = null,
    val kickMessage: String? = null
)

@Serializable
data class GroupMaintenanceUpdateRequest(
    val kickMessage: String? = null
)

@Serializable
data class MaintenanceWhitelistRequest(
    val entry: String
)

// ── Stress Test DTOs ──────────────────────────────────────────────

@Serializable
data class StressStatusResponse(
    val active: Boolean,
    val group: String? = null,
    val currentPlayers: Int = 0,
    val targetPlayers: Int = 0,
    val totalCapacity: Int = 0,
    val overflow: Int = 0,
    val elapsedSeconds: Long = 0,
    val services: Map<String, Int> = emptyMap(),
    val proxyServices: Map<String, Int> = emptyMap()
)

@Serializable
data class StressStartRequest(
    val players: Int,
    val group: String? = null,
    val rampSeconds: Long = 0
)

@Serializable
data class StressRampRequest(
    val players: Int,
    val durationSeconds: Long = 30
)

// ── Event DTOs (for WebSocket) ──────────────────────────────────────

@Serializable
data class EventMessage(
    val type: String,
    val timestamp: String,
    val data: Map<String, String> = emptyMap()
)
