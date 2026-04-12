package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.api.*
import dev.nimbuspowered.nimbus.api.ApiErrors
import dev.nimbuspowered.nimbus.api.apiError
import dev.nimbuspowered.nimbus.stress.StressTestManager
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Status read — not rate-limited, callable from polling dashboards. */
fun Route.stressStatusRoute(stressTestManager: StressTestManager) {
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
}

/** Mutation routes — rate-limited (5/min) to prevent runaway start/stop/ramp spam. */
fun Route.stressRoutes(stressTestManager: StressTestManager) {

    // POST /api/stress/start — Start a stress test
    post("/api/stress/start") {
        val req = call.receive<StressStartRequest>()
        if (req.players <= 0) {
            call.respond(apiError("Player count must be positive", ApiErrors.INVALID_INPUT))
            return@post
        }
        val started = stressTestManager.start(req.group, req.players, req.rampSeconds * 1000)
        if (started) {
            call.respond(ApiMessage(true, "Stress test started: ${req.players} players on ${req.group ?: "all groups"}"))
        } else {
            call.respond(apiError("A stress test is already running or the target group is a proxy", ApiErrors.STRESS_ALREADY_RUNNING))
        }
    }

    // POST /api/stress/stop — Stop the stress test
    post("/api/stress/stop") {
        if (!stressTestManager.isActive()) {
            call.respond(apiError("No stress test is running", ApiErrors.STRESS_NOT_RUNNING))
            return@post
        }
        stressTestManager.stop()
        call.respond(ApiMessage(true, "Stress test stopped"))
    }

    // POST /api/stress/ramp — Adjust target mid-test
    post("/api/stress/ramp") {
        val req = call.receive<StressRampRequest>()
        if (req.players < 0) {
            call.respond(apiError("Player count cannot be negative", ApiErrors.INVALID_INPUT))
            return@post
        }
        val ramped = stressTestManager.ramp(req.players, req.durationSeconds * 1000)
        if (ramped) {
            call.respond(ApiMessage(true, "Ramping to ${req.players} players over ${req.durationSeconds}s"))
        } else {
            call.respond(apiError("No stress test is running", ApiErrors.STRESS_NOT_RUNNING))
        }
    }
}
