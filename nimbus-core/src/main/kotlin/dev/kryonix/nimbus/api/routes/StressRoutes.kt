package dev.kryonix.nimbus.api.routes

import dev.kryonix.nimbus.api.*
import dev.kryonix.nimbus.stress.StressTestManager
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.stressRoutes(stressTestManager: StressTestManager) {

    // GET /api/stress — Current stress test status
    get("/api/stress") {
        val status = stressTestManager.getStatus()
        if (status == null) {
            call.respond(StressStatusResponse(active = false))
        } else {
            call.respond(StressStatusResponse(
                active = true,
                group = status.profile.groupName,
                currentPlayers = status.profile.currentPlayers,
                targetPlayers = status.profile.targetPlayers,
                totalCapacity = status.totalCapacity,
                overflow = status.profile.overflow,
                elapsedSeconds = status.elapsedMs / 1000,
                services = status.perService,
                proxyServices = status.proxyServices
            ))
        }
    }

    // POST /api/stress/start — Start a stress test
    post("/api/stress/start") {
        val req = call.receive<StressStartRequest>()
        if (req.players <= 0) {
            call.respond(ApiMessage(false, "Player count must be positive"))
            return@post
        }
        val started = stressTestManager.start(req.group, req.players, req.rampSeconds * 1000)
        if (started) {
            call.respond(ApiMessage(true, "Stress test started: ${req.players} players on ${req.group ?: "all groups"}"))
        } else {
            call.respond(ApiMessage(false, "A stress test is already running or the target group is a proxy"))
        }
    }

    // POST /api/stress/stop — Stop the stress test
    post("/api/stress/stop") {
        if (!stressTestManager.isActive()) {
            call.respond(ApiMessage(false, "No stress test is running"))
            return@post
        }
        stressTestManager.stop()
        call.respond(ApiMessage(true, "Stress test stopped"))
    }

    // POST /api/stress/ramp — Adjust target mid-test
    post("/api/stress/ramp") {
        val req = call.receive<StressRampRequest>()
        if (req.players < 0) {
            call.respond(ApiMessage(false, "Player count cannot be negative"))
            return@post
        }
        val ramped = stressTestManager.ramp(req.players, req.durationSeconds * 1000)
        if (ramped) {
            call.respond(ApiMessage(true, "Ramping to ${req.players} players over ${req.durationSeconds}s"))
        } else {
            call.respond(ApiMessage(false, "No stress test is running"))
        }
    }
}
