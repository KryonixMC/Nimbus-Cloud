package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.api.ApiErrors
import dev.nimbuspowered.nimbus.api.CreateGroupRequest
import dev.nimbuspowered.nimbus.api.apiError
import dev.nimbuspowered.nimbus.config.GroupType
import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.template.ModpackInstaller
import dev.nimbuspowered.nimbus.template.SoftwareResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class ModpackResolveRequest(
    val source: String
)

@Serializable
data class ModpackInfoResponse(
    val name: String,
    val version: String,
    val mcVersion: String,
    val modloader: String,
    val modloaderVersion: String,
    val totalFiles: Int,
    val serverFiles: Int
)

@Serializable
data class ModpackImportRequest(
    val source: String,
    val groupName: String,
    val type: String = "DYNAMIC",
    val memory: String = "2G",
    val minInstances: Int = 1,
    val maxInstances: Int = 2
)

@Serializable
data class ModpackImportResponse(
    val success: Boolean,
    val message: String,
    val groupName: String = "",
    val filesDownloaded: Int = 0,
    val filesFailed: Int = 0
)

fun Route.modpackRoutes(
    softwareResolver: SoftwareResolver,
    groupManager: GroupManager,
    serviceManager: ServiceManager,
    groupsDir: Path,
    templatesDir: Path
) {
    val installer = ModpackInstaller(HttpClient(CIO))

    route("/api/modpacks") {

        // POST /api/modpacks/resolve — Inspect a modpack without importing
        post("resolve") {
            val request = call.receive<ModpackResolveRequest>()
            val downloadDir = templatesDir.resolve(".modpack-cache")
            Files.createDirectories(downloadDir)

            val mrpackPath = installer.resolve(request.source, downloadDir)
                ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Could not resolve modpack '${request.source}'", ApiErrors.NOT_FOUND))

            val index = installer.parseIndex(mrpackPath)
                ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid .mrpack file", ApiErrors.VALIDATION_FAILED))

            val info = installer.getInfo(index)
            call.respond(ModpackInfoResponse(
                name = info.name,
                version = info.version,
                mcVersion = info.mcVersion,
                modloader = info.modloader.name,
                modloaderVersion = info.modloaderVersion,
                totalFiles = info.totalFiles,
                serverFiles = info.serverFiles
            ))
        }

        // POST /api/modpacks/import — Full modpack import
        post("import") {
            val request = call.receive<ModpackImportRequest>()

            if (request.groupName.isBlank() || !request.groupName.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
                return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid group name", ApiErrors.VALIDATION_FAILED))
            }
            if (groupManager.getGroup(request.groupName) != null) {
                return@post call.respond(HttpStatusCode.Conflict, apiError("Group '${request.groupName}' already exists", ApiErrors.GROUP_ALREADY_EXISTS))
            }

            val downloadDir = templatesDir.resolve(".modpack-cache")
            Files.createDirectories(downloadDir)

            // Resolve .mrpack
            val mrpackPath = installer.resolve(request.source, downloadDir)
                ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Could not resolve modpack '${request.source}'", ApiErrors.NOT_FOUND))

            val index = installer.parseIndex(mrpackPath)
                ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid .mrpack file", ApiErrors.VALIDATION_FAILED))

            val info = installer.getInfo(index)
            val templateName = request.groupName.lowercase()
            val templateDir = templatesDir.resolve(templateName)
            Files.createDirectories(templateDir)

            // Download modloader JAR
            softwareResolver.ensureJarAvailable(info.modloader, info.mcVersion, templateDir, info.modloaderVersion)

            // Install mod files
            val result = installer.installFiles(index, templateDir) { _, _, _ -> }

            // Extract overrides
            installer.extractOverrides(mrpackPath, templateDir)

            // Install proxy forwarding mods
            when (info.modloader) {
                ServerSoftware.FABRIC -> softwareResolver.ensureFabricProxyMod(templateDir, info.mcVersion)
                ServerSoftware.FORGE, ServerSoftware.NEOFORGE -> softwareResolver.ensureForwardingMod(info.modloader, info.mcVersion, templateDir)
                else -> {}
            }

            // Auto-accept EULA
            templateDir.resolve("eula.txt").toFile().writeText("eula=true\n")

            // Create group via existing group creation logic
            val groupRequest = CreateGroupRequest(
                name = request.groupName,
                type = request.type,
                template = templateName,
                software = info.modloader.name,
                version = info.mcVersion,
                modloaderVersion = info.modloaderVersion,
                memory = request.memory,
                minInstances = request.minInstances,
                maxInstances = request.maxInstances
            )
            val groupType = try { GroupType.valueOf(request.type.uppercase()) } catch (_: Exception) { GroupType.DYNAMIC }
            val software = info.modloader
            val toml = buildGroupToml(groupRequest, groupType, software)
            groupsDir.resolve("${request.groupName.lowercase()}.toml").toFile().writeText(toml)
            val groupConfig = buildGroupConfig(groupRequest, groupType, software)
            groupManager.reloadGroups(
                groupManager.getAllGroups().map { it.config } + groupConfig
            )

            call.respond(HttpStatusCode.Created, ModpackImportResponse(
                success = result.success,
                message = if (result.success) "Modpack '${info.name}' imported as group '${request.groupName}'"
                         else "Import completed with ${result.filesFailed} failed downloads",
                groupName = request.groupName,
                filesDownloaded = result.filesDownloaded,
                filesFailed = result.filesFailed
            ))
        }
    }
}
