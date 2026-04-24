package dev.nimbuspowered.nimbus.module.display.routes

import dev.nimbuspowered.nimbus.api.ApiError
import dev.nimbuspowered.nimbus.api.ApiMessage
import dev.nimbuspowered.nimbus.api.apiError
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.module.display.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.displayRoutes(displayManager: DisplayManager, groupManager: GroupManager) {

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
                ?: return@get call.respond(HttpStatusCode.NotFound, apiError("No display config for '$name'", ApiError.DISPLAY_CONFIG_NOT_FOUND))
            call.respond(display.toResponse())
        }

        // PUT /api/displays/{name} — Update display config (partial)
        put("{name}") {
            val name = call.parameters["name"]!!
            if (displayManager.getDisplay(name) == null) {
                return@put call.respond(HttpStatusCode.NotFound, apiError("No display config for '$name'", ApiError.DISPLAY_CONFIG_NOT_FOUND))
            }

            val req = call.receive<UpdateDisplayRequest>()
            val update = DisplayUpdate(
                sign = req.sign?.let { SignUpdate(it.line1, it.line2, it.line3, it.line4Online, it.line4Offline) },
                npc = req.npc?.let { n ->
                    NpcUpdate(
                        displayName = n.displayName,
                        subtitle = n.subtitle,
                        subtitleOffline = n.subtitleOffline,
                        floatingItem = n.floatingItem,
                        statusItems = n.statusItems,
                        inventory = n.inventory?.let { inv ->
                            NpcInventoryUpdate(inv.title, inv.size, inv.itemName, inv.itemLore)
                        }
                    )
                },
                states = req.states
            )

            if (displayManager.updateDisplay(name, update)) {
                call.respond(displayManager.getDisplay(name)!!.toResponse())
            } else {
                call.respond(HttpStatusCode.InternalServerError, apiError("Failed to update display config", ApiError.INTERNAL_ERROR))
            }
        }

        // POST /api/displays/{name}/reset — Reset display config to defaults
        post("{name}/reset") {
            val name = call.parameters["name"]!!
            val group = groupManager.getGroup(name)
                ?: return@post call.respond(HttpStatusCode.NotFound, apiError("No group '$name'", ApiError.GROUP_NOT_FOUND))

            if (displayManager.resetDisplay(name, group.config)) {
                call.respond(displayManager.getDisplay(name)!!.toResponse())
            } else {
                call.respond(HttpStatusCode.InternalServerError, apiError("Failed to reset display config", ApiError.INTERNAL_ERROR))
            }
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

private fun DisplayConfig.toResponse(): DisplayResponse {
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
