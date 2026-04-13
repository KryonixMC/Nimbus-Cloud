package dev.nimbuspowered.nimbus.module.notifications

import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class NotificationsManager(
    @Volatile private var config: NotificationsConfig,
    private val scope: CoroutineScope
) {

    private val logger = LoggerFactory.getLogger(NotificationsManager::class.java)

    // ── Rate limiting ────────────────────────────────────

    inner class RateLimiter(private val maxPerMinute: Int) {
        @Volatile private var tokens: Int = maxPerMinute
        @Volatile private var lastRefillEpochMs: Long = System.currentTimeMillis()

        fun tryConsume(): Boolean {
            val now = System.currentTimeMillis()
            val elapsed = now - lastRefillEpochMs
            if (elapsed >= 60_000L) {
                tokens = maxPerMinute
                lastRefillEpochMs = now
            }
            return if (tokens > 0) {
                tokens--
                true
            } else {
                false
            }
        }
    }

    // ── State ────────────────────────────────────────────

    private val httpClient: HttpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 10_000
        }
    }

    private val batchQueues = ConcurrentHashMap<String, MutableList<PendingNotification>>()
    private val batchJobs   = ConcurrentHashMap<String, Job>()
    private val rateLimiters = ConcurrentHashMap<String, RateLimiter>()
    private val subscriptionJobs = mutableListOf<Job>()

    // Track sent/failed counts for status reporting
    @Volatile var totalSent: Int = 0
        private set
    @Volatile var totalFailed: Int = 0
        private set

    // ── Lifecycle ────────────────────────────────────────

    fun start(eventBus: EventBus, registry: ServiceRegistry) {
        if (!config.global.enabled) {
            logger.info("Notifications module is disabled in config")
            return
        }

        // Initialize rate limiters for all configured webhooks
        for (webhook in config.webhooks) {
            rateLimiters[webhook.id] = RateLimiter(webhook.rateLimitPerMinute)
        }

        subscriptionJobs += eventBus.on<NimbusEvent.ServiceCrashed> { event ->
            val groupName = registry.get(event.serviceName)?.groupName ?: "unknown"
            dispatch(
                eventType = "ServiceCrashed",
                severity = "critical",
                data = mapOf(
                    "service" to event.serviceName,
                    "group" to groupName,
                    "exit_code" to event.exitCode.toString(),
                    "restart_attempt" to event.restartAttempt.toString()
                )
            )
        }

        subscriptionJobs += eventBus.on<NimbusEvent.ScaleUp> { event ->
            dispatch(
                eventType = "ScaleUp",
                severity = "warn",
                data = mapOf(
                    "group" to event.groupName,
                    "current_instances" to event.currentInstances.toString(),
                    "target_instances" to event.targetInstances.toString(),
                    "reason" to event.reason
                )
            )
        }

        subscriptionJobs += eventBus.on<NimbusEvent.ScaleDown> { event ->
            dispatch(
                eventType = "ScaleDown",
                severity = "warn",
                data = mapOf(
                    "group" to event.groupName,
                    "service" to event.serviceName,
                    "reason" to event.reason
                )
            )
        }

        subscriptionJobs += eventBus.on<NimbusEvent.ServiceReady> { event ->
            dispatch(
                eventType = "ServiceReady",
                severity = "info",
                data = mapOf(
                    "service" to event.serviceName,
                    "group" to event.groupName
                )
            )
        }

        subscriptionJobs += eventBus.on<NimbusEvent.MaintenanceEnabled> { event ->
            dispatch(
                eventType = "MaintenanceEnabled",
                severity = "warn",
                data = buildMap {
                    put("scope", event.scope)
                    if (event.reason.isNotBlank()) put("reason", event.reason)
                }
            )
        }

        subscriptionJobs += eventBus.on<NimbusEvent.MaintenanceDisabled> { event ->
            dispatch(
                eventType = "MaintenanceDisabled",
                severity = "warn",
                data = mapOf("scope" to event.scope)
            )
        }

        subscriptionJobs += eventBus.on<NimbusEvent.ModuleEvent> { event ->
            val severity = when {
                event.type.contains("ANOMALY_CRITICAL", ignoreCase = true) -> "critical"
                event.type.contains("ANOMALY_WARNING", ignoreCase = true)  -> "warn"
                event.type.contains("BACKUP", ignoreCase = true)           -> "info"
                else -> return@on   // Not a notification-worthy module event
            }
            dispatch(
                eventType = event.type,
                severity = severity,
                data = event.data
            )
        }

        logger.info("Notifications manager started, monitoring {} webhook(s)", config.webhooks.size)
    }

    // ── Dispatch ─────────────────────────────────────────

    private fun dispatch(eventType: String, severity: String, data: Map<String, String>) {
        if (!config.global.enabled) return

        val notification = PendingNotification(
            eventType = eventType,
            severity = severity,
            data = data,
            timestamp = Instant.now()
        )

        val interestedWebhooks = config.webhooks.filter { webhook ->
            webhook.subscribesTo(eventType, severity)
        }

        for (webhook in interestedWebhooks) {
            enqueue(webhook.id, notification)
        }
    }

    private fun enqueue(webhookId: String, notification: PendingNotification) {
        val queue = batchQueues.computeIfAbsent(webhookId) {
            java.util.Collections.synchronizedList(mutableListOf())
        }
        queue.add(notification)

        // Launch debounce job if none is active for this webhook
        if (batchJobs[webhookId]?.isActive != true) {
            val webhook = config.webhooks.find { it.id == webhookId } ?: return
            val job = scope.launch {
                delay(webhook.batchWindowMs)
                flush(webhookId)
            }
            batchJobs[webhookId] = job
        }
    }

    private suspend fun flush(webhookId: String) {
        val queue = batchQueues[webhookId] ?: return
        val notifications = synchronized(queue) {
            val snapshot = queue.toList()
            queue.clear()
            snapshot
        }

        if (notifications.isEmpty()) return

        val webhook = config.webhooks.find { it.id == webhookId } ?: return
        val rateLimiter = rateLimiters.getOrPut(webhookId) { RateLimiter(webhook.rateLimitPerMinute) }

        if (!rateLimiter.tryConsume()) {
            logger.warn("Rate limit exceeded for webhook '{}', dropping {} notification(s)", webhookId, notifications.size)
            return
        }

        send(webhook, notifications)
    }

    private suspend fun send(webhook: WebhookConfig, notifications: List<PendingNotification>) {
        val payload = when (webhook.type) {
            "discord" -> WebhookFormatter.formatDiscord(notifications)
            "slack"   -> WebhookFormatter.formatSlack(notifications)
            else      -> return
        }

        try {
            val response: HttpResponse = httpClient.post(webhook.url) {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            if (response.status.isSuccess()) {
                totalSent++
                logger.debug("Sent {} notification(s) to webhook '{}'", notifications.size, webhook.id)
            } else {
                totalFailed++
                logger.warn(
                    "Webhook '{}' responded with HTTP {}: {}",
                    webhook.id, response.status.value, response.bodyAsText().take(200)
                )
            }
        } catch (e: Exception) {
            totalFailed++
            logger.warn("Failed to send to webhook '{}': {}", webhook.id, e.message)
        }
    }

    // ── Test ─────────────────────────────────────────────

    suspend fun testWebhook(webhookId: String): Boolean {
        val webhook = config.webhooks.find { it.id == webhookId } ?: return false
        val testNotification = PendingNotification(
            eventType = "TestEvent",
            severity = "info",
            data = mapOf(
                "message" to "This is a test notification from Nimbus.",
                "webhook_id" to webhookId
            ),
            timestamp = Instant.now()
        )
        return try {
            send(webhook, listOf(testNotification))
            true
        } catch (e: Exception) {
            logger.warn("Test webhook '{}' failed: {}", webhookId, e.message)
            false
        }
    }

    // ── Reload ───────────────────────────────────────────

    fun reload(newConfig: NotificationsConfig) {
        config = newConfig

        // Re-init rate limiters for new/updated webhooks
        rateLimiters.clear()
        for (webhook in newConfig.webhooks) {
            rateLimiters[webhook.id] = RateLimiter(webhook.rateLimitPerMinute)
        }

        logger.info("Notifications config reloaded ({} webhook(s))", newConfig.webhooks.size)
    }

    // ── Shutdown ─────────────────────────────────────────

    fun shutdown() {
        subscriptionJobs.forEach { it.cancel() }
        subscriptionJobs.clear()
        batchJobs.values.forEach { it.cancel() }
        batchJobs.clear()
        runCatching { httpClient.close() }
        logger.info("Notifications manager shut down")
    }

    // ── Helpers ──────────────────────────────────────────

    private fun WebhookConfig.subscribesTo(eventType: String, severity: String): Boolean {
        val eventMatches = events.isEmpty() || events.any { it.equals(eventType, ignoreCase = true) }
        val severityMatches = severityLevel(severity) >= severityLevel(minSeverity)
        return eventMatches && severityMatches
    }

    private fun severityLevel(severity: String): Int = when (severity.lowercase()) {
        "critical" -> 2
        "warn"     -> 1
        else       -> 0   // info
    }
}
