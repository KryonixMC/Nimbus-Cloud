package dev.kryonix.nimbus.refinery

import dev.kryonix.nimbus.event.EventBus
import dev.kryonix.nimbus.event.NimbusEvent
import dev.kryonix.nimbus.group.GroupManager
import dev.kryonix.nimbus.service.ServiceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Nimbus-side integration for Refinery performance engine.
 *
 * Subscribes to the EventBus and handles all "refinery:*" channel messages
 * from backend services running the Refinery-Nimbus addon.
 *
 * Channels handled:
 * - refinery:announce — Service with Refinery registers itself
 * - refinery:telemetry — Periodic TPS, memory, entity, module data
 * - refinery:scaling — Overload/underload hints for auto-scaling
 * - refinery:crash — Emergency crash dump forwarding
 * - refinery:config:response — Responses to fleet config commands
 */
class RefineryIntegration(
    private val eventBus: EventBus,
    private val registry: ServiceRegistry,
    private val groupManager: GroupManager,
    private val scope: CoroutineScope,
    private val templatesDir: Path
) {

    private val logger = LoggerFactory.getLogger(RefineryIntegration::class.java)
    val store = RefineryTelemetryStore()

    private var messageJob: Job? = null
    private var serviceStopJob: Job? = null

    fun init() {
        // Listen for refinery messages
        messageJob = eventBus.on<NimbusEvent.ServiceMessage> { event ->
            if (event.channel.startsWith("refinery:")) {
                handleRefineryMessage(event)
            }
        }

        // Clean up telemetry when services stop
        serviceStopJob = eventBus.on<NimbusEvent.ServiceStopped> { event ->
            store.removeService(event.serviceName)
        }

        // Scan templates for Refinery JARs
        scanTemplates()

        logger.info("Refinery integration initialized")
    }

    /**
     * Scan all group template directories for Refinery plugin JARs.
     * This provides active detection before any service sends an announce.
     */
    fun scanTemplates() {
        if (!Files.isDirectory(templatesDir)) return

        val detected = mutableMapOf<String, TemplateDetection>()

        try {
            Files.list(templatesDir)
                .filter { Files.isDirectory(it) }
                .filter { it.fileName.toString() !in listOf("global", "global_proxy") }
                .forEach { templateDir ->
                    val groupName = templateDir.fileName.toString()
                    val pluginsDir = templateDir.resolve("plugins")
                    if (!Files.isDirectory(pluginsDir)) return@forEach

                    var hasCore = false
                    var hasAddon = false

                    try {
                        Files.list(pluginsDir).forEach { file ->
                            val name = file.fileName.toString().lowercase()
                            if (name.startsWith("refinery-core") && name.endsWith(".jar")) hasCore = true
                            if (name.startsWith("refinery-nimbus") && name.endsWith(".jar")) hasAddon = true
                        }
                    } catch (_: Exception) {}

                    if (hasCore || hasAddon) {
                        detected[groupName] = TemplateDetection(
                            groupName = groupName,
                            hasCoreJar = hasCore,
                            hasAddonJar = hasAddon
                        )
                    }
                }
        } catch (_: Exception) {}

        store.updateTemplateDetections(detected)

        if (detected.isNotEmpty()) {
            logger.info("Refinery JARs detected in {} group templates: {}", detected.size,
                detected.entries.joinToString(", ") { "${it.key}(core=${it.value.hasCoreJar}, addon=${it.value.hasAddonJar})" })
        }
    }

    data class TemplateDetection(
        val groupName: String,
        val hasCoreJar: Boolean,
        val hasAddonJar: Boolean
    )

    fun shutdown() {
        messageJob?.cancel()
        serviceStopJob?.cancel()
    }

    /**
     * Send a config command to a specific service.
     */
    suspend fun sendConfigCommand(targetService: String, action: String, params: Map<String, String> = emptyMap()) {
        val data = mutableMapOf("action" to action)
        data.putAll(params)

        eventBus.emit(NimbusEvent.ServiceMessage(
            fromService = "controller",
            toService = targetService,
            channel = "refinery:config",
            data = data
        ))
    }

    /**
     * Send a config command to all services in a group.
     */
    suspend fun sendConfigToGroup(groupName: String, action: String, params: Map<String, String> = emptyMap()) {
        val services = registry.getByGroup(groupName)
        for (service in services) {
            sendConfigCommand(service.name, action, params)
        }
    }

    /**
     * Send a config command to ALL Refinery services.
     */
    suspend fun sendConfigToAll(action: String, params: Map<String, String> = emptyMap()) {
        for (announcement in store.getAnnouncements()) {
            sendConfigCommand(announcement.serviceName, action, params)
        }
    }

    private suspend fun handleRefineryMessage(event: NimbusEvent.ServiceMessage) {
        when (event.channel) {
            "refinery:announce" -> {
                store.registerAnnouncement(event.data)
                val service = event.data["service"] ?: "unknown"
                val version = event.data["refinery_version"] ?: "?"
                logger.info("Refinery registered: {} (v{})", service, version)
            }

            "refinery:telemetry" -> {
                store.updateTelemetry(event.fromService, event.data)
            }

            "refinery:scaling" -> {
                store.updateScalingHint(event.data)
                val status = event.data["status"]
                if (status == "OVERLOADED") {
                    val service = event.data["service"] ?: event.fromService
                    val tps = event.data["tps"] ?: "?"
                    logger.warn("Refinery OVERLOAD: {} (TPS: {})", service, tps)
                }
            }

            "refinery:crash" -> {
                store.addCrashReport(event.data)
                val service = event.data["service"] ?: event.fromService
                val level = event.data["level"] ?: "?"
                logger.error("Refinery CRASH DUMP from {} (level: {})", service, level)
            }

            "refinery:config:response" -> {
                val service = event.data["service"] ?: event.fromService
                val action = event.data["action"] ?: "?"
                val success = event.data["success"] ?: "?"
                val message = event.data["message"] ?: ""
                logger.info("Refinery config response from {}: {} = {} ({})", service, action, success, message)
            }
        }
    }
}
