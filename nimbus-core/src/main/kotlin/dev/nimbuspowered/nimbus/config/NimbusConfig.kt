package dev.nimbuspowered.nimbus.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NimbusConfig(
    val network: NetworkConfig = NetworkConfig(),
    val controller: ControllerConfig = ControllerConfig(),
    val console: ConsoleConfig = ConsoleConfig(),
    val paths: PathsConfig = PathsConfig(),
    val api: ApiConfig = ApiConfig(),
    val java: JavaConfig = JavaConfig(),
    val database: DatabaseConfig = DatabaseConfig(),
    val loadbalancer: LoadBalancerConfig = LoadBalancerConfig(),
    @Suppress("unused") // backward compat: old configs may have [permissions] section
    val permissions: PermissionsConfig = PermissionsConfig(),
    val bedrock: BedrockConfig = BedrockConfig(),
    val cluster: ClusterConfig = ClusterConfig(),
    val audit: AuditConfig = AuditConfig(),
    val curseforge: CurseForgeConfig = CurseForgeConfig()
)

@Serializable
data class NetworkConfig(
    val name: String = "Nimbus",
    val bind: String = "0.0.0.0"
)

@Serializable
data class ControllerConfig(
    @SerialName("max_memory")
    val maxMemory: String = "10G",
    @SerialName("max_services")
    val maxServices: Int = 20,
    @SerialName("heartbeat_interval")
    val heartbeatInterval: Long = 5000
)

@Serializable
data class ConsoleConfig(
    val colored: Boolean = true,
    @SerialName("log_events")
    val logEvents: Boolean = true,
    @SerialName("history_file")
    val historyFile: String = ".nimbus_history"
)

@Serializable
data class PathsConfig(
    val templates: String = "templates",
    val services: String = "services",
    val dedicated: String = "dedicated",
    val logs: String = "logs"
)

@Serializable
data class ApiConfig(
    val enabled: Boolean = true,
    val bind: String = "127.0.0.1",
    val port: Int = 8080,
    val token: String = "",
    @SerialName("jwt_enabled")
    val jwtEnabled: Boolean = false,
    @SerialName("allowed_origins")
    val allowedOrigins: List<String> = listOf("dashboard.nimbuspowered.org")
)

@Serializable
data class DatabaseConfig(
    val type: String = "sqlite",  // sqlite, mysql, postgresql
    val host: String = "localhost",
    val port: Int = 3306,
    val name: String = "nimbus",
    val username: String = "",
    val password: String = ""
)

@Serializable
data class JavaConfig(
    @SerialName("java_16")
    val java16: String = "",
    @SerialName("java_17")
    val java17: String = "",
    @SerialName("java_21")
    val java21: String = ""
) {
    fun toMap(): Map<Int, String> = buildMap {
        if (java16.isNotEmpty()) put(16, java16)
        if (java17.isNotEmpty()) put(17, java17)
        if (java21.isNotEmpty()) put(21, java21)
    }
}

@Serializable
data class LoadBalancerConfig(
    val enabled: Boolean = false,
    val bind: String = "0.0.0.0",
    val port: Int = 25565,
    val strategy: String = "least-players",
    @SerialName("proxy_protocol")
    val proxyProtocol: Boolean = false,
    @SerialName("connection_timeout")
    val connectionTimeout: Int = 5000,
    @SerialName("buffer_size")
    val bufferSize: Int = 16384,
    @SerialName("max_connections")
    val maxConnections: Int = 10000,
    @SerialName("idle_timeout")
    val idleTimeout: Int = 30000,
    @SerialName("health_check_interval")
    val healthCheckInterval: Int = 10,
    @SerialName("health_check_timeout")
    val healthCheckTimeout: Int = 3000,
    @SerialName("unhealthy_threshold")
    val unhealthyThreshold: Int = 3,
    @SerialName("healthy_threshold")
    val healthyThreshold: Int = 2
)

@Serializable
data class PermissionsConfig(
    @SerialName("deploy_plugin")
    val deployPlugin: Boolean = true
)

@Serializable
data class BedrockConfig(
    val enabled: Boolean = false,
    @SerialName("base_port")
    val basePort: Int = 19132
)

@Serializable
data class AuditConfig(
    val enabled: Boolean = true,
    @SerialName("retention_days")
    val retentionDays: Long = 90
)

@Serializable
data class CurseForgeConfig(
    @SerialName("api_key")
    val apiKey: String = ""
)

@Serializable
data class ClusterConfig(
    val enabled: Boolean = false,
    val token: String = "",
    @SerialName("agent_port")
    val agentPort: Int = 8443,
    @SerialName("bind")
    val bind: String = "0.0.0.0",
    @SerialName("heartbeat_interval")
    val heartbeatInterval: Long = 5000,
    @SerialName("node_timeout")
    val nodeTimeout: Long = 15000,
    @SerialName("placement_strategy")
    val placementStrategy: String = "least-services",
    @SerialName("tls_enabled")
    val tlsEnabled: Boolean = true,
    @SerialName("keystore_path")
    val keystorePath: String = "",
    @SerialName("keystore_password")
    val keystorePassword: String = "",
    @SerialName("extra_sans")
    val extraSans: List<String> = emptyList(),
    @SerialName("public_host")
    val publicHost: String = "",
    /**
     * Maximum total size in bytes that canonical state may occupy under
     * `services/state/` + any dedicated service roots. 0 = unlimited.
     * Pushes that would exceed this limit are rejected with HTTP 507.
     */
    @SerialName("sync_disk_quota_bytes")
    val syncDiskQuotaBytes: Long = 0,
    @SerialName("reconciliation_delay")
    val reconciliationDelay: Long = 10000
)
