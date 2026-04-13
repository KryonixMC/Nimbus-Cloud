package dev.nimbuspowered.nimbus.module.scaling

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

// ── Data Models ─────────────────────────────────────────

data class GroupScalingConfig(
    val groupName: String,
    val schedule: ScheduleConfig,
    val prediction: PredictionConfig = PredictionConfig()
)

data class ScheduleConfig(
    val enabled: Boolean = true,
    val timezone: ZoneId = ZoneId.of("Europe/Berlin"),
    val rules: List<ScheduleRule> = emptyList(),
    val warmup: WarmupConfig = WarmupConfig()
)

data class ScheduleRule(
    val name: String,
    val days: Set<DayOfWeek>,
    val from: LocalTime,
    val to: LocalTime,
    val minInstances: Int,
    val maxInstances: Int? = null
)

data class WarmupConfig(
    val enabled: Boolean = true,
    val leadTimeMinutes: Int = 10
)

// ── Prediction Config ──────────────────────────────────

enum class PredictionAlgorithm {
    /** Simple average of matching day+hour over the past 28 days. */
    SIMPLE_AVERAGE,
    /** Weighted moving average: recent days weighted more heavily. */
    WEIGHTED_AVERAGE,
    /** WMA with linear trend adjustment from recent hours. */
    TREND_ADJUSTED
}

data class PredictionConfig(
    val enabled: Boolean = true,
    val algorithm: PredictionAlgorithm = PredictionAlgorithm.WEIGHTED_AVERAGE,
    /** Weight decay factor per day (0.0-1.0). Most recent day = 1.0, day before = decay^1, etc. */
    val weightDecayFactor: Double = 0.8,
    /** Hours of recent data used for trend calculation (TREND_ADJUSTED only). */
    val trendWindowHours: Int = 3,
    /** Detect upcoming peaks and pre-warm ahead of time. */
    val peakDetectionEnabled: Boolean = true,
    /** Minutes ahead to scan for peaks. */
    val peakLeadMinutes: Int = 20,
    /** Scheduled events with known player counts (overrides statistical prediction). */
    val scheduledEvents: List<ScheduledEvent> = emptyList()
)

data class ScheduledEvent(
    val name: String,
    val dayOfWeek: DayOfWeek,
    val startTime: LocalTime,
    val durationMinutes: Int,
    val expectedPlayers: Int
)

// ── Config Manager ──────────────────────────────────────

class SmartScalingConfigManager(private val configDir: Path) {

    private val logger = LoggerFactory.getLogger(SmartScalingConfigManager::class.java)
    private val configs = mutableMapOf<String, GroupScalingConfig>()

    fun init() {
        if (!configDir.exists()) configDir.createDirectories()
        reload()
    }

    fun reload() {
        configs.clear()
        if (!configDir.exists()) return

        Files.list(configDir)
            .filter { it.toString().endsWith(".toml") }
            .forEach { file ->
                try {
                    val config = parseConfig(file)
                    configs[config.groupName] = config
                } catch (e: Exception) {
                    logger.warn("Failed to load scaling config: {}", file.fileName, e)
                }
            }

        logger.info("Loaded {} smart scaling configs", configs.size)
    }

    fun getConfig(groupName: String): GroupScalingConfig? = configs[groupName]

    fun getAllConfigs(): Map<String, GroupScalingConfig> = configs.toMap()

    /** Generate a default config for a group if none exists. */
    fun ensureConfig(groupName: String) {
        val file = configDir.resolve("${groupName}.toml")
        if (file.exists()) return

        file.writeText(
            """
            |[schedule]
            |enabled = false
            |timezone = "Europe/Berlin"
            |
            |# Example schedule rule:
            |# [[schedule.rules]]
            |# name = "evening-peak"
            |# days = ["MON", "TUE", "WED", "THU", "FRI"]
            |# from = "17:00"
            |# to = "23:00"
            |# min_instances = 3
            |
            |[warmup]
            |enabled = true
            |lead_time_minutes = 10
            |
            |[prediction]
            |enabled = true
            |algorithm = "WEIGHTED_AVERAGE"  # SIMPLE_AVERAGE, WEIGHTED_AVERAGE, TREND_ADJUSTED
            |weight_decay_factor = 0.8       # Recent days weighted more (0.1-1.0)
            |trend_window_hours = 3          # Hours of recent data for trend calculation
            |peak_detection_enabled = true
            |peak_lead_minutes = 20          # Minutes ahead to scan for peaks
            |
            |# Example scheduled event (overrides statistical prediction):
            |# [[prediction.scheduled_events]]
            |# name = "friday-tournament"
            |# day_of_week = "FRI"
            |# start_time = "19:00"
            |# duration_minutes = 120
            |# expected_players = 200
            """.trimMargin() + "\n"
        )
        logger.info("Generated default scaling config for group '{}'", groupName)
    }

