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
    val punishments: PunishmentsConfig = PunishmentsConfig(),
    val resourcepacks: ResourcePacksConfig = ResourcePacksConfig(),
    val bedrock: BedrockConfig = BedrockConfig(),
    val cluster: ClusterConfig = ClusterConfig(),
    val audit: AuditConfig = AuditConfig(),
    val metrics: MetricsConfig = MetricsConfig(),
    val curseforge: CurseForgeConfig = CurseForgeConfig(),
    val dashboard: DashboardConfig = DashboardConfig()
)

/**
 * Dashboard-facing config — currently just the public URL used by the
 * auth module to construct magic-link URLs. Separate from the dashboard
 * app itself (which has its own config inside `dashboard/`).
 */
@Serializable
data class DashboardConfig(
    @SerialName("public_url")
    val publicUrl: String = "https://dashboard.nimbuspowered.org"
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
    val heartbeatInterval: Long = 5000,
    @SerialName("scaling_tick_interval")
    val scalingTickInterval: Long = 10,
    @SerialName("service_stale_timeout")
    val serviceStaleTimeout: Long = 300  // seconds, 0 = disabled
)

@Serializable
data class ConsoleConfig(
    val colored: Boolean = true,
    @SerialName("log_events")
    val logEvents: Boolean = true,
    @SerialName("history_file")
    val historyFile: String = ".nimbus_history",
    /**
     * Looks up approximate geo-location for Remote-CLI session IPs via the
     * public ip-api.com endpoint. Off by default in v0.11.1 — leaks the
     * connecting IP to a third party and the operator gains little from
     * "Berlin, Germany" appearing in their session log. Flip on if you want it.
     */
    @SerialName("geo_lookup_enabled")
    val geoLookupEnabled: Boolean = false
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
    val allowedOrigins: List<String> = listOf("https://dashboard.nimbuspowered.org", "http://localhost:3000"),
    @SerialName("trust_forwarded_for")
    val trustForwardedFor: Boolean = false
)

@Serializable
data class DatabaseConfig(
    val type: String = "sqlite",  // sqlite, mysql, postgresql
    val host: String = "localhost",
    val port: Int = 3306,
    val name: String = "nimbus",
    val username: String = "",
    val password: String = "",
    @SerialName("pool_size")
    val poolSize: Int = 10
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
data class PunishmentsConfig(
    @SerialName("deploy_plugin")
    val deployPlugin: Boolean = true,
    /** Ban/mute check cache TTL on proxy login (seconds). Higher = less DB load, slower revoke propagation. */
    @SerialName("check_cache_ttl")
    val checkCacheTtl: Int = 5,
    /** How often expired tempbans/tempmutes are deactivated (seconds). */
    @SerialName("expiry_check_interval")
    val expiryCheckInterval: Int = 30
)

@Serializable
data class ResourcePacksConfig(
    @SerialName("deploy_plugin")
    val deployPlugin: Boolean = true,
    /** Maximum size for uploaded resource pack files (bytes). Default 250 MB. */
    @SerialName("max_upload_bytes")
    val maxUploadBytes: Long = 250L * 1024 * 1024,
    /** Public base URL used by clients to fetch locally hosted packs. Empty = derive from API bind. */
    @SerialName("public_base_url")
    val publicBaseUrl: String = ""
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
data class MetricsConfig(
    @SerialName("retention_days")
    val retentionDays: Long = 30
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
