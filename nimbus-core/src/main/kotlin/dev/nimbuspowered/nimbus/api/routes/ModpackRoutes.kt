package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.api.ApiErrors
import dev.nimbuspowered.nimbus.api.CreateGroupRequest
import dev.nimbuspowered.nimbus.api.apiError
import dev.nimbuspowered.nimbus.config.CurseForgeConfig
import dev.nimbuspowered.nimbus.config.GroupType
import dev.nimbuspowered.nimbus.config.ServerSoftware
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.template.ModpackInstaller
import dev.nimbuspowered.nimbus.template.ModpackSource
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
    val serverFiles: Int,
    val source: String = "MODRINTH"
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
    templatesDir: Path,
    curseForgeConfig: CurseForgeConfig = CurseForgeConfig()
) {
    val installer = ModpackInstaller(HttpClient(CIO), curseForgeConfig.apiKey)
    val maxUploadBytes = 2L * 1024 * 1024 * 1024 // 2 GB for modpack ZIPs

    route("/api/modpacks") {

        // POST /api/modpacks/resolve — Inspect a modpack without importing
        post("resolve") {
            val request = call.receive<ModpackResolveRequest>()
            val downloadDir = templatesDir.resolve(".modpack-cache")
            Files.createDirectories(downloadDir)

            val resolvedPath = installer.resolve(request.source, downloadDir)
                ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Could not resolve modpack '${request.source}'", ApiErrors.MODPACK_NOT_FOUND))

            // Server pack ZIP (CurseForge-style)
            if (installer.isServerPack(resolvedPath)) {
                val info = installer.getServerPackInfo(resolvedPath)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Could not analyze server pack", ApiErrors.MODPACK_INVALID))
                return@post call.respond(ModpackInfoResponse(
                    name = info.name,
                    version = info.version,
                    mcVersion = info.mcVersion,
                    modloader = info.modloader.name,
                    modloaderVersion = info.modloaderVersion,
                    totalFiles = info.totalFiles,
                    serverFiles = info.serverFiles,
                    source = info.source.name
                ))
            }

            // Modrinth .mrpack
            val index = installer.parseIndex(resolvedPath)
                ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid .mrpack file", ApiErrors.MODPACK_INVALID))

            val info = installer.getInfo(index)
            call.respond(ModpackInfoResponse(
                name = info.name,
                version = info.version,
                mcVersion = info.mcVersion,
                modloader = info.modloader.name,
                modloaderVersion = info.modloaderVersion,
                totalFiles = info.totalFiles,
                serverFiles = info.serverFiles,
                source = info.source.name
            ))
        }

        // POST /api/modpacks/import — Full modpack import (Modrinth slug/URL or CurseForge slug/URL)
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

            val resolvedPath = installer.resolve(request.source, downloadDir)
                ?: return@post call.respond(HttpStatusCode.NotFound, apiError("Could not resolve modpack '${request.source}'", ApiErrors.MODPACK_NOT_FOUND))

            val templateName = request.groupName.lowercase()
            val templateDir = templatesDir.resolve(templateName)
            Files.createDirectories(templateDir)

            // Server pack ZIP path
            if (installer.isServerPack(resolvedPath)) {
                return@post handleServerPackImport(
                    call, installer, softwareResolver, groupManager,
                    resolvedPath, request.groupName, templateDir, groupsDir,
                    request.type, request.memory, request.minInstances, request.maxInstances
                )
            }

            // Modrinth .mrpack path
            val index = installer.parseIndex(resolvedPath)
                ?: return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid .mrpack file", ApiErrors.MODPACK_INVALID))

            val info = installer.getInfo(index)

            // Download modloader JAR
            softwareResolver.ensureJarAvailable(info.modloader, info.mcVersion, templateDir, info.modloaderVersion)

            // Install mod files
            val result = installer.installFiles(index, templateDir) { _, _, _ -> }

            // Extract overrides
            installer.extractOverrides(resolvedPath, templateDir)

            // Install proxy forwarding mods
            when (info.modloader) {
                ServerSoftware.FABRIC -> softwareResolver.ensureFabricProxyMod(templateDir, info.mcVersion)
                ServerSoftware.FORGE, ServerSoftware.NEOFORGE -> softwareResolver.ensureForwardingMod(info.modloader, info.mcVersion, templateDir)
                else -> {}
            }

            // Auto-accept EULA
            templateDir.resolve("eula.txt").toFile().writeText("eula=true\n")

            // Create group
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
            val toml = buildGroupToml(groupRequest, groupType, info.modloader)
            groupsDir.resolve("${templateName}.toml").toFile().writeText(toml)
            val groupConfig = buildGroupConfig(groupRequest, groupType, info.modloader)
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

        // POST /api/modpacks/upload?groupName=X&type=STATIC&memory=4G — Upload a server pack ZIP
        // File is sent as raw request body (application/octet-stream) to avoid multipart buffering OOM.
        // Query params: groupName (required), type, memory, minInstances, maxInstances, fileName
        post("upload") {
            val groupName = call.request.queryParameters["groupName"] ?: ""
            val type = call.request.queryParameters["type"] ?: "DYNAMIC"
            val memory = call.request.queryParameters["memory"] ?: "2G"
            val minInstances = call.request.queryParameters["minInstances"]?.toIntOrNull() ?: 1
            val maxInstances = call.request.queryParameters["maxInstances"]?.toIntOrNull() ?: 2
            val fileName = call.request.queryParameters["fileName"] ?: "upload.zip"

            if (groupName.isBlank() || !groupName.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
                return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid group name", ApiErrors.VALIDATION_FAILED))
            }
            if (groupManager.getGroup(groupName) != null) {
                return@post call.respond(HttpStatusCode.Conflict, apiError("Group '$groupName' already exists", ApiErrors.GROUP_ALREADY_EXISTS))
            }

            // Stream request body directly to disk — no memory buffering
            val uploadDir = templatesDir.resolve(".modpack-uploads")
            Files.createDirectories(uploadDir)
            val uploadedZip = uploadDir.resolve(fileName)

            try {
                call.receiveStream().use { input ->
                    java.io.FileOutputStream(uploadedZip.toFile()).use { output ->
                        val buf = ByteArray(65536)
                        var totalWritten = 0L
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            totalWritten += read
                            if (totalWritten > maxUploadBytes) {
                                output.close()
                                Files.deleteIfExists(uploadedZip)
                                return@post call.respond(HttpStatusCode.PayloadTooLarge,
                                    apiError("File too large (max ${maxUploadBytes / 1024 / 1024}MB)", ApiErrors.PAYLOAD_TOO_LARGE))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Files.deleteIfExists(uploadedZip)
                return@post call.respond(HttpStatusCode.InternalServerError,
                    apiError("Upload failed: ${e.message}", ApiErrors.MODPACK_UPLOAD_FAILED))
            }

            val templateName = groupName.lowercase()
            val templateDir = templatesDir.resolve(templateName)
            Files.createDirectories(templateDir)

            // Detect format: server pack ZIP or .mrpack
            if (installer.isServerPack(uploadedZip)) {
                handleServerPackImport(
                    call, installer, softwareResolver, groupManager,
                    uploadedZip, groupName, templateDir, groupsDir,
                    type, memory, minInstances, maxInstances
                )
            } else if (uploadedZip.fileName.toString().endsWith(".mrpack") || installer.parseIndex(uploadedZip) != null) {
                // Treat as .mrpack
                val index = installer.parseIndex(uploadedZip)
                if (index == null) {
                    Files.deleteIfExists(uploadedZip)
                    return@post call.respond(HttpStatusCode.BadRequest, apiError("Invalid modpack file — not a server pack ZIP or .mrpack", ApiErrors.MODPACK_INVALID))
                }
                val info = installer.getInfo(index)

                softwareResolver.ensureJarAvailable(info.modloader, info.mcVersion, templateDir, info.modloaderVersion)
                val result = installer.installFiles(index, templateDir) { _, _, _ -> }
                installer.extractOverrides(uploadedZip, templateDir)

                when (info.modloader) {
                    ServerSoftware.FABRIC -> softwareResolver.ensureFabricProxyMod(templateDir, info.mcVersion)
                    ServerSoftware.FORGE, ServerSoftware.NEOFORGE -> softwareResolver.ensureForwardingMod(info.modloader, info.mcVersion, templateDir)
                    else -> {}
                }

                templateDir.resolve("eula.txt").toFile().writeText("eula=true\n")

                val groupRequest = CreateGroupRequest(
                    name = groupName, type = type, template = templateName,
                    software = info.modloader.name, version = info.mcVersion,
                    modloaderVersion = info.modloaderVersion, memory = memory,
                    minInstances = minInstances, maxInstances = maxInstances
                )
                val groupType = try { GroupType.valueOf(type.uppercase()) } catch (_: Exception) { GroupType.DYNAMIC }
                val toml = buildGroupToml(groupRequest, groupType, info.modloader)
                groupsDir.resolve("${templateName}.toml").toFile().writeText(toml)
                val groupConfig = buildGroupConfig(groupRequest, groupType, info.modloader)
                groupManager.reloadGroups(groupManager.getAllGroups().map { it.config } + groupConfig)

                Files.deleteIfExists(uploadedZip)
                call.respond(HttpStatusCode.Created, ModpackImportResponse(
                    success = result.success,
                    message = if (result.success) "Modpack '${info.name}' uploaded and imported as group '$groupName'"
                             else "Import completed with ${result.filesFailed} failed downloads",
                    groupName = groupName,
                    filesDownloaded = result.filesDownloaded,
                    filesFailed = result.filesFailed
                ))
            } else {
                Files.deleteIfExists(uploadedZip)
                call.respond(HttpStatusCode.BadRequest, apiError("Uploaded file is not a valid server pack ZIP or .mrpack", ApiErrors.MODPACK_INVALID))
            }
        }
    }
}

/**
 * Shared handler for server pack ZIP import (used by both /import and /upload).
 */
private suspend fun handleServerPackImport(
    call: io.ktor.server.routing.RoutingCall,
    installer: ModpackInstaller,
    softwareResolver: SoftwareResolver,
    groupManager: GroupManager,
    zipPath: Path,
    groupName: String,
    templateDir: Path,
    groupsDir: Path,
    type: String,
    memory: String,
    minInstances: Int,
    maxInstances: Int
) {
    val info = installer.getServerPackInfo(zipPath)
    if (info == null) {
        Files.deleteIfExists(zipPath)
        return call.respond(HttpStatusCode.BadRequest, apiError("Could not analyze server pack", ApiErrors.MODPACK_INVALID))
    }

    // Extract all server files to template
    installer.extractServerPack(zipPath, templateDir)

    // Install modloader JAR (the ZIP has the installer but Nimbus needs to run it)
    softwareResolver.ensureJarAvailable(info.modloader, info.mcVersion, templateDir, info.modloaderVersion)

    // Install proxy forwarding mods
    when (info.modloader) {
        ServerSoftware.FABRIC -> softwareResolver.ensureFabricProxyMod(templateDir, info.mcVersion)
        ServerSoftware.FORGE, ServerSoftware.NEOFORGE -> softwareResolver.ensureForwardingMod(info.modloader, info.mcVersion, templateDir)
        else -> {}
    }

    // Auto-accept EULA
    templateDir.resolve("eula.txt").toFile().writeText("eula=true\n")

    // Create group config
    val templateName = groupName.lowercase()
    val groupRequest = CreateGroupRequest(
        name = groupName, type = type, template = templateName,
        software = info.modloader.name, version = info.mcVersion,
        modloaderVersion = info.modloaderVersion, memory = memory,
        minInstances = minInstances, maxInstances = maxInstances
    )
    val groupType = try { GroupType.valueOf(type.uppercase()) } catch (_: Exception) { GroupType.DYNAMIC }
    val toml = buildGroupToml(groupRequest, groupType, info.modloader)
    groupsDir.resolve("${templateName}.toml").toFile().writeText(toml)
    val groupConfig = buildGroupConfig(groupRequest, groupType, info.modloader)
    groupManager.reloadGroups(groupManager.getAllGroups().map { it.config } + groupConfig)

    Files.deleteIfExists(zipPath)
    call.respond(HttpStatusCode.Created, ModpackImportResponse(
        success = true,
        message = "Server pack imported as group '$groupName' (${info.modloader.name} ${info.modloaderVersion}, MC ${info.mcVersion}, ${info.serverFiles} mods)",
        groupName = groupName,
        filesDownloaded = info.serverFiles,
        filesFailed = 0
    ))
}