    // ── TOML Parsing ────────────────────────────────────

    private fun parseConfig(file: Path): GroupScalingConfig {
        val content = file.readText()
        val groupName = file.fileName.toString().removeSuffix(".toml")

        val enabled = extractBoolean(content, "enabled", "schedule") ?: true
        val timezone = extractString(content, "timezone", "schedule")
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneId.of("Europe/Berlin")

        val rules = parseRules(content)

        val warmupEnabled = extractBoolean(content, "enabled", "warmup") ?: true
        val leadTime = extractInt(content, "lead_time_minutes", "warmup") ?: 10

        val prediction = parsePredictionConfig(content)

        return GroupScalingConfig(
            groupName = groupName,
            schedule = ScheduleConfig(
                enabled = enabled,
                timezone = timezone,
                rules = rules,
                warmup = WarmupConfig(warmupEnabled, leadTime)
            ),
            prediction = prediction
        )
    }

    private fun parsePredictionConfig(content: String): PredictionConfig {
        val predEnabled = extractBoolean(content, "enabled", "prediction") ?: true
        val algorithmStr = extractString(content, "algorithm", "prediction") ?: "WEIGHTED_AVERAGE"
        val algorithm = runCatching { PredictionAlgorithm.valueOf(algorithmStr.uppercase()) }
            .getOrDefault(PredictionAlgorithm.WEIGHTED_AVERAGE)
        val decay = extractDouble(content, "weight_decay_factor", "prediction") ?: 0.8
        val trendWindow = extractInt(content, "trend_window_hours", "prediction") ?: 3
        val peakEnabled = extractBoolean(content, "peak_detection_enabled", "prediction") ?: true
        val peakLead = extractInt(content, "peak_lead_minutes", "prediction") ?: 20
        val events = parseScheduledEvents(content)

        return PredictionConfig(
            enabled = predEnabled,
            algorithm = algorithm,
            weightDecayFactor = decay.coerceIn(0.1, 1.0),
            trendWindowHours = trendWindow.coerceIn(1, 24),
            peakDetectionEnabled = peakEnabled,
            peakLeadMinutes = peakLead.coerceIn(5, 60),
            scheduledEvents = events
        )
    }

    private fun parseScheduledEvents(content: String): List<ScheduledEvent> {
        val events = mutableListOf<ScheduledEvent>()
        val blockRegex = Regex(
            """\[\[prediction\.scheduled_events]]\s*\n([\s\S]*?)(?=\n\[\[|\n\[(?!\[)|\z)"""
        )

        for (match in blockRegex.findAll(content)) {
            val block = match.groupValues[1]
            val name = extractStringFromBlock(block, "name") ?: continue
            val dayStr = extractStringFromBlock(block, "day_of_week") ?: continue
            val startTimeStr = extractStringFromBlock(block, "start_time") ?: continue
            val duration = extractIntFromBlock(block, "duration_minutes") ?: continue
            val expected = extractIntFromBlock(block, "expected_players") ?: continue

            val day = parseDayOfWeek(dayStr) ?: continue
            val startTime = runCatching { LocalTime.parse(startTimeStr) }.getOrNull() ?: continue

            events.add(ScheduledEvent(name, day, startTime, duration, expected))
        }
        return events
    }

