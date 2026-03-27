package dev.nimbus.api

import kotlinx.serialization.Serializable

// ── Service DTOs ────────────────────────────────────────────────────

@Serializable
data class ServiceResponse(
    val name: String,
    val groupName: String,
    val port: Int,
    val state: String,
    val pid: Long?,
    val playerCount: Int,
    val startedAt: String?,
    val restartCount: Int,
    val uptime: String?
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
    val jvmArgs: List<String> = listOf("-XX:+UseG1GC")
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

// ── Generic Response ────────────────────────────────────────────────

@Serializable
data class ApiMessage(
    val success: Boolean,
    val message: String
)

// ── Event DTOs (for WebSocket) ──────────────────────────────────────

@Serializable
data class EventMessage(
    val type: String,
    val timestamp: String,
    val data: Map<String, String> = emptyMap()
)
