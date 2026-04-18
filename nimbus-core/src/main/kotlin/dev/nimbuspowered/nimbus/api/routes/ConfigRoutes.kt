package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.api.*
import dev.nimbuspowered.nimbus.api.ApiErrors
import dev.nimbuspowered.nimbus.api.apiError
import dev.nimbuspowered.nimbus.config.NimbusConfig
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Exposes the nimbus.toml config as read + partial update.
 * Only non-critical fields (network.name, console.*) are editable.
 * The API token is never exposed.
 */
fun Route.configRoutes(
    config: NimbusConfig,
    configPath: Path
) {
    // GET /api/config — Read current config (token masked).
    // No dedicated node exists; gate on admin since this exposes network/api
    // binding details that aren't appropriate for non-admin dashboard users.
    get("/api/config") {
        if (!call.requirePermission("nimbus.dashboard.admin")) return@get
        call.respond(ConfigResponse(
            network = ConfigNetworkResponse(config.network.name, config.network.bind),
            controller = ConfigControllerResponse(config.controller.maxMemory, config.controller.maxServices, config.controller.heartbeatInterval),
            console = ConfigConsoleResponse(config.console.colored, config.console.logEvents),
            paths = ConfigPathsResponse(config.paths.templates, config.paths.services, config.paths.logs),
            api = ConfigApiResponse(
                enabled = config.api.enabled,
                bind = config.api.bind,
                port = config.api.port,
                hasToken = config.api.token.isNotBlank(),
                allowedOrigins = config.api.allowedOrigins
            )
        ))
    }

    // PATCH /api/config — Update non-critical fields
    patch("/api/config") {
        val request = call.receive<ConfigUpdateRequest>()

        if (request.networkName == null && request.consoleColored == null && request.consoleLogEvents == null) {
            return@patch call.respond(HttpStatusCode.BadRequest, apiError("No fields to update", ApiErrors.NO_FIELDS_TO_UPDATE))
        }

        // Read current TOML, apply changes via string replacement
        var toml = configPath.readText()

        request.networkName?.let { newName ->
            if (newName.isBlank()) {
                return@patch call.respond(HttpStatusCode.BadRequest, apiError("network.name must not be blank", ApiErrors.VALIDATION_FAILED))
            }
            toml = replaceTomlValue(toml, "name", "\"${escapeToml(newName)}\"", section = "[network]")
        }

        request.consoleColored?.let { colored ->
            toml = replaceTomlValue(toml, "colored", colored.toString(), section = "[console]")
        }

        request.consoleLogEvents?.let { logEvents ->
            toml = replaceTomlValue(toml, "log_events", logEvents.toString(), section = "[console]")
        }

        configPath.writeText(toml.replace("\r\n", "\n"))

        val changes = mutableListOf<String>()
        request.networkName?.let { changes += "network.name = '$it'" }
        request.consoleColored?.let { changes += "console.colored = $it" }
        request.consoleLogEvents?.let { changes += "console.log_events = $it" }

        call.respond(ApiMessage(true, "Config updated: ${changes.joinToString(", ")}. Restart Nimbus for full effect."))
    }
}

/**
 * Replaces a TOML key's value within a specific section.
 */
private fun replaceTomlValue(toml: String, key: String, newValue: String, section: String): String {
    val lines = toml.lines().toMutableList()
    var inSection = false

    for (i in lines.indices) {
        val trimmed = lines[i].trim()

        if (trimmed.startsWith("[")) {
            inSection = trimmed == section
            continue
        }

        if (inSection && trimmed.startsWith("$key ") || inSection && trimmed.startsWith("$key=")) {
            lines[i] = "$key = $newValue"
            break
        }
    }

    return lines.joinToString("\n")
}

private fun escapeToml(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
