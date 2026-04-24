package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.api.*
import dev.nimbuspowered.nimbus.config.ConfigLoader
import dev.nimbuspowered.nimbus.config.NimbusConfig
import dev.nimbuspowered.nimbus.config.ReloadRegistry
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.service.ServiceManager
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.time.Instant

fun Route.systemRoutes(
    config: NimbusConfig,
    groupManager: GroupManager,
    groupsDir: Path,
    serviceManager: ServiceManager,
    eventBus: EventBus,
    scope: CoroutineScope,
    startedAt: Instant
) {
    // POST /api/reload — Hot-reload live-reloadable config sections. Returns a
    // structured ReloadReport that names every section, its scope, whether the
    // current reload applied it, and which sections would require a full
    // controller restart to take effect. Backwards-compatible: ReloadReport
    // retains the legacy `success`/`groupsLoaded`/`message` fields.
    post("/api/reload") {
        if (!call.requirePermission("nimbus.dashboard.reload")) return@post
        try {
            val configs = ConfigLoader.reloadGroupConfigs(groupsDir, config.controller.strictConfig)
            groupManager.reloadGroups(configs)
            eventBus.emit(NimbusEvent.ConfigReloaded(configs.size))
            call.respond(ReloadRegistry.buildReport(
                success = true,
                groupsLoaded = configs.size,
                appliedSections = setOf("groups"),
                message = "Reloaded ${configs.size} group config(s)"
            ))
        } catch (e: Exception) {
            call.respond(ReloadRegistry.buildReport(
                success = false,
                groupsLoaded = 0,
                appliedSections = emptySet(),
                message = "Reload failed: ${e.message}"
            ))
        }
    }

    // POST /api/shutdown — Gracefully stop the controller. Essential when the
    // controller is running in daemon mode (no TTY, no console REPL) — without
    // this, the only way to stop is SIGKILL which orphans backends.
    //
    // We respond 202 Accepted immediately, then spawn a background coroutine to
    // call System.exit(0) after a short delay so the response can be flushed.
    // The JVM shutdown hook then runs the normal graceful cleanup path.
    post("/api/shutdown") {
        if (!call.requirePermission("nimbus.dashboard.shutdown")) return@post
        call.respond(ApiMessage(true, "Shutdown initiated"))
        scope.launch {
            kotlinx.coroutines.delay(250)
            System.exit(0)
        }
    }
}
