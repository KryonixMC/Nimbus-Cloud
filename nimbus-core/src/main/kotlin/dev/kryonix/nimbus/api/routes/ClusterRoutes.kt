package dev.kryonix.nimbus.api.routes

import dev.kryonix.nimbus.api.*
import dev.kryonix.nimbus.cluster.NodeManager
import dev.kryonix.nimbus.loadbalancer.TcpLoadBalancer
import dev.kryonix.nimbus.service.ServiceRegistry
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.clusterRoutes(
    nodeManager: NodeManager?,
    loadBalancer: TcpLoadBalancer?,
    registry: ServiceRegistry
) {
    // GET /api/nodes — List all cluster nodes
    get("/api/nodes") {
        if (nodeManager == null) {
            return@get call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Cluster mode not enabled"))
        }
        val nodes = nodeManager.getAllNodes().map { node ->
            val nodeServices = registry.getAll().filter { it.nodeId == node.nodeId }
            NodeResponse(
                nodeId = node.nodeId,
                host = node.host,
                maxMemory = node.maxMemory,
                maxServices = node.maxServices,
                currentServices = node.currentServices,
                cpuUsage = node.cpuUsage,
                memoryUsedMb = node.memoryUsedMb,
                memoryTotalMb = node.memoryTotalMb,
                isConnected = node.isConnected,
                agentVersion = node.agentVersion,
                os = node.os,
                arch = node.arch,
                services = nodeServices.map { it.name }
            )
        }
        call.respond(NodeListResponse(nodes, nodes.size))
    }

    // GET /api/loadbalancer — Load balancer status
    get("/api/loadbalancer") {
        if (loadBalancer == null) {
            return@get call.respond(HttpStatusCode.NotFound, ApiMessage(false, "Load balancer not enabled"))
        }
        val healthStates = loadBalancer.healthManager.getAll()
        val backends = healthStates.map { state ->
            val service = registry.getAll().firstOrNull { it.host == state.host && it.port == state.port }
            LbBackendResponse(
                name = service?.name ?: "${state.host}:${state.port}",
                host = state.host,
                port = state.port,
                playerCount = service?.playerCount ?: 0,
                health = state.status.get().name,
                connectionCount = state.activeConnections.get()
            )
        }
        call.respond(LoadBalancerResponse(
            enabled = true,
            bind = loadBalancer.config.bind,
            port = loadBalancer.config.port,
            strategy = loadBalancer.config.strategy,
            proxyProtocol = loadBalancer.config.proxyProtocol,
            totalConnections = loadBalancer.totalConnections,
            activeConnections = loadBalancer.activeConnections,
            rejectedConnections = loadBalancer.rejectedConnections,
            backends = backends
        ))
    }
}
