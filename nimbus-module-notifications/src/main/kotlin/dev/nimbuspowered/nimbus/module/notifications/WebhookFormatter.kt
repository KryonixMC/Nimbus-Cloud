package dev.nimbuspowered.nimbus.module.notifications

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// ── Pending Notification ─────────────────────────────────

data class PendingNotification(
    val eventType: String,
    val severity: String,     // "info", "warn", "critical"
    val data: Map<String, String>,
    val timestamp: Instant = Instant.now()
)

// ── Formatter ────────────────────────────────────────────

object WebhookFormatter {

    private val ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneOffset.UTC)

    /** Discord embed color by severity. */
    private fun discordColor(severity: String): Int = when (severity) {
        "critical" -> 0xED4245
        "warn"     -> 0xFEE75C
        else       -> 0x57F287   // info — green
    }

    /**
     * Build a Discord webhook JSON payload with up to 10 embeds.
     * Each [PendingNotification] becomes one embed.
     */
    fun formatDiscord(notifications: List<PendingNotification>): String {
        val embeds = notifications.take(10)
        val sb = StringBuilder()
        sb.append("""{"embeds":[""")

        embeds.forEachIndexed { index, notif ->
            if (index > 0) sb.append(",")

            val color = discordColor(notif.severity)
            val ts = ISO_FMT.format(notif.timestamp)
            val title = escapeJson(humanTitle(notif.eventType))

            sb.append("""{"title":${jsonString(title)},"color":$color,"fields":[""")

            val fields = notif.data.entries.toList()
            fields.forEachIndexed { fi, (k, v) ->
                if (fi > 0) sb.append(",")
                sb.append("""{"name":${jsonString(humanKey(k))},"value":${jsonString(v)},"inline":true}""")
            }

            sb.append("""],"footer":{"text":${jsonString("Nimbus | $ts UTC")}}""")
            sb.append("}")
        }

        sb.append("]}")
        return sb.toString()
    }

    /**
     * Build a Slack Block Kit JSON payload.
     * Each [PendingNotification] gets a header block + section + optional divider.
     */
    fun formatSlack(notifications: List<PendingNotification>): String {
        val sb = StringBuilder()
        sb.append("""{"blocks":[""")

        notifications.forEachIndexed { index, notif ->
            if (index > 0) {
                // Divider between notifications
                sb.append(""",{"type":"divider"},""")
            }

            val title = escapeJson(humanTitle(notif.eventType))
            val severityEmoji = when (notif.severity) {
                "critical" -> ":red_circle:"
                "warn"     -> ":yellow_circle:"
                else       -> ":large_green_circle:"
            }

            // Header block
            sb.append("""{"type":"header","text":{"type":"plain_text","text":"$severityEmoji $title"}}""")

            // Section block with mrkdwn fields
            if (notif.data.isNotEmpty()) {
                sb.append(""",{"type":"section","fields":[""")
                val fields = notif.data.entries.toList()
                fields.forEachIndexed { fi, (k, v) ->
                    if (fi > 0) sb.append(",")
                    val label = slackEscape(humanKey(k))
                    val value = slackEscape(v)
                    sb.append("""{"type":"mrkdwn","text":"*${label}*\n${value}"}""")
                }
                sb.append("]}")
            }

            // Timestamp context block
            val ts = ISO_FMT.format(notif.timestamp)
            sb.append(""",{"type":"context","elements":[{"type":"mrkdwn","text":"Nimbus | $ts UTC"}]}""")
        }

        sb.append("]}")
        return sb.toString()
    }

    // ── Helpers ──────────────────────────────────────────

    /** Convert camelCase/PascalCase event type to a readable title. */
    private fun humanTitle(eventType: String): String =
        eventType.replace(Regex("([A-Z])"), " $1").trim()

    /** Convert snake_case key to Title Case. */
    private fun humanKey(key: String): String =
        key.split('_').joinToString(" ") { it.replaceFirstChar(Char::titlecase) }

    private fun jsonString(value: String): String = "\"${escapeJson(value)}\""

    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun slackEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
