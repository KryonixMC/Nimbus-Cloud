package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.module.ModuleManager
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

fun Route.moduleRoutes(moduleManager: ModuleManager) {

    route("/api/modules") {

        // List all loaded modules
        get {
            val loaded = moduleManager.getModules().map {
                ModuleResponse(
                    id = it.id,
                    name = it.name,
                    version = it.version,
                    description = it.description,
                    status = "active"
                )
            }
            val available = moduleManager.discoverAvailable()
            val loadedIds = loaded.map { it.id }.toSet()
            val notInstalled = available.filter { it.id !in loadedIds }.map {
                ModuleResponse(
                    id = it.id,
                    name = it.name,
                    version = null,
                    description = it.description,
                    status = "available"
                )
            }
            call.respond(ModulesListResponse(loaded + notInstalled))
        }

        // Install a module
        post("/install/{id}") {
            val id = call.parameters["id"]!!
            when (moduleManager.install(id)) {
                ModuleManager.InstallResult.INSTALLED ->
                    call.respond(ModuleActionResponse(true, "Module '$id' installed — restart to activate"))
                ModuleManager.InstallResult.ALREADY_INSTALLED ->
                    call.respond(HttpStatusCode.Conflict, ModuleActionResponse(false, "Module '$id' is already installed"))
                ModuleManager.InstallResult.NOT_FOUND ->
                    call.respond(HttpStatusCode.NotFound, ModuleActionResponse(false, "Module '$id' not found"))
            }
        }

        // Uninstall a module
        post("/uninstall/{id}") {
            val id = call.parameters["id"]!!
            if (moduleManager.uninstall(id)) {
                call.respond(ModuleActionResponse(true, "Module '$id' uninstalled — restart to take effect"))
            } else {
                call.respond(HttpStatusCode.NotFound, ModuleActionResponse(false, "Module '$id' is not installed"))
            }
        }
    }
}

@Serializable
data class ModuleResponse(
    val id: String,
    val name: String,
    val version: String?,
    val description: String,
    val status: String // "active" or "available"
)

@Serializable
data class ModulesListResponse(val modules: List<ModuleResponse>)

@Serializable
data class ModuleActionResponse(val success: Boolean, val message: String)
