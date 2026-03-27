package dev.nimbus.api.routes

import dev.nimbus.api.*
import dev.nimbus.config.ConfigLoader
import dev.nimbus.config.NimbusConfig
import dev.nimbus.group.GroupManager
import dev.nimbus.service.ServiceManager
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.time.Instant

@OptIn(DelicateCoroutinesApi::class)
fun Route.systemRoutes(
    config: NimbusConfig,
    groupManager: GroupManager,
    groupsDir: Path,
    serviceManager: ServiceManager,
    startedAt: Instant
) {
    // POST /api/reload — Hot-reload group configs
    post("/api/reload") {
        try {
            val configs = ConfigLoader.reloadGroupConfigs(groupsDir)
            groupManager.reloadGroups(configs)
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

    // POST /api/shutdown — Graceful shutdown
    post("/api/shutdown") {
        call.respond(ApiMessage(true, "Shutdown initiated — stopping all services..."))

        // Run shutdown in background so the response is sent first
        GlobalScope.launch {
            delay(500)
            serviceManager.stopAll()
            Runtime.getRuntime().exit(0)
        }
    }
}
