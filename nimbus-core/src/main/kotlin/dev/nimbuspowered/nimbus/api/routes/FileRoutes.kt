package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.api.*
import dev.nimbuspowered.nimbus.api.ApiError
import dev.nimbuspowered.nimbus.api.apiError
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.*

/**
 * File management API for templates, services, and groups directories.
 *
 * Scopes:
 *  - templates: full read/write (mods, plugins, configs, worlds)
 *  - services:  full read/write (live server files, static services persist changes)
 *  - groups:    read-only (TOML configs — editing via Group CRUD API)
 *
 * Security:
 *  - Path traversal blocked (no ".." components, resolved path must stay within scope root)
 *  - Max upload size enforced
 */
fun Route.fileRoutes(
    scopeRoots: Map<String, Path>,
    readOnlyScopes: Set<String>,
    maxUploadBytes: Long
) {
    route("/api/files/{scope}") {

        // GET /api/files/{scope} — List root of scope
        // GET /api/files/{scope}/{path...} — List directory or read file
        get("{path...}") {
            if (!call.requirePermission("nimbus.dashboard.services.edit_config")) return@get
            val (scopeName, resolvedPath) = resolveScopePath(call, scopeRoots)
                ?: return@get

            if (!resolvedPath.exists()) {
                return@get call.respond(HttpStatusCode.NotFound, apiError("Path not found", ApiError.PATH_NOT_FOUND))
            }

            if (resolvedPath.isDirectory()) {
                val entries = Files.list(resolvedPath).use { stream ->
                    stream.map { entry ->
                        FileEntry(
                            name = entry.fileName.toString(),
                            path = scopeRoots[scopeName]!!.relativize(entry).toString().replace('\\', '/'),
                            isDirectory = entry.isDirectory(),
                            size = if (entry.isRegularFile()) entry.fileSize() else 0,
                            lastModified = Files.getLastModifiedTime(entry).toInstant().toString()
                        )
                    }.sorted(Comparator.comparing<FileEntry, Boolean> { !it.isDirectory }.thenComparing { it.name })
                        .toList()
                }
                val relativePath = scopeRoots[scopeName]!!.relativize(resolvedPath).toString().replace('\\', '/')
                call.respond(FileListResponse(scopeName, relativePath.ifEmpty { "/" }, entries, entries.size))
            } else {
                // Binary files: serve as download. Text files: return content as JSON.
                if (isBinaryFile(resolvedPath)) {
                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${resolvedPath.fileName}\"")
                    call.respondFile(resolvedPath.toFile())
                } else {
                    val content = resolvedPath.readText()
                    val relativePath = scopeRoots[scopeName]!!.relativize(resolvedPath).toString().replace('\\', '/')
                    call.respond(FileContentResponse(scopeName, relativePath, content, resolvedPath.fileSize()))
                }
            }
        }

        get {
            val scopeName = call.parameters["scope"]!!
            val root = scopeRoots[scopeName]
                ?: return@get call.respond(HttpStatusCode.BadRequest, apiError("Invalid scope '$scopeName'. Valid: ${scopeRoots.keys.joinToString()}", ApiError.INVALID_SCOPE))

            if (!root.exists()) {
                return@get call.respond(FileListResponse(scopeName, "/", emptyList(), 0))
            }

            val entries = Files.list(root).use { stream ->
                stream.map { entry ->
                    FileEntry(
                        name = entry.fileName.toString(),
                        path = root.relativize(entry).toString().replace('\\', '/'),
                        isDirectory = entry.isDirectory(),
                        size = if (entry.isRegularFile()) entry.fileSize() else 0,
                        lastModified = Files.getLastModifiedTime(entry).toInstant().toString()
                    )
                }.sorted(Comparator.comparing<FileEntry, Boolean> { !it.isDirectory }.thenComparing { it.name })
                    .toList()
            }
            call.respond(FileListResponse(scopeName, "/", entries, entries.size))
        }

        // PUT /api/files/{scope}/{path...} — Write text content to file
        put("{path...}") {
            if (!call.requirePermission("nimbus.dashboard.services.edit_config")) return@put
            val (scopeName, resolvedPath) = resolveScopePath(call, scopeRoots)
                ?: return@put

            if (scopeName in readOnlyScopes) {
                return@put call.respond(HttpStatusCode.Forbidden, apiError("Scope '$scopeName' is read-only", ApiError.READ_ONLY))
            }

            val request = call.receive<FileWriteRequest>()

            // Ensure parent directory exists
            resolvedPath.parent?.createDirectories()
            resolvedPath.writeText(request.content)

            call.respond(ApiMessage(true, "File written successfully"))
        }

        // POST /api/files/{scope}/{path...}?mkdir — Create directory
        // POST /api/files/{scope}/{path...} — Multipart file upload
        post("{path...}") {
            if (!call.requirePermission("nimbus.dashboard.services.edit_config")) return@post
            val (scopeName, resolvedPath) = resolveScopePath(call, scopeRoots)
                ?: return@post

            if (scopeName in readOnlyScopes) {
                return@post call.respond(HttpStatusCode.Forbidden, apiError("Scope '$scopeName' is read-only", ApiError.READ_ONLY))
            }

            // mkdir operation
            if (call.request.queryParameters.contains("mkdir")) {
                resolvedPath.createDirectories()
                return@post call.respond(HttpStatusCode.Created, ApiMessage(true, "Directory created"))
            }

            // Multipart file upload
            val contentType = call.request.contentType()
            if (!contentType.match(ContentType.MultiPart.FormData)) {
                return@post call.respond(HttpStatusCode.BadRequest, apiError("Expected multipart/form-data for file upload", ApiError.VALIDATION_FAILED))
            }

            val multipart = call.receiveMultipart()
            var uploaded = false
            var uploadedSize = 0L
            var uploadedPath = ""

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        val fileName = part.originalFileName ?: resolvedPath.fileName.toString()
                        val targetPath = if (resolvedPath.isDirectory() || !resolvedPath.exists() && resolvedPath.toString().endsWith("/")) {
                            resolvedPath.createDirectories()
                            resolvedPath.resolve(fileName)
                        } else {
                            resolvedPath.parent?.createDirectories()
                            resolvedPath
                        }

                        // Check size limit
                        val bytes = part.streamProvider().readBytes()
                        if (bytes.size > maxUploadBytes) {
                            part.dispose()
                            call.respond(HttpStatusCode.PayloadTooLarge,
                                apiError("File too large (${bytes.size} bytes). Max: $maxUploadBytes bytes", ApiError.PAYLOAD_TOO_LARGE))
                            return@forEachPart
                        }

                        targetPath.writeBytes(bytes)
                        uploadedSize = bytes.size.toLong()
                        uploadedPath = scopeRoots[scopeName]!!.relativize(targetPath).toString().replace('\\', '/')
                        uploaded = true
                    }
                    else -> {}
                }
                part.dispose()
            }

            if (uploaded) {
                call.respond(HttpStatusCode.Created, FileUploadResponse(true, uploadedPath, uploadedSize))
            } else {
                call.respond(HttpStatusCode.BadRequest, apiError("No file part found in request", ApiError.VALIDATION_FAILED))
            }
        }

        // DELETE /api/files/{scope}/{path...} — Delete file or empty directory
        delete("{path...}") {
            if (!call.requirePermission("nimbus.dashboard.services.edit_config")) return@delete
            val (scopeName, resolvedPath) = resolveScopePath(call, scopeRoots)
                ?: return@delete

            if (scopeName in readOnlyScopes) {
                return@delete call.respond(HttpStatusCode.Forbidden, apiError("Scope '$scopeName' is read-only", ApiError.READ_ONLY))
            }

            if (!resolvedPath.exists()) {
                return@delete call.respond(HttpStatusCode.NotFound, apiError("Path not found", ApiError.PATH_NOT_FOUND))
            }

            // Prevent deleting the scope root itself
            if (resolvedPath == scopeRoots[scopeName]) {
                return@delete call.respond(HttpStatusCode.Forbidden, apiError("Cannot delete scope root directory", ApiError.FORBIDDEN))
            }

            if (resolvedPath.isDirectory()) {
                // Recursive delete for directories
                resolvedPath.toFile().deleteRecursively()
            } else {
                resolvedPath.deleteIfExists()
            }

            call.respond(ApiMessage(true, "Deleted successfully"))
        }
    }
}