    private fun parseRules(content: String): List<ScheduleRule> {
        val rules = mutableListOf<ScheduleRule>()

        // Match each [[schedule.rules]] block
        val blockRegex = Regex(
            """\[\[schedule\.rules]]\s*\n([\s\S]*?)(?=\n\[\[|\n\[(?!\[)|\z)"""
        )

        for (match in blockRegex.findAll(content)) {
            val block = match.groupValues[1]

            val name = extractStringFromBlock(block, "name") ?: continue
            val daysStr = extractStringArray(block, "days") ?: continue
            val from = extractStringFromBlock(block, "from") ?: continue
            val to = extractStringFromBlock(block, "to") ?: continue
            val minInstances = extractIntFromBlock(block, "min_instances") ?: continue
            val maxInstances = extractIntFromBlock(block, "max_instances")

            val days = daysStr.mapNotNull { parseDayOfWeek(it) }.toSet()
            if (days.isEmpty()) continue

            rules.add(
                ScheduleRule(
                    name = name,
                    days = days,
                    from = LocalTime.parse(from),
                    to = LocalTime.parse(to),
                    minInstances = minInstances,
                    maxInstances = maxInstances
                )
            )
        }

        return rules
    }

    private fun parseDayOfWeek(s: String): DayOfWeek? = when (s.uppercase().trim()) {
        "MON", "MONDAY" -> DayOfWeek.MONDAY
        "TUE", "TUESDAY" -> DayOfWeek.TUESDAY
        "WED", "WEDNESDAY" -> DayOfWeek.WEDNESDAY
        "THU", "THURSDAY" -> DayOfWeek.THURSDAY
        "FRI", "FRIDAY" -> DayOfWeek.FRIDAY
        "SAT", "SATURDAY" -> DayOfWeek.SATURDAY
        "SUN", "SUNDAY" -> DayOfWeek.SUNDAY
        else -> null
    }

    // ── TOML extraction helpers ─────────────────────────

    private fun extractString(content: String, key: String, section: String): String? {
        val escaped = section.replace(".", "\\.")
        val sectionRegex = Regex("""\[$escaped]\s*\n([\s\S]*?)(?=\n\[(?!\[)|\z)""")
        val sectionContent = sectionRegex.find(content)?.groupValues?.get(1) ?: return null
        return extractStringFromBlock(sectionContent, key)
    }

    private fun extractBoolean(content: String, key: String, section: String): Boolean? {
        val escaped = section.replace(".", "\\.")
        val sectionRegex = Regex("""\[$escaped]\s*\n([\s\S]*?)(?=\n\[(?!\[)|\z)""")
        val sectionContent = sectionRegex.find(content)?.groupValues?.get(1) ?: return null
        val regex = Regex("""^\s*$key\s*=\s*(true|false)\s*$""", RegexOption.MULTILINE)
        return regex.find(sectionContent)?.groupValues?.get(1)?.toBooleanStrictOrNull()
    }

    private fun extractInt(content: String, key: String, section: String): Int? {
        val escaped = section.replace(".", "\\.")
        val sectionRegex = Regex("""\[$escaped]\s*\n([\s\S]*?)(?=\n\[(?!\[)|\z)""")
        val sectionContent = sectionRegex.find(content)?.groupValues?.get(1) ?: return null
        return extractIntFromBlock(sectionContent, key)
    }

    private fun extractDouble(content: String, key: String, section: String): Double? {
        val escaped = section.replace(".", "\\.")
        val sectionRegex = Regex("""\[$escaped]\s*\n([\s\S]*?)(?=\n\[(?!\[)|\z)""")
        val sectionContent = sectionRegex.find(content)?.groupValues?.get(1) ?: return null
        val regex = Regex("""^\s*$key\s*=\s*([0-9]+\.?[0-9]*)\s*$""", RegexOption.MULTILINE)
        return regex.find(sectionContent)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun extractStringFromBlock(block: String, key: String): String? {
        val regex = Regex("""^\s*$key\s*=\s*"(.*)"\s*$""", RegexOption.MULTILINE)
        return regex.find(block)?.groupValues?.get(1)
    }

    private fun extractIntFromBlock(block: String, key: String): Int? {
        val regex = Regex("""^\s*$key\s*=\s*(\d+)\s*$""", RegexOption.MULTILINE)
        return regex.find(block)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractStringArray(block: String, key: String): List<String>? {
        val regex = Regex("""^\s*$key\s*=\s*\[(.*?)]\s*$""", RegexOption.MULTILINE)
        val match = regex.find(block) ?: return null
        val inner = match.groupValues[1]
        return Regex(""""([^"]+)"""").findAll(inner).map { it.groupValues[1] }.toList()
            .ifEmpty { null }
    }
}
