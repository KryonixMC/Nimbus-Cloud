package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.api.*
import dev.nimbuspowered.nimbus.config.ConfigLoader
import dev.nimbuspowered.nimbus.config.NimbusConfig
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
    // POST /api/reload — Hot-reload group configs
    post("/api/reload") {
        try {
            val configs = ConfigLoader.reloadGroupConfigs(groupsDir)
            groupManager.reloadGroups(configs)
            eventBus.emit(NimbusEvent.ConfigReloaded(configs.size))
            call.respond(ReloadResponse(
                success = true,
                groupsLoaded = configs.size,
                message = "Reloaded ${configs.size} group config(s)"
            ))
        } catch (e: Exception) {
            call.respond(ReloadResponse(
                success = false,
                groupsLoaded = 0,
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
        call.respond(ApiMessage(true, "Shutdown initiated"))
        scope.launch {
            kotlinx.coroutines.delay(250)
            System.exit(0)
        }
    }
}
