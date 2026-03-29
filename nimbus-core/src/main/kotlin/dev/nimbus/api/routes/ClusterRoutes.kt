package dev.nimbus.api.routes

import dev.nimbus.api.*
import dev.nimbus.cluster.NodeManager
import dev.nimbus.loadbalancer.TcpLoadBalancer
import dev.nimbus.service.ServiceRegistry
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
        // Build response with current backends
        call.respond(ApiMessage(true, "Load balancer status"))
    }
}
