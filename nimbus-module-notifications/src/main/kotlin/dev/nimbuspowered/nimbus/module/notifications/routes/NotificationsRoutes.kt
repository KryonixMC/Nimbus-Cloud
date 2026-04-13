package dev.nimbuspowered.nimbus.module.notifications.routes

import dev.nimbuspowered.nimbus.module.notifications.NotificationsConfigManager
import dev.nimbuspowered.nimbus.module.notifications.NotificationsManager
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

fun Route.notificationsRoutes(manager: NotificationsManager, configManager: NotificationsConfigManager) {

    route("/api/notifications") {

        // GET /api/notifications/webhooks — list all webhooks with their status
        get("webhooks") {
            val config = configManager.getConfig()
            val webhooks = config.webhooks.map { wh ->
                WebhookStatusResponse(
                    id = wh.id,
                    type = wh.type,
                    events = wh.events,
                    minSeverity = wh.minSeverity,
                    batchWindowMs = wh.batchWindowMs,
                    rateLimitPerMinute = wh.rateLimitPerMinute
                )
            }
            call.respond(
                WebhookListResponse(
                    enabled = config.global.enabled,
                    webhooks = webhooks,
                    totalSent = manager.totalSent,
                    totalFailed = manager.totalFailed
                )
            )
        }

        // POST /api/notifications/webhooks/{id}/test — send a test notification
        post("webhooks/{id}/test") {
            val id = call.parameters["id"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("MISSING_ID", "Webhook id is required"))

            val config = configManager.getConfig()
            if (config.webhooks.none { it.id == id }) {
                return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("WEBHOOK_NOT_FOUND", "No webhook found with id '$id'"))
            }

            val ok = manager.testWebhook(id)
            if (ok) {
                call.respond(TestWebhookResponse(success = true, webhookId = id, message = "Test notification sent successfully"))
            } else {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    TestWebhookResponse(success = false, webhookId = id, message = "Failed to send test notification — check server logs")
                )
            }
        }

        // POST /api/notifications/reload — reload config from disk
        post("reload") {
            configManager.reload()
            manager.reload(configManager.getConfig())
            val count = configManager.getConfig().webhooks.size
            call.respond(ReloadResponse(success = true, webhooksLoaded = count))
        }
    }
}

// ── Response DTOs ───────────────────────────────────────

@Serializable
data class WebhookListResponse(
    val enabled: Boolean,
    val webhooks: List<WebhookStatusResponse>,
    val totalSent: Int,
    val totalFailed: Int
)

@Serializable
data class WebhookStatusResponse(
    val id: String,
    val type: String,
    val events: List<String>,
    val minSeverity: String,
    val batchWindowMs: Long,
    val rateLimitPerMinute: Int
)

@Serializable
data class TestWebhookResponse(
    val success: Boolean,
    val webhookId: String,
    val message: String
)

@Serializable
data class ReloadResponse(
    val success: Boolean,
    val webhooksLoaded: Int
)

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String
)
