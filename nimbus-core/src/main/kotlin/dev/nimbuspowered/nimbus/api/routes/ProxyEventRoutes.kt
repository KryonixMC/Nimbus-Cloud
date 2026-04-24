package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.api.ApiError
import dev.nimbuspowered.nimbus.api.ApiMessage
import dev.nimbuspowered.nimbus.api.apiError
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

/**
 * Generic endpoint for the Velocity Bridge to report proxy-level events.
 * These are fundamental events (player join/leave/switch) that the proxy always knows about.
 * Modules (like Players) subscribe to the resulting Core events via EventBus.
 */
fun Route.proxyEventRoutes(eventBus: EventBus) {

    // POST /api/proxy/events — Report a proxy event (player connect/disconnect/switch)
    post("/api/proxy/events") {
        val request = call.receive<ProxyEventReport>()

        // H3 fix: validate UUID format before downstream use
        val validatedUuid = request.uuid?.let { raw ->
            try {
                java.util.UUID.fromString(raw).toString()
            } catch (_: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid UUID format: '$raw'", ApiError.VALIDATION_FAILED))
            }
        }

        when (request.type.uppercase()) {
            "PLAYER_CONNECTED" -> {
                val player = request.player ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Missing 'player'", ApiError.VALIDATION_FAILED))
                val uuid = validatedUuid ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Missing 'uuid'", ApiError.VALIDATION_FAILED))
                val service = request.service ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Missing 'service'", ApiError.VALIDATION_FAILED))
                eventBus.emit(NimbusEvent.PlayerConnected(player, uuid, service))
            }
            "PLAYER_DISCONNECTED" -> {
                val player = request.player ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Missing 'player'", ApiError.VALIDATION_FAILED))
                val uuid = validatedUuid ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Missing 'uuid'", ApiError.VALIDATION_FAILED))
                val service = request.service ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Missing 'service'", ApiError.VALIDATION_FAILED))
                eventBus.emit(NimbusEvent.PlayerDisconnected(player, uuid, service))
            }
            "PLAYER_SERVER_SWITCH" -> {
                val player = request.player ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Missing 'player'", ApiError.VALIDATION_FAILED))
                val uuid = validatedUuid ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Missing 'uuid'", ApiError.VALIDATION_FAILED))
                val from = request.fromService ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Missing 'fromService'", ApiError.VALIDATION_FAILED))
                val to = request.toService ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Missing 'toService'", ApiError.VALIDATION_FAILED))
                eventBus.emit(NimbusEvent.PlayerServerSwitch(player, uuid, from, to))
            }
            else -> {
                return@post call.respond(HttpStatusCode.BadRequest, apiError("Unknown event type: ${request.type}", ApiError.VALIDATION_FAILED))
            }
        }
        call.respond(ApiMessage(true, "Event received"))
    }
}

@Serializable
data class ProxyEventReport(
    val type: String,
    val player: String? = null,
    val uuid: String? = null,
    val service: String? = null,
    val fromService: String? = null,
    val toService: String? = null
)
