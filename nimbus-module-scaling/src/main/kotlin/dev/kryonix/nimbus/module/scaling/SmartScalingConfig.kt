package dev.kryonix.nimbus.module.scaling

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
    val schedule: ScheduleConfig
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

        return GroupScalingConfig(
            groupName = groupName,
            schedule = ScheduleConfig(
                enabled = enabled,
                timezone = timezone,
                rules = rules,
                warmup = WarmupConfig(warmupEnabled, leadTime)
            )
        )
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
