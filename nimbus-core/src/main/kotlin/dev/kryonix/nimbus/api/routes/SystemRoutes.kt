package dev.kryonix.nimbus.api.routes

import dev.kryonix.nimbus.api.*
import dev.kryonix.nimbus.config.ConfigLoader
import dev.kryonix.nimbus.config.NimbusConfig
import dev.kryonix.nimbus.event.EventBus
import dev.kryonix.nimbus.event.NimbusEvent
import dev.kryonix.nimbus.group.GroupManager
import dev.kryonix.nimbus.service.ServiceManager
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
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
}
