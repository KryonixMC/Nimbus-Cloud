package dev.nimbuspowered.nimbus.module.notifications

import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

// ── Data Models ─────────────────────────────────────────

data class WebhookConfig(
    val id: String,
    val type: String,           // "discord" or "slack"
    val url: String,
    val events: List<String>,
    val minSeverity: String,    // "info", "warn", "critical"
    val batchWindowMs: Long,
    val rateLimitPerMinute: Int
)

data class NotificationsGlobalConfig(
    val enabled: Boolean = true
)

data class NotificationsConfig(
    val global: NotificationsGlobalConfig = NotificationsGlobalConfig(),
    val webhooks: List<WebhookConfig> = emptyList()
)

// ── Config Manager ──────────────────────────────────────

class NotificationsConfigManager(private val configDir: Path) {

    private val logger = LoggerFactory.getLogger(NotificationsConfigManager::class.java)
    private val configFile: Path get() = configDir.resolve("nimbus.toml")

    @Volatile
    private var config: NotificationsConfig = NotificationsConfig()

    fun init() {
        if (!configDir.exists()) configDir.createDirectories()
        if (!configFile.exists()) writeExampleConfig()
        reload()
    }

    fun reload() {
        if (!configFile.exists()) {
            config = NotificationsConfig()
            return
        }
        try {
            config = parseConfig(configFile.readText())
            logger.info("Loaded {} notification webhook(s)", config.webhooks.size)
        } catch (e: Exception) {
            logger.warn("Failed to load notifications config: {}", e.message)
        }
    }

    fun getConfig(): NotificationsConfig = config

    // ── TOML Parsing ────────────────────────────────────

    private fun parseConfig(content: String): NotificationsConfig {
        val globalEnabled = extractBoolean(content, "enabled", "global") ?: true
        val global = NotificationsGlobalConfig(enabled = globalEnabled)
        val webhooks = parseWebhooks(content)
        return NotificationsConfig(global = global, webhooks = webhooks)
    }

    private fun parseWebhooks(content: String): List<WebhookConfig> {
        val webhooks = mutableListOf<WebhookConfig>()
        val blockRegex = Regex(
            """\[\[webhooks]]\s*\n([\s\S]*?)(?=\n\[\[|\n\[(?!\[)|\z)"""
        )

        for (match in blockRegex.findAll(content)) {
            val block = match.groupValues[1]

            val id = extractStringFromBlock(block, "id") ?: continue
            val type = extractStringFromBlock(block, "type") ?: continue
            val url = extractStringFromBlock(block, "url") ?: continue
            val events = extractStringArray(block, "events") ?: emptyList()
            val minSeverity = extractStringFromBlock(block, "min_severity") ?: "info"
            val batchWindowMs = extractLongFromBlock(block, "batch_window_ms") ?: 5000L
            val rateLimitPerMinute = extractIntFromBlock(block, "rate_limit_per_minute") ?: 30

            if (type != "discord" && type != "slack") {
                logger.warn("Unknown webhook type '{}' for id '{}', skipping", type, id)
                continue
            }

            webhooks.add(
                WebhookConfig(
                    id = id,
                    type = type,
                    url = url,
                    events = events,
                    minSeverity = minSeverity,
                    batchWindowMs = batchWindowMs,
                    rateLimitPerMinute = rateLimitPerMinute
                )
            )
        }
        return webhooks
    }

    // ── TOML extraction helpers ─────────────────────────

    private fun extractBoolean(content: String, key: String, section: String): Boolean? {
        val escaped = section.replace(".", "\\.")
        val sectionRegex = Regex("""\[$escaped]\s*\n([\s\S]*?)(?=\n\[(?!\[)|\z)""")
        val sectionContent = sectionRegex.find(content)?.groupValues?.get(1) ?: return null
        val regex = Regex("""^\s*$key\s*=\s*(true|false)\s*$""", RegexOption.MULTILINE)
        return regex.find(sectionContent)?.groupValues?.get(1)?.toBooleanStrictOrNull()
    }

    private fun extractStringFromBlock(block: String, key: String): String? {
        val regex = Regex("""^\s*$key\s*=\s*"(.*)"\s*$""", RegexOption.MULTILINE)
        return regex.find(block)?.groupValues?.get(1)
    }

    private fun extractIntFromBlock(block: String, key: String): Int? {
        val regex = Regex("""^\s*$key\s*=\s*(\d+)\s*$""", RegexOption.MULTILINE)
        return regex.find(block)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractLongFromBlock(block: String, key: String): Long? {
        val regex = Regex("""^\s*$key\s*=\s*(\d+)\s*$""", RegexOption.MULTILINE)
        return regex.find(block)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun extractStringArray(block: String, key: String): List<String>? {
        val regex = Regex("""^\s*$key\s*=\s*\[(.*?)]\s*$""", RegexOption.MULTILINE)
        val match = regex.find(block) ?: return null
        val inner = match.groupValues[1]
        return Regex(""""([^"]+)"""").findAll(inner).map { it.groupValues[1] }.toList()
            .ifEmpty { null }
    }

    private fun writeExampleConfig() {
        configFile.writeText(
            """
            |[global]
            |enabled = true
            |
            |# Add webhook targets below. Remove the '#' characters to activate.
            |
            |# [[webhooks]]
            |# id = "discord-main"
            |# type = "discord"
            |# url = "https://discord.com/api/webhooks/YOUR_ID/YOUR_TOKEN"
            |# events = ["ServiceCrashed", "ScaleUp", "ScaleDown", "ServiceReady"]
            |# min_severity = "info"   # info | warn | critical
            |# batch_window_ms = 5000
            |# rate_limit_per_minute = 30
            |
            |# [[webhooks]]
            |# id = "slack-ops"
            |# type = "slack"
            |# url = "https://hooks.slack.com/services/YOUR/SLACK/WEBHOOK"
            |# events = ["ServiceCrashed", "MaintenanceEnabled", "MaintenanceDisabled"]
            |# min_severity = "warn"
            |# batch_window_ms = 3000
            |# rate_limit_per_minute = 20
            """.trimMargin() + "\n"
        )
        logger.info("Created example notifications config at {}", configFile)
    }
}
