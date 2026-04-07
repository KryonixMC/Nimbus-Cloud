package dev.nimbuspowered.nimbus.api.auth

/**
 * Permission scopes for JWT-based API authentication.
 * The [ADMIN] scope is a superscope that grants access to all endpoints.
 */
object ApiScope {
    const val SERVICES_READ = "services:read"
    const val SERVICES_WRITE = "services:write"
    const val GROUPS_READ = "groups:read"
    const val GROUPS_WRITE = "groups:write"
    const val METRICS_READ = "metrics:read"
    const val CLUSTER_READ = "cluster:read"
    const val CLUSTER_WRITE = "cluster:write"
    const val FILES_READ = "files:read"
    const val FILES_WRITE = "files:write"
    const val CONFIG_READ = "config:read"
    const val CONFIG_WRITE = "config:write"
    const val AUDIT_READ = "audit:read"
    const val ADMIN = "admin"

    val ALL = setOf(
        SERVICES_READ, SERVICES_WRITE,
        GROUPS_READ, GROUPS_WRITE,
        METRICS_READ,
        CLUSTER_READ, CLUSTER_WRITE,
        FILES_READ, FILES_WRITE,
        CONFIG_READ, CONFIG_WRITE,
        AUDIT_READ,
        ADMIN
    )

    /** Scopes granted to game server service tokens (limited access). */
    val SERVICE_SCOPES = setOf(SERVICES_READ, SERVICES_WRITE, GROUPS_READ, METRICS_READ)

    /** Scopes granted to Velocity proxy tokens (full admin for bridge integration). */
    val PROXY_SCOPES = ALL
}
