package dev.kryonix.nimbus.api.routes

import dev.kryonix.nimbus.NimbusVersion
import dev.kryonix.nimbus.api.*
import dev.kryonix.nimbus.event.EventBus
import dev.kryonix.nimbus.event.NimbusEvent
import dev.kryonix.nimbus.proxy.ProxySyncManager
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.proxySyncRoutes(proxySyncManager: ProxySyncManager, eventBus: EventBus) {

    // GET /api/proxy/config — Full proxy sync config (includes maintenance state)
    get("/api/proxy/config") {
        val cfg = proxySyncManager.getConfig()
        val maintenanceGroups = proxySyncManager.getAllGroupMaintenanceStates()
            .filter { it.value.enabled }
            .mapValues { it.value.kickMessage }
        call.respond(ProxySyncResponse(
            tablist = TabListResponse(cfg.tabList.header, cfg.tabList.footer, cfg.tabList.playerFormat, cfg.tabList.updateInterval),
            motd = MotdResponse(cfg.motd.line1, cfg.motd.line2, cfg.motd.maxPlayers, cfg.motd.playerCountOffset),
            chat = ChatResponse(cfg.chat.format, cfg.chat.enabled),
            maintenance = ProxyMaintenanceResponse(
                globalEnabled = proxySyncManager.globalMaintenanceEnabled,
                motdLine1 = proxySyncManager.globalMotdLine1,
                motdLine2 = proxySyncManager.globalMotdLine2,
                protocolText = proxySyncManager.globalProtocolText,
                kickMessage = proxySyncManager.globalKickMessage,
                whitelist = proxySyncManager.getMaintenanceWhitelist().toList(),
                groups = maintenanceGroups
            ),
            version = NimbusVersion.version
        ))
    }

    // GET /api/proxy/tablist — Tab list config
    get("/api/proxy/tablist") {
        val tab = proxySyncManager.getConfig().tabList
        call.respond(TabListResponse(tab.header, tab.footer, tab.playerFormat, tab.updateInterval))
    }

    // PUT /api/proxy/tablist — Update tab list config
    put("/api/proxy/tablist") {
        val req = call.receive<TabListUpdateRequest>()
        proxySyncManager.updateTabList(req.header, req.footer, req.playerFormat, req.updateInterval)
        val tab = proxySyncManager.getConfig().tabList
        eventBus.emit(NimbusEvent.TabListUpdated(tab.header, tab.footer, tab.playerFormat, tab.updateInterval))
        call.respond(TabListResponse(tab.header, tab.footer, tab.playerFormat, tab.updateInterval))
    }

    // GET /api/proxy/motd — MOTD config
    get("/api/proxy/motd") {
        val motd = proxySyncManager.getConfig().motd
        call.respond(MotdResponse(motd.line1, motd.line2, motd.maxPlayers, motd.playerCountOffset))
    }

    // PUT /api/proxy/motd — Update MOTD config
    put("/api/proxy/motd") {
        val req = call.receive<MotdUpdateRequest>()
        proxySyncManager.updateMotd(req.line1, req.line2, req.maxPlayers, req.playerCountOffset)
        val motd = proxySyncManager.getConfig().motd
        eventBus.emit(NimbusEvent.MotdUpdated(motd.line1, motd.line2, motd.maxPlayers, motd.playerCountOffset))
        call.respond(MotdResponse(motd.line1, motd.line2, motd.maxPlayers, motd.playerCountOffset))
    }

    // GET /api/proxy/chat — Chat config
    get("/api/proxy/chat") {
        val chat = proxySyncManager.getConfig().chat
        call.respond(ChatResponse(chat.format, chat.enabled))
    }

    // PUT /api/proxy/chat — Update chat config
    put("/api/proxy/chat") {
        val req = call.receive<ChatUpdateRequest>()
        proxySyncManager.updateChat(req.format, req.enabled)
        val chat = proxySyncManager.getConfig().chat
        eventBus.emit(NimbusEvent.ChatFormatUpdated(chat.format, chat.enabled))
        call.respond(ChatResponse(chat.format, chat.enabled))
    }

    // PUT /api/proxy/tablist/players/{uuid} — Set player tab override
    put("/api/proxy/tablist/players/{uuid}") {
        val uuid = call.parameters["uuid"]!!
        val req = call.receive<PlayerTabFormatRequest>()
        proxySyncManager.setPlayerTabFormat(uuid, req.format)
        eventBus.emit(NimbusEvent.PlayerTabUpdated(uuid, req.format))
        call.respond(ApiMessage(true, "Tab format set for $uuid"))
    }

    // DELETE /api/proxy/tablist/players/{uuid} — Clear player tab override
    delete("/api/proxy/tablist/players/{uuid}") {
        val uuid = call.parameters["uuid"]!!
        proxySyncManager.clearPlayerTabFormat(uuid)
        eventBus.emit(NimbusEvent.PlayerTabUpdated(uuid, null))
        call.respond(ApiMessage(true, "Tab format cleared for $uuid"))
    }

    // GET /api/proxy/tablist/players — All player tab overrides
    get("/api/proxy/tablist/players") {
        val overrides = proxySyncManager.getAllPlayerTabOverrides()
        call.respond(PlayerTabOverridesResponse(overrides, overrides.size))
    }
}
