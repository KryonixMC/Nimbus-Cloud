package dev.nimbuspowered.nimbus.module.anomaly

import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

// ── Data Model ──────────────────────────────────────────────

data class AnomalyConfig(
    val enabled: Boolean = true,
    val evaluationIntervalSeconds: Long = 60L,
    /** How many recent metric samples to include in the rolling window. */
    val windowSamples: Int = 60,
    /** Z-score threshold for flagging a service against its own history. */
    val zscoreThreshold: Double = 2.5,
    val peerComparisonEnabled: Boolean = true,
    /** Z-score threshold for flagging a service against its group peers. */
    val peerZscoreThreshold: Double = 2.0
)

// ── Config Manager ──────────────────────────────────────────

class AnomalyConfigManager(private val configDir: Path) {

    private val logger = LoggerFactory.getLogger(AnomalyConfigManager::class.java)

    @Volatile
    private var config: AnomalyConfig = AnomalyConfig()

    private val configFile: Path get() = configDir.resolve("nimbus.toml")

    fun init() {
        if (!configDir.exists()) configDir.createDirectories()
        if (!configFile.exists()) writeDefault()
        reload()
    }

    fun reload() {
        if (!configFile.exists()) {
            config = AnomalyConfig()
            return
        }
        try {
            config = parseConfig(configFile.readText())
            logger.info(
                "Anomaly detection config loaded: enabled={}, interval={}s, window={}, " +
                    "zscoreThreshold={}, peerComparison={}, peerZscoreThreshold={}",
                config.enabled,
                config.evaluationIntervalSeconds,
                config.windowSamples,
                config.zscoreThreshold,
                config.peerComparisonEnabled,
                config.peerZscoreThreshold
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse anomaly config, using defaults", e)
            config = AnomalyConfig()
        }
    }

    fun getConfig(): AnomalyConfig = config

    // ── TOML Parsing ─────────────────────────────────────────

    private fun parseConfig(content: String): AnomalyConfig {
        val enabled = extractBoolean(content, "enabled", "global") ?: true
        val intervalSeconds = extractLong(content, "evaluation_interval_seconds", "global") ?: 60L
        val windowSamples = extractInt(content, "window_samples", "global") ?: 60
        val zscoreThreshold = extractDouble(content, "zscore_threshold", "global") ?: 2.5
        val peerEnabled = extractBoolean(content, "peer_comparison_enabled", "global") ?: true
        val peerThreshold = extractDouble(content, "peer_zscore_threshold", "global") ?: 2.0

        return AnomalyConfig(
            enabled = enabled,
            evaluationIntervalSeconds = intervalSeconds.coerceAtLeast(10L),
            windowSamples = windowSamples.coerceIn(3, 1000),
            zscoreThreshold = zscoreThreshold.coerceIn(1.0, 10.0),
            peerComparisonEnabled = peerEnabled,
            peerZscoreThreshold = peerThreshold.coerceIn(1.0, 10.0)
        )
    }

    private fun extractBoolean(content: String, key: String, section: String): Boolean? {
        val sectionContent = getSectionContent(content, section) ?: return null
        val regex = Regex("""^\s*$key\s*=\s*(true|false)\s*$""", RegexOption.MULTILINE)
        return regex.find(sectionContent)?.groupValues?.get(1)?.toBooleanStrictOrNull()
    }

    private fun extractInt(content: String, key: String, section: String): Int? {
        val sectionContent = getSectionContent(content, section) ?: return null
        val regex = Regex("""^\s*$key\s*=\s*(\d+)\s*$""", RegexOption.MULTILINE)
        return regex.find(sectionContent)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractLong(content: String, key: String, section: String): Long? {
        val sectionContent = getSectionContent(content, section) ?: return null
        val regex = Regex("""^\s*$key\s*=\s*(\d+)\s*$""", RegexOption.MULTILINE)
        return regex.find(sectionContent)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun extractDouble(content: String, key: String, section: String): Double? {
        val sectionContent = getSectionContent(content, section) ?: return null
        val regex = Regex("""^\s*$key\s*=\s*([0-9]+\.?[0-9]*)\s*$""", RegexOption.MULTILINE)
        return regex.find(sectionContent)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun getSectionContent(content: String, section: String): String? {
        val escaped = section.replace(".", "\\.")
        val sectionRegex = Regex("""\[$escaped]\s*\n([\s\S]*?)(?=\n\[(?!\[)|\z)""")
        return sectionRegex.find(content)?.groupValues?.get(1)
    }

    private fun writeDefault() {
        configFile.writeText(
            """
            |[global]
            |enabled = true
            |evaluation_interval_seconds = 60
            |window_samples = 60
            |zscore_threshold = 2.5
            |peer_comparison_enabled = true
            |peer_zscore_threshold = 2.0
            """.trimMargin() + "\n"
        )
        logger.info("Generated default anomaly detection config")
    }
}
