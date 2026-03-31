package dev.nimbus.api.routes

import dev.nimbus.api.*
import dev.nimbus.display.DisplayManager
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.displayRoutes(displayManager: DisplayManager) {

    route("/api/displays") {

        // GET /api/displays — List all display configs
        get {
            val displays = displayManager.getAllDisplays().values.map { it.toResponse() }
            call.respond(DisplayListResponse(displays, displays.size))
        }

        // GET /api/displays/{name} — Get display config for a group
        get("{name}") {
            val name = call.parameters["name"]!!
            val display = displayManager.getDisplay(name)
                ?: return@get call.respond(HttpStatusCode.NotFound, ApiMessage(false, "No display config for '$name'"))
            call.respond(display.toResponse())
        }

        // GET /api/displays/{name}/state/{state} — Resolve a state label
        get("{name}/state/{state}") {
            val name = call.parameters["name"]!!
            val rawState = call.parameters["state"]!!
            val label = displayManager.resolveStateLabel(name, rawState)
            call.respond(mapOf("raw" to rawState, "label" to label))
        }
    }
}

private fun dev.nimbus.display.DisplayConfig.toResponse(): DisplayResponse {
    return DisplayResponse(
        name = display.name,
        sign = SignDisplayResponse(
            line1 = display.sign.line1,
            line2 = display.sign.line2,
            line3 = display.sign.line3,
            line4Online = display.sign.line4Online,
            line4Offline = display.sign.line4Offline
        ),
        npc = NpcDisplayResponse(
            displayName = display.npc.displayName,
            subtitle = display.npc.subtitle,
            subtitleOffline = display.npc.subtitleOffline,
            floatingItem = display.npc.floatingItem,
            statusItems = display.npc.statusItems,
            inventory = NpcInventoryResponse(
                title = display.npc.inventory.title,
                size = display.npc.inventory.size,
                itemName = display.npc.inventory.itemName,
                itemLore = display.npc.inventory.itemLore
            )
        ),
        states = display.states
    )
}
