package dev.nimbuspowered.nimbus.module.scaling.routes

import dev.nimbuspowered.nimbus.api.requirePermission
import dev.nimbuspowered.nimbus.module.scaling.SmartScalingConfigManager
import dev.nimbuspowered.nimbus.module.scaling.SmartScalingManager
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

fun Route.scalingRoutes(manager: SmartScalingManager, configManager: SmartScalingConfigManager) {

    route("/api/scaling") {

        // GET /api/scaling/status — Overview of active schedules + recent decisions
        get("status") {
            val configs = configManager.getAllConfigs()
            val groups = configs.map { (groupName, config) ->
                val activeRule = manager.getActiveRule(groupName)
                GroupStatusResponse(
                    group = groupName,
                    enabled = config.schedule.enabled,
                    ruleCount = config.schedule.rules.size,
                    timezone = config.schedule.timezone.id,
                    activeRule = activeRule?.let { (rule, isWarmup) ->
                        ActiveRuleResponse(
                            name = rule.name,
                            minInstances = rule.minInstances,
                            maxInstances = rule.maxInstances,
                            isWarmup = isWarmup
                        )
                    }
                )
            }
            val decisions = manager.getRecentDecisions(10).map { it.toResponse() }
            call.respond(ScalingStatusResponse(groups, decisions))
        }

        // GET /api/scaling/schedules — All schedule rules
        get("schedules") {
            val schedules = configManager.getAllConfigs().map { (groupName, config) ->
                ScheduleResponse(
                    group = groupName,
                    enabled = config.schedule.enabled,
                    timezone = config.schedule.timezone.id,
                    warmup = WarmupResponse(config.schedule.warmup.enabled, config.schedule.warmup.leadTimeMinutes),
                    rules = config.schedule.rules.map { rule ->
                        RuleResponse(
                            name = rule.name,
                            days = rule.days.map { it.name },
                            from = rule.from.toString(),
                            to = rule.to.toString(),
                            minInstances = rule.minInstances,
                            maxInstances = rule.maxInstances
                        )
                    }
                )
            }
            call.respond(ScheduleListResponse(schedules))
        }

        // GET /api/scaling/schedules/{group} — Rules for a specific group
        get("schedules/{group}") {
            val groupName = call.parameters["group"]!!
            val config = configManager.getConfig(groupName)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "No scaling config for '$groupName'"))

            call.respond(
                ScheduleResponse(
                    group = groupName,
                    enabled = config.schedule.enabled,
                    timezone = config.schedule.timezone.id,
                    warmup = WarmupResponse(config.schedule.warmup.enabled, config.schedule.warmup.leadTimeMinutes),
                    rules = config.schedule.rules.map { rule ->
                        RuleResponse(
                            name = rule.name,
                            days = rule.days.map { it.name },
                            from = rule.from.toString(),
                            to = rule.to.toString(),
                            minInstances = rule.minInstances,
                            maxInstances = rule.maxInstances
                        )
                    }
                )
            )
        }

        // GET /api/scaling/history/{group}?hours=24 — Player count history
        get("history/{group}") {
            val groupName = call.parameters["group"]!!
            val hours = call.request.queryParameters["hours"]?.toIntOrNull() ?: 24
            val history = manager.getHistory(groupName, hours)

            call.respond(
                HistoryResponse(
                    group = groupName,
                    hours = hours,
                    snapshots = history.map { s ->
                        SnapshotResponse(s.timestamp, s.playerCount, s.serviceCount)
                    }
                )
            )
        }

        // GET /api/scaling/predictions/{group} — Predictions for next 6 hours
        get("predictions/{group}") {
            val groupName = call.parameters["group"]!!
            val predictions = manager.getPredictions(groupName, 6)

            call.respond(
                PredictionsResponse(
                    group = groupName,
                    predictions = predictions.map { p ->
                        PredictionResponse(
                            hour = p.hour,
                            dayOfWeek = p.dayOfWeek.name,
                            predictedPlayers = p.predictedPlayers,
                            dataPoints = p.dataPoints
                        )
                    }
                )
            )
        }

        // GET /api/scaling/decisions — Recent scaling decisions
        get("decisions") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val decisions = manager.getRecentDecisions(limit)
            call.respond(DecisionListResponse(decisions.map { it.toResponse() }))
        }
    }
}

// ── Response DTOs ───────────────────────────────────────

@Serializable
data class ScalingStatusResponse(
    val groups: List<GroupStatusResponse>,
    val recentDecisions: List<DecisionResponse>
)

@Serializable
data class GroupStatusResponse(
    val group: String,
    val enabled: Boolean,
    val ruleCount: Int,
    val timezone: String,
    val activeRule: ActiveRuleResponse?
)

@Serializable
data class ActiveRuleResponse(
    val name: String,
    val minInstances: Int,
    val maxInstances: Int?,
    val isWarmup: Boolean
)

@Serializable
data class ScheduleListResponse(val schedules: List<ScheduleResponse>)

@Serializable
data class ScheduleResponse(
    val group: String,
    val enabled: Boolean,
    val timezone: String,
    val warmup: WarmupResponse,
    val rules: List<RuleResponse>
)

@Serializable
data class WarmupResponse(val enabled: Boolean, val leadTimeMinutes: Int)

@Serializable
data class RuleResponse(
    val name: String,
    val days: List<String>,
    val from: String,
    val to: String,
    val minInstances: Int,
    val maxInstances: Int?
)

@Serializable
data class HistoryResponse(
    val group: String,
    val hours: Int,
    val snapshots: List<SnapshotResponse>
)

@Serializable
data class SnapshotResponse(val timestamp: String, val playerCount: Int, val serviceCount: Int)

@Serializable
data class PredictionsResponse(
    val group: String,
    val predictions: List<PredictionResponse>
)

@Serializable
data class PredictionResponse(
    val hour: Int,
    val dayOfWeek: String,
    val predictedPlayers: Int,
    val dataPoints: Int
)

@Serializable
data class DecisionListResponse(val decisions: List<DecisionResponse>)

@Serializable
data class DecisionResponse(
    val timestamp: String,
    val group: String,
    val action: String,
    val reason: String,
    val source: String,
    val servicesStarted: Int
)

private fun SmartScalingManager.DecisionEntry.toResponse() = DecisionResponse(
    timestamp = timestamp,
    group = groupName,
    action = action,
    reason = reason,
    source = source,
    servicesStarted = servicesStarted
)