/**
 * Resolves and validates the scope + path from the request.
 * Returns null if the request was already responded to with an error.
 */
private suspend fun resolveScopePath(
    call: io.ktor.server.application.ApplicationCall,
    scopeRoots: Map<String, Path>
): Pair<String, Path>? {
    val scopeName = call.parameters["scope"]!!
    val root = scopeRoots[scopeName]
    if (root == null) {
        call.respond(HttpStatusCode.BadRequest, apiError("Invalid scope '$scopeName'. Valid: ${scopeRoots.keys.joinToString()}", ApiError.INVALID_SCOPE))
        return null
    }

    val pathParam = call.parameters.getAll("path")?.joinToString("/") ?: ""

    // Block path traversal
    if (pathParam.contains("..")) {
        call.respond(HttpStatusCode.Forbidden, apiError("Path traversal not allowed", ApiError.PATH_TRAVERSAL))
        return null
    }

    val resolved = root.resolve(pathParam).normalize()

    // Ensure resolved path is still within the scope root
    if (!resolved.startsWith(root)) {
        call.respond(HttpStatusCode.Forbidden, apiError("Path outside of scope", ApiError.PATH_TRAVERSAL))
        return null
    }

    // Additionally resolve symlinks to prevent symlink-based path traversal
    if (resolved.exists()) {
        val realPath = resolved.toRealPath()
        val realRoot = root.toRealPath()
        if (!realPath.startsWith(realRoot)) {
            call.respond(HttpStatusCode.Forbidden, apiError("Path outside of scope", ApiError.PATH_TRAVERSAL))
            return null
        }
    }

    return scopeName to resolved
}

/**
 * Simple heuristic to detect binary files by extension.
 */
private fun isBinaryFile(path: Path): Boolean {
    val binaryExtensions = setOf(
        "jar", "zip", "gz", "tar", "rar", "7z",
        "png", "jpg", "jpeg", "gif", "bmp", "ico",
        "dat", "mca", "nbt", "schematic", "schem",
        "db", "sqlite", "class", "so", "dll", "exe"
    )
    val ext = path.extension.lowercase()
    return ext in binaryExtensions
}
