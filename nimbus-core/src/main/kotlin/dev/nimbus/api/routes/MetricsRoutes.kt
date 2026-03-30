package dev.nimbus.api.routes

import dev.nimbus.api.NimbusApi
import dev.nimbus.cluster.NodeConnection
import dev.nimbus.cluster.NodeManager
import dev.nimbus.group.GroupManager
import dev.nimbus.loadbalancer.TcpLoadBalancer
import dev.nimbus.proxy.ProxySyncManager
import dev.nimbus.service.ServiceRegistry
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Duration
import java.time.Instant

/**
 * Prometheus text exposition format endpoint.
 * Public (no auth) — designed for scraping by Prometheus.
 */
fun Route.metricsRoutes(
    registry: ServiceRegistry,
    groupManager: GroupManager,
    nodeManager: NodeManager?,
    loadBalancer: TcpLoadBalancer?,
    proxySyncManager: ProxySyncManager,
    startedAt: Instant
) {
    get("/api/metrics") {
        val sb = StringBuilder()
        val services = registry.getAll()
        val groups = groupManager.getAllGroups()

        // ── Nimbus info ──
        sb.metric("nimbus_info", "gauge", "Nimbus instance information",
            1, "version" to NimbusApi.VERSION)
        sb.metric("nimbus_uptime_seconds", "gauge", "Seconds since Nimbus started",
            Duration.between(startedAt, Instant.now()).seconds)

        // ── Service counts ──
        sb.metric("nimbus_services_total", "gauge", "Total number of services",
            services.size)

        val byState = services.groupBy { it.state.name }
        sb.help("nimbus_services_by_state", "gauge", "Services by state")
        for ((state, list) in byState) {
            sb.value("nimbus_services_by_state", list.size, "state" to state)
        }

        val byGroup = services.groupBy { it.groupName }
        sb.help("nimbus_services_by_group", "gauge", "Services by group")
        for ((group, list) in byGroup) {
            sb.value("nimbus_services_by_group", list.size, "group" to group)
        }

        // ── Player counts ──
        val totalPlayers = services.sumOf { it.playerCount }
        sb.metric("nimbus_players_total", "gauge", "Total players across all services",
            totalPlayers)

        sb.help("nimbus_players_by_group", "gauge", "Players by group")
        for ((group, list) in byGroup) {
            sb.value("nimbus_players_by_group", list.sumOf { it.playerCount }, "group" to group)
        }

        sb.help("nimbus_players_by_service", "gauge", "Players per service")
        for (service in services) {
            sb.value("nimbus_players_by_service", service.playerCount,
                "service" to service.name, "group" to service.groupName)
        }

        // ── Group configuration ──
        sb.help("nimbus_group_min_instances", "gauge", "Minimum instances configured per group")
        sb.help("nimbus_group_max_instances", "gauge", "Maximum instances configured per group")
        sb.help("nimbus_group_max_players", "gauge", "Max players per instance per group")
        for (group in groups) {
            sb.value("nimbus_group_min_instances", group.minInstances, "group" to group.name)
            sb.value("nimbus_group_max_instances", group.maxInstances, "group" to group.name)
            sb.value("nimbus_group_max_players", group.config.group.resources.maxPlayers,
                "group" to group.name)
        }

        // ── Cluster nodes ──
        if (nodeManager != null) {
            val nodes = nodeManager.getAllNodes()
            sb.metric("nimbus_nodes_total", "gauge", "Total registered nodes",
                nodes.size)
            sb.metric("nimbus_nodes_online", "gauge", "Online nodes",
                nodeManager.getOnlineNodeCount())

            sb.help("nimbus_node_cpu_usage", "gauge", "Node CPU usage (0.0–1.0)")
            sb.help("nimbus_node_memory_used_bytes", "gauge", "Node memory used in bytes")
            sb.help("nimbus_node_memory_total_bytes", "gauge", "Node total memory in bytes")
            sb.help("nimbus_node_services", "gauge", "Services running on node")
            for (node in nodes) {
                val labels = arrayOf("node" to node.nodeId)
                sb.value("nimbus_node_cpu_usage", node.cpuUsage, *labels)
                sb.value("nimbus_node_memory_used_bytes", node.memoryUsedMb * 1024 * 1024, *labels)
                sb.value("nimbus_node_memory_total_bytes", node.memoryTotalMb * 1024 * 1024, *labels)
                sb.value("nimbus_node_services", node.currentServices, *labels)
            }
        }

        // ── Load balancer ──
        if (loadBalancer != null) {
            sb.metric("nimbus_loadbalancer_connections_total", "counter",
                "Total connections handled by load balancer", loadBalancer.totalConnections)
            sb.metric("nimbus_loadbalancer_connections_active", "gauge",
                "Active load balancer connections", loadBalancer.activeConnections)
        }

        // ── Maintenance ──
        sb.metric("nimbus_maintenance_enabled", "gauge",
            "Whether global maintenance mode is enabled (1=on, 0=off)",
            if (proxySyncManager.globalMaintenanceEnabled) 1 else 0)

        call.respondText(sb.toString(), ContentType.parse("text/plain; version=0.0.4; charset=utf-8"))
    }
}

// ── Prometheus format helpers ──

private fun StringBuilder.metric(name: String, type: String, help: String, value: Number, vararg labels: Pair<String, String>) {
    help(name, type, help)
    value(name, value, *labels)
}

private fun StringBuilder.help(name: String, type: String, help: String) {
    append("# HELP ").append(name).append(' ').append(help).append('\n')
    append("# TYPE ").append(name).append(' ').append(type).append('\n')
}

private fun StringBuilder.value(name: String, value: Number, vararg labels: Pair<String, String>) {
    append(name)
    if (labels.isNotEmpty()) {
        append('{')
        labels.forEachIndexed { i, (k, v) ->
            if (i > 0) append(',')
            append(k).append("=\"").append(prometheusEscape(v)).append('"')
        }
        append('}')
    }
    append(' ').append(value).append('\n')
}

private fun prometheusEscape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
