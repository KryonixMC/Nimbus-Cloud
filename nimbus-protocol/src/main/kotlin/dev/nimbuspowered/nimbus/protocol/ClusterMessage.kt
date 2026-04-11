package dev.nimbuspowered.nimbus.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val clusterJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

/**
 * All messages exchanged between controller and agent.
 * The "type" field in JSON is the discriminator.
 */
@Serializable
sealed class ClusterMessage {

    // ── Controller -> Agent ────────────────────────────

    @Serializable @SerialName("AUTH_RESPONSE")
    data class AuthResponse(
        val accepted: Boolean,
        val nodeId: String = "",
        val reason: String = ""
    ) : ClusterMessage()

    @Serializable @SerialName("START_SERVICE")
    data class StartService(
        val serviceName: String,
        val groupName: String,
        val port: Int,
        val templateName: String,
        val templateNames: List<String> = emptyList(),
        val templateHash: String,
        val software: String,
        val version: String,
        val memory: String,
        val jvmArgs: List<String>,
        val jvmOptimize: Boolean = true,
        val jarName: String,
        val modloaderVersion: String = "",
        val readyPattern: String = "",
        val readyTimeoutSeconds: Int = 60,
        val forwardingMode: String = "modern",
        val forwardingSecret: String = "",
        val isStatic: Boolean = false,
        val isModded: Boolean = false,
        val customJarName: String = "",
        val apiUrl: String = "",
        val apiToken: String = "",
        val nimbusProperties: Map<String, String> = emptyMap(),
        val javaVersion: Int = 0,
        val bedrockPort: Int = 0,
        val bedrockEnabled: Boolean = false,
        /** When true, agent pulls canonical state from controller before start and pushes back on stop. */
        val syncEnabled: Boolean = false,
        /** rsync-style exclude globs applied to both pull and push. Files matching these are never synced. */
        val syncExcludes: List<String> = emptyList(),
        /** Dedicated service (single-instance, persistent data, no template). Always implies sync. */
        val isDedicated: Boolean = false
    ) : ClusterMessage()

    @Serializable @SerialName("STOP_SERVICE")
    data class StopService(
        val serviceName: String,
        val timeoutSeconds: Int = 30
    ) : ClusterMessage()

    @Serializable @SerialName("SEND_COMMAND")
    data class SendCommand(
        val serviceName: String,
        val command: String
    ) : ClusterMessage()

    @Serializable @SerialName("HEARTBEAT_REQUEST")
    data class HeartbeatRequest(
        val timestamp: Long = System.currentTimeMillis()
    ) : ClusterMessage()

    @Serializable @SerialName("TEMPLATE_INFO")
    data class TemplateInfo(
        val templateName: String,
        val hash: String,
        val downloadUrl: String,
        val sizeBytes: Long
    ) : ClusterMessage()

    @Serializable @SerialName("SHUTDOWN_AGENT")
    data class ShutdownAgent(
        val reason: String = "controller shutdown",
        val graceful: Boolean = true
    ) : ClusterMessage()

    // ── Remote File Management ─────────────────────────

    @Serializable @SerialName("FILE_LIST_REQUEST")
    data class FileListRequest(
        val serviceName: String,
        val path: String,
        val requestId: String
    ) : ClusterMessage()

    @Serializable @SerialName("FILE_READ_REQUEST")
    data class FileReadRequest(
        val serviceName: String,
        val path: String,
        val requestId: String
    ) : ClusterMessage()

    @Serializable @SerialName("FILE_WRITE_REQUEST")
    data class FileWriteRequest(
        val serviceName: String,
        val path: String,
        val content: String,
        val requestId: String
    ) : ClusterMessage()

    @Serializable @SerialName("FILE_DELETE_REQUEST")
    data class FileDeleteRequest(
        val serviceName: String,
        val path: String,
        val requestId: String
    ) : ClusterMessage()

    // ── Agent -> Controller ────────────────────────────

    @Serializable @SerialName("AUTH_REQUEST")
    data class AuthRequest(
        val token: String,
        val nodeName: String,
        val maxMemory: String,
        val maxServices: Int,
        val currentServices: Int = 0,
        val agentVersion: String = "dev",
        val os: String = "",
        val arch: String = "",
        // Host system specs (reported once at auth time — static for the lifetime of the agent)
        val hostname: String = "",
        val osVersion: String = "",
        val cpuModel: String = "",
        val availableProcessors: Int = 0,
        val systemMemoryTotalMb: Long = 0,
        val javaVersion: String = "",
        val javaVendor: String = ""
    ) : ClusterMessage()

    @Serializable @SerialName("HEARTBEAT_RESPONSE")
    data class HeartbeatResponse(
        val timestamp: Long = System.currentTimeMillis(),
        /** System-wide CPU load, 0.0–1.0 (-1 if unavailable). */
        val cpuUsage: Double = 0.0,
        /** Agent JVM's own CPU load, 0.0–1.0 (-1 if unavailable). */
        val processCpuLoad: Double = -1.0,
        /** Total system memory in use, in MB (not just the agent JVM heap). */
        val memoryUsedMb: Long = 0,
        val memoryTotalMb: Long = 0,
        val services: List<ServiceHeartbeat> = emptyList()
    ) : ClusterMessage()

    @Serializable @SerialName("SERVICE_STATE_CHANGED")
    data class ServiceStateChanged(
        val serviceName: String,
        val groupName: String,
        val state: String,
        val port: Int = 0,
        val pid: Long = 0
    ) : ClusterMessage()

    @Serializable @SerialName("SERVICE_STDOUT")
    data class ServiceStdout(
        val serviceName: String,
        val line: String
    ) : ClusterMessage()

    @Serializable @SerialName("SERVICE_PLAYER_COUNT")
    data class ServicePlayerCount(
        val serviceName: String,
        val playerCount: Int
    ) : ClusterMessage()

    @Serializable @SerialName("COMMAND_RESULT")
    data class CommandResult(
        val serviceName: String,
        val success: Boolean,
        val error: String = ""
    ) : ClusterMessage()

    @Serializable @SerialName("TEMPLATE_REQUEST")
    data class TemplateRequest(
        val templateName: String
    ) : ClusterMessage()

    @Serializable @SerialName("LOG_MESSAGE")
    data class LogMessage(
        val level: String,
        val message: String
    ) : ClusterMessage()

    // ── Remote File Management Responses ───────────────

    @Serializable @SerialName("FILE_LIST_RESPONSE")
    data class FileListResponse(
        val requestId: String,
        val entries: List<RemoteFileEntry> = emptyList(),
        val error: String = ""
    ) : ClusterMessage()

    @Serializable @SerialName("FILE_READ_RESPONSE")
    data class FileReadResponse(
        val requestId: String,
        val content: String = "",
        val size: Long = 0,
        val error: String = ""
    ) : ClusterMessage()

    @Serializable @SerialName("FILE_WRITE_RESPONSE")
    data class FileWriteResponse(
        val requestId: String,
        val success: Boolean,
        val error: String = ""
    ) : ClusterMessage()

    @Serializable @SerialName("FILE_DELETE_RESPONSE")
    data class FileDeleteResponse(
        val requestId: String,
        val success: Boolean,
        val error: String = ""
    ) : ClusterMessage()
}

@Serializable
data class RemoteFileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: String = ""
)

@Serializable
data class ServiceHeartbeat(
    val serviceName: String,
    val groupName: String,
    val state: String,
    val port: Int,
    val pid: Long,
    val playerCount: Int,
    val customState: String? = null,
    /** Resident set size of the service process in MB, read from /proc on the agent. */
    val memoryUsedMb: Long = 0
)
