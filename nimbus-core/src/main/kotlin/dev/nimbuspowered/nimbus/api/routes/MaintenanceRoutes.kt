package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.api.*
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.proxy.ProxySyncManager
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.maintenanceRoutes(proxySyncManager: ProxySyncManager, eventBus: EventBus) {

    // GET /api/maintenance — Full maintenance status
    get("/api/maintenance") {
        call.respond(buildStatusResponse(proxySyncManager))
    }

    // POST /api/maintenance/global — Toggle global maintenance
    post("/api/maintenance/global") {
        val req = call.receive<MaintenanceToggleRequest>()
        val changed = proxySyncManager.setGlobalMaintenance(req.enabled)
        if (changed) {
            if (req.enabled) {
                eventBus.emit(NimbusEvent.MaintenanceEnabled("global", req.reason))
            } else {
                eventBus.emit(NimbusEvent.MaintenanceDisabled("global"))
            }
        }
        call.respond(buildStatusResponse(proxySyncManager))
    }

    // PUT /api/maintenance/global — Update global maintenance config
    put("/api/maintenance/global") {
        val req = call.receive<GlobalMaintenanceUpdateRequest>()
        proxySyncManager.updateGlobalMaintenanceConfig(req.motdLine1, req.motdLine2, req.protocolText, req.kickMessage)
        call.respond(buildStatusResponse(proxySyncManager))
    }

    // POST /api/maintenance/groups/{name} — Toggle group maintenance
    post("/api/maintenance/groups/{name}") {
        val groupName = call.parameters["name"]!!
        val req = call.receive<MaintenanceToggleRequest>()
        val changed = proxySyncManager.setGroupMaintenance(groupName, req.enabled)
        if (changed) {
            if (req.enabled) {
                eventBus.emit(NimbusEvent.MaintenanceEnabled(groupName, req.reason))
            } else {
                eventBus.emit(NimbusEvent.MaintenanceDisabled(groupName))
            }
        }
        call.respond(buildStatusResponse(proxySyncManager))
    }

    // PUT /api/maintenance/groups/{name} — Update group maintenance config
    put("/api/maintenance/groups/{name}") {
        val groupName = call.parameters["name"]!!
        val req = call.receive<GroupMaintenanceUpdateRequest>()
        if (req.kickMessage != null) {
            proxySyncManager.updateGroupMaintenanceConfig(groupName, req.kickMessage)
        }
        call.respond(buildStatusResponse(proxySyncManager))
    }

    // POST /api/maintenance/whitelist — Add to whitelist
    post("/api/maintenance/whitelist") {
        val req = call.receive<MaintenanceWhitelistRequest>()
        val added = proxySyncManager.addToMaintenanceWhitelist(req.entry)
        val msg = if (added) "Added '${req.entry}' to maintenance whitelist" else "'${req.entry}' is already whitelisted"
        call.respond(ApiMessage(true, msg))
    }

    // DELETE /api/maintenance/whitelist — Remove from whitelist
    delete("/api/maintenance/whitelist") {
        val req = call.receive<MaintenanceWhitelistRequest>()
        val removed = proxySyncManager.removeFromMaintenanceWhitelist(req.entry)
        val msg = if (removed) "Removed '${req.entry}' from maintenance whitelist" else "'${req.entry}' is not whitelisted"
        call.respond(ApiMessage(true, msg))
    }
}

private fun buildStatusResponse(manager: ProxySyncManager): MaintenanceStatusResponse {
    val groupStates = manager.getAllGroupMaintenanceStates().mapValues { (_, state) ->
        GroupMaintenanceResponse(state.enabled, state.kickMessage)
    }
    return MaintenanceStatusResponse(
        global = GlobalMaintenanceResponse(
            enabled = manager.globalMaintenanceEnabled,
            motdLine1 = manager.globalMotdLine1,
            motdLine2 = manager.globalMotdLine2,
            protocolText = manager.globalProtocolText,
            kickMessage = manager.globalKickMessage,
            whitelist = manager.getMaintenanceWhitelist().toList()
        ),
        groups = groupStates
    )
}
