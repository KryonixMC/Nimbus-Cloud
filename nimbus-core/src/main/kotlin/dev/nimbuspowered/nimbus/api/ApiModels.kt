package dev.nimbuspowered.nimbus.api

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
    val bedrockPort: Int? = null,
    val tps: Double = 20.0,
    val memoryUsedMb: Long = 0,
    val memoryMaxMb: Long = 0,
    val healthy: Boolean = true
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
    val activeInstances: Int,
    val modIds: List<String> = emptyList()
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

@Serializable
data class ServiceHealthSummaryResponse(
    val totalServices: Int,
    val readyServices: Int,
    val healthyServices: Int,
    val unhealthyServices: Int,
    val averageTps: Double,
    val totalMemoryUsedMb: Long,
    val totalMemoryMaxMb: Long,
    val memoryUsagePercent: Double,
    val services: List<ServiceHealthEntry>
)

@Serializable
data class ServiceHealthEntry(
    val name: String,
    val groupName: String,
    val state: String,
    val tps: Double,
    val memoryUsedMb: Long,
    val memoryMaxMb: Long,
    val healthy: Boolean,
    val restartCount: Int,
    val uptimeSeconds: Long?
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
data class ReportHealthRequest(
    val tps: Double,
    val memoryUsedMb: Long,
    val memoryMaxMb: Long
)

@Serializable
data class HealthReportResponse(
    val service: String,
    val healthy: Boolean
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
    val message: String,
    val error: String? = null
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

// ── Command DTOs ───────────────────────────────────────────────────

@Serializable
data class CommandSubcommandResponse(
    val path: String,
    val description: String,
    val usage: String,
    val completions: List<CommandCompletionResponse> = emptyList()
)

@Serializable
data class CommandCompletionResponse(
    val position: Int,
    val type: String
)

@Serializable
data class CommandMetaResponse(
    val name: String,
    val description: String,
    val usage: String,
    val permission: String,
    val subcommands: List<CommandSubcommandResponse>
)

@Serializable
data class CommandListResponse(
    val commands: List<CommandMetaResponse>,
    val total: Int
)

@Serializable
data class CommandExecuteRequest(
    val args: List<String> = emptyList()
)

@Serializable
data class OutputLineResponse(
    val type: String,
    val text: String
)

@Serializable
data class CommandExecuteResponse(
    val success: Boolean,
    val lines: List<OutputLineResponse>
)

// ── Console DTOs ───────────────────────────────────────────────────

@Serializable
data class CompleteRequest(
    val buffer: String,
    val cursor: Int = -1
)

@Serializable
data class CompleteResponse(
    val candidates: List<String>
)

@Serializable
data class ConsoleMessageIn(
    val type: String,
    val id: String = "",
    val input: String = "",
    val service: String = "",
    val text: String = ""
)

@Serializable
data class ConsoleMessageOut(
    val type: String,
    val id: String = "",
    val line: OutputLineResponse? = null,
    val event: EventMessage? = null,
    val text: String = "",
    val candidates: List<String> = emptyList()
)

// ── Event DTOs (for WebSocket) ──────────────────────────────────────

@Serializable
data class EventMessage(
    val type: String,
    val timestamp: String,
    val data: Map<String, String> = emptyMap()
)
