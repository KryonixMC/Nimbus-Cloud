package dev.kryonix.nimbus.module.scaling

import dev.kryonix.nimbus.database.DatabaseManager
import dev.kryonix.nimbus.event.EventBus
import dev.kryonix.nimbus.event.NimbusEvent
import dev.kryonix.nimbus.group.GroupManager
import dev.kryonix.nimbus.service.ServiceManager
import dev.kryonix.nimbus.service.ServiceRegistry
import dev.kryonix.nimbus.service.ServiceState
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class SmartScalingManager(
    private val db: DatabaseManager,
    private val configManager: SmartScalingConfigManager,
    private val groupManager: GroupManager,
    private val registry: ServiceRegistry,
    private val eventBus: EventBus
) {

    private val logger = LoggerFactory.getLogger(SmartScalingManager::class.java)

    /** ServiceManager is registered after module init — resolved lazily. */
    var serviceManager: ServiceManager? = null

    /** Cooldown: track last warmup action per group to avoid spamming. */
    private val lastWarmupAction = mutableMapOf<String, Instant>()

    companion object {
        private const val WARMUP_COOLDOWN_SECONDS = 60L
        private const val SNAPSHOT_RETENTION_DAYS = 90L
        private const val DECISION_RETENTION_DAYS = 90L
    }

    // ── Snapshot Collection ─────────────────────────────

    /** Record current player/service counts for all dynamic groups. */
    suspend fun collectSnapshots() {
        val now = Instant.now().toString()

        for (group in groupManager.getAllGroups()) {
            if (group.isStatic) continue

            val services = registry.getByGroup(group.name)
            val readyServices = services.filter { it.state == ServiceState.READY }
            val totalPlayers = readyServices.sumOf { it.playerCount }

            db.query {
                ScalingSnapshots.insert {
                    it[timestamp] = now
                    it[groupName] = group.name
                    it[playerCount] = totalPlayers
                    it[serviceCount] = readyServices.size
                }
            }
        }
    }

    /** Prune old snapshots and decisions beyond retention period. */
    suspend fun pruneHistory() {
        val snapshotCutoff = Instant.now().minus(SNAPSHOT_RETENTION_DAYS, ChronoUnit.DAYS).toString()
        val decisionCutoff = Instant.now().minus(DECISION_RETENTION_DAYS, ChronoUnit.DAYS).toString()

        db.query {
            ScalingSnapshots.deleteWhere { timestamp less snapshotCutoff }
            ScalingDecisions.deleteWhere { timestamp less decisionCutoff }
        }
    }

    // ── Schedule Evaluation ─────────────────────────────

    /**
     * Evaluate all schedule rules and start servers if needed.
     * Called every 30s from the module's background loop.
     */
    suspend fun evaluateSchedules() {
        val sm = serviceManager ?: return

        for ((groupName, config) in configManager.getAllConfigs()) {
            if (!config.schedule.enabled) continue

            val group = groupManager.getGroup(groupName) ?: continue
            if (group.isStatic) continue

            val now = ZonedDateTime.now(config.schedule.timezone)
            val activeRule = findActiveRule(config.schedule.rules, now)
            val warmupRule = if (config.schedule.warmup.enabled) {
                findWarmupRule(config.schedule.rules, now, config.schedule.warmup.leadTimeMinutes)
            } else null

            // Use the more demanding rule (higher min_instances)
            val effectiveRule = listOfNotNull(activeRule, warmupRule)
                .maxByOrNull { it.minInstances }
                ?: continue

            val services = registry.getByGroup(groupName)
            val readyOrStarting = services.count {
                it.state in listOf(ServiceState.READY, ServiceState.PREPARING, ServiceState.STARTING)
            }

            // Respect group's max_instances as hard cap
            val groupMax = group.config.group.scaling.maxInstances
            val effectiveMax = effectiveRule.maxInstances?.coerceAtMost(groupMax) ?: groupMax

            if (readyOrStarting >= effectiveRule.minInstances) continue
            if (readyOrStarting >= effectiveMax) continue

            // Cooldown check
            val lastAction = lastWarmupAction[groupName]
            if (lastAction != null && Instant.now().epochSecond - lastAction.epochSecond < WARMUP_COOLDOWN_SECONDS) continue

            val toStart = (effectiveRule.minInstances - readyOrStarting).coerceAtMost(effectiveMax - readyOrStarting)
            if (toStart <= 0) continue

            val isWarmup = warmupRule != null && (activeRule == null || warmupRule.minInstances > activeRule.minInstances)
            val source = if (isWarmup) "warmup" else "schedule"
            val reason = if (isWarmup) {
                "pre-warming for schedule \"${effectiveRule.name}\" (need ${effectiveRule.minInstances}, have $readyOrStarting)"
            } else {
                "schedule \"${effectiveRule.name}\" active (min=${effectiveRule.minInstances}, have $readyOrStarting)"
            }

            logger.info("Smart scaling {}: {} — starting {} service(s) for group '{}'", source, reason, toStart, groupName)

            var started = 0
            repeat(toStart) {
                val service = sm.startService(groupName)
                if (service != null) started++
            }

            if (started > 0) {
                lastWarmupAction[groupName] = Instant.now()

                val eventType = if (isWarmup) "SMART_WARMUP" else "SMART_SCHEDULE"
                eventBus.emit(
                    NimbusEvent.ModuleEvent(
                        moduleId = "scaling",
                        type = eventType,
                        data = mapOf(
                            "group" to groupName,
                            "rule" to effectiveRule.name,
                            "started" to started.toString(),
                            "min" to effectiveRule.minInstances.toString()
                        )
                    )
                )

                // Log decision to DB
                db.query {
                    ScalingDecisions.insert {
                        it[timestamp] = Instant.now().toString()
                        it[ScalingDecisions.groupName] = groupName
                        it[action] = eventType.lowercase()
                        it[ScalingDecisions.reason] = reason
                        it[ScalingDecisions.decisionSource] = source
                        it[servicesStarted] = started
                    }
                }
            }
        }
    }

    // ── Predictive Warmup ───────────────────────────────

    /**
     * Evaluate predictive warmup based on historical data.
     * Looks at average player counts for the same hour/weekday over the past 7 days.
     */
    suspend fun evaluatePredictions() {
        val sm = serviceManager ?: return

        for (group in groupManager.getAllGroups()) {
            if (group.isStatic) continue

            val config = configManager.getConfig(group.name)
            if (config != null && !config.schedule.warmup.enabled) continue

            val leadMinutes = config?.schedule?.warmup?.leadTimeMinutes ?: 10
            val timezone = config?.schedule?.timezone ?: ZoneId.of("Europe/Berlin")

            val prediction = predictPlayerCount(group.name, leadMinutes, timezone) ?: continue
            if (prediction.predictedPlayers <= 0) continue

            val playersPerInstance = group.config.group.scaling.playersPerInstance
            val neededInstances = ((prediction.predictedPlayers.toDouble() / playersPerInstance) /
                    group.config.group.scaling.scaleThreshold).toInt().coerceAtLeast(1)

            val services = registry.getByGroup(group.name)
            val readyOrStarting = services.count {
                it.state in listOf(ServiceState.READY, ServiceState.PREPARING, ServiceState.STARTING)
            }

            val groupMax = group.config.group.scaling.maxInstances
            if (readyOrStarting >= neededInstances || readyOrStarting >= groupMax) continue

            // Cooldown check
            val lastAction = lastWarmupAction[group.name]
            if (lastAction != null && Instant.now().epochSecond - lastAction.epochSecond < WARMUP_COOLDOWN_SECONDS) continue

            val toStart = (neededInstances - readyOrStarting).coerceAtMost(groupMax - readyOrStarting)
            if (toStart <= 0) continue

            val reason = "predicted ${prediction.predictedPlayers} players in ${leadMinutes}min " +
                    "(avg from ${prediction.dataPoints} samples, need $neededInstances instances, have $readyOrStarting)"

            logger.info("Smart scaling prediction: {} — starting {} service(s) for group '{}'", reason, toStart, group.name)

            var started = 0
            repeat(toStart) {
                val service = sm.startService(group.name)
                if (service != null) started++
            }

            if (started > 0) {
                lastWarmupAction[group.name] = Instant.now()

                eventBus.emit(
                    NimbusEvent.ModuleEvent(
                        moduleId = "scaling",
                        type = "SMART_PREDICTION",
                        data = mapOf(
                            "group" to group.name,
                            "predicted" to prediction.predictedPlayers.toString(),
                            "started" to started.toString(),
                            "samples" to prediction.dataPoints.toString()
                        )
                    )
                )

                db.query {
                    ScalingDecisions.insert {
                        it[timestamp] = Instant.now().toString()
                        it[groupName] = group.name
                        it[action] = "smart_prediction"
                        it[ScalingDecisions.reason] = reason
                        it[decisionSource] = "prediction"
                        it[servicesStarted] = started
                    }
                }
            }
        }
    }

    // ── Prediction Algorithm ────────────────────────────

    data class Prediction(
        val predictedPlayers: Int,
        val dataPoints: Int
    )

    /**
     * Predict player count for [minutesAhead] in the future.
     * Uses average of same weekday + hour window from the past 7 days.
     */
    suspend fun predictPlayerCount(groupName: String, minutesAhead: Int, timezone: ZoneId): Prediction? {
        val targetTime = ZonedDateTime.now(timezone).plusMinutes(minutesAhead.toLong())
        val targetHour = targetTime.hour
        val targetDay = targetTime.dayOfWeek

        // Load snapshots from the past 7 days
        val cutoff = Instant.now().minus(7, ChronoUnit.DAYS).toString()

        val snapshots = db.query {
            ScalingSnapshots.selectAll()
                .where { (ScalingSnapshots.groupName eq groupName) and (ScalingSnapshots.timestamp greater cutoff) }
                .orderBy(ScalingSnapshots.timestamp, SortOrder.DESC)
                .toList()
        }.map { row ->
            val ts = Instant.parse(row[ScalingSnapshots.timestamp])
            val zdt = ts.atZone(timezone)
            Triple(zdt.dayOfWeek, zdt.hour, row[ScalingSnapshots.playerCount])
        }

        if (snapshots.isEmpty()) return null

        // Filter for matching weekday and hour
        val matching = snapshots.filter { (day, hour, _) ->
            day == targetDay && hour == targetHour
        }

        if (matching.isEmpty()) return null

        val avgPlayers = matching.map { it.third }.average().toInt()
        return Prediction(avgPlayers, matching.size)
    }

    // ── Query Methods (for commands/API) ────────────────

    /** Get active schedule rule for a group right now. */
    fun getActiveRule(groupName: String): Pair<ScheduleRule, Boolean>? {
        val config = configManager.getConfig(groupName) ?: return null
        if (!config.schedule.enabled) return null

        val now = ZonedDateTime.now(config.schedule.timezone)
        val active = findActiveRule(config.schedule.rules, now)
        if (active != null) return active to false

        if (config.schedule.warmup.enabled) {
            val warmup = findWarmupRule(config.schedule.rules, now, config.schedule.warmup.leadTimeMinutes)
            if (warmup != null) return warmup to true
        }

        return null
    }

    /** Get prediction for a group for the next N hours. */
    suspend fun getPredictions(groupName: String, hours: Int = 6): List<HourlyPrediction> {
        val config = configManager.getConfig(groupName)
        val timezone = config?.schedule?.timezone ?: ZoneId.of("Europe/Berlin")

        val predictions = mutableListOf<HourlyPrediction>()
        for (h in 0 until hours) {
            val prediction = predictPlayerCount(groupName, h * 60, timezone)
            val targetTime = ZonedDateTime.now(timezone).plusHours(h.toLong())
            predictions.add(
                HourlyPrediction(
                    hour = targetTime.hour,
                    dayOfWeek = targetTime.dayOfWeek,
                    predictedPlayers = prediction?.predictedPlayers ?: 0,
                    dataPoints = prediction?.dataPoints ?: 0
                )
            )
        }
        return predictions
    }

    data class HourlyPrediction(
        val hour: Int,
        val dayOfWeek: DayOfWeek,
        val predictedPlayers: Int,
        val dataPoints: Int
    )

    /** Get recent scaling decisions from the DB. */
    suspend fun getRecentDecisions(limit: Int = 50): List<DecisionEntry> {
        return db.query {
            ScalingDecisions.selectAll()
                .orderBy(ScalingDecisions.timestamp, SortOrder.DESC)
                .limit(limit)
                .toList()
        }.map { row ->
            DecisionEntry(
                timestamp = row[ScalingDecisions.timestamp],
                groupName = row[ScalingDecisions.groupName],
                action = row[ScalingDecisions.action],
                reason = row[ScalingDecisions.reason],
                source = row[ScalingDecisions.decisionSource],
                servicesStarted = row[ScalingDecisions.servicesStarted]
            )
        }
    }

    data class DecisionEntry(
        val timestamp: String,
        val groupName: String,
        val action: String,
        val reason: String,
        val source: String,
        val servicesStarted: Int
    )

    /** Get player history for a group. */
    suspend fun getHistory(groupName: String, hours: Int = 24): List<SnapshotEntry> {
        val cutoff = Instant.now().minus(hours.toLong(), ChronoUnit.HOURS).toString()

        return db.query {
            ScalingSnapshots.selectAll()
                .where { (ScalingSnapshots.groupName eq groupName) and (ScalingSnapshots.timestamp greater cutoff) }
                .orderBy(ScalingSnapshots.timestamp, SortOrder.ASC)
                .toList()
        }.map { row ->
            SnapshotEntry(
                timestamp = row[ScalingSnapshots.timestamp],
                playerCount = row[ScalingSnapshots.playerCount],
                serviceCount = row[ScalingSnapshots.serviceCount]
            )
        }
    }

    data class SnapshotEntry(
        val timestamp: String,
        val playerCount: Int,
        val serviceCount: Int
    )

    // ── Schedule Matching ───────────────────────────────

    /** Find the schedule rule active at the given time. */
    private fun findActiveRule(rules: List<ScheduleRule>, now: ZonedDateTime): ScheduleRule? {
        val currentDay = now.dayOfWeek
        val currentTime = now.toLocalTime()

        return rules.filter { rule ->
            currentDay in rule.days && isTimeInRange(currentTime, rule.from, rule.to)
        }.maxByOrNull { it.minInstances }
    }

    /**
     * Find a schedule rule that will become active within [leadMinutes].
     * Returns the rule if we should start warming up for it.
     */
    private fun findWarmupRule(rules: List<ScheduleRule>, now: ZonedDateTime, leadMinutes: Int): ScheduleRule? {
        val currentDay = now.dayOfWeek
        val currentTime = now.toLocalTime()
        val warmupStart = currentTime.plusMinutes(leadMinutes.toLong())

        return rules.filter { rule ->
            currentDay in rule.days &&
                    !isTimeInRange(currentTime, rule.from, rule.to) &&  // Not already active
                    isTimeInRange(warmupStart, rule.from, rule.to)      // But will be within lead time
        }.maxByOrNull { it.minInstances }
    }

    /** Check if a time falls within a range, handling overnight ranges (e.g. 22:00 to 02:00). */
    private fun isTimeInRange(time: LocalTime, from: LocalTime, to: LocalTime): Boolean {
        return if (to.isAfter(from)) {
            // Normal range: 17:00 - 23:00
            !time.isBefore(from) && time.isBefore(to)
        } else {
            // Overnight range: 22:00 - 02:00
            !time.isBefore(from) || time.isBefore(to)
        }
    }
}
