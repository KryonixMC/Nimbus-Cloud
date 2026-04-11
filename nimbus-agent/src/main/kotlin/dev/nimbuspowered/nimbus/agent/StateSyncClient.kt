package dev.nimbuspowered.nimbus.agent

import dev.nimbuspowered.nimbus.protocol.StateFileEntry
import dev.nimbuspowered.nimbus.protocol.StateManifest
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import javax.net.ssl.X509TrustManager
import kotlin.io.path.exists

/**
 * Agent-side client for the controller's state sync endpoints.
 *
 * On `pull`: fetches the controller's manifest and downloads each file that
 * differs from the local workdir. Also deletes local files not present in the
 * controller's manifest (reconciling the local copy to match canonical).
 *
 * On `push`: builds a manifest of the local workdir, computes the delta against
 * the controller's manifest, and POSTs a single multipart request containing the
 * target manifest + the changed files' bytes.
 *
 * Excludes are honored in both directions — files matching any exclude pattern
 * are neither uploaded nor deleted during reconcile.
 */
class StateSyncClient(
    private val controllerBaseUrl: String,
    private val token: String,
    trustManager: X509TrustManager? = null
) {
    private val logger = LoggerFactory.getLogger(StateSyncClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(CIO) {
        val tm = trustManager
        engine {
            https {
                if (tm != null) this.trustManager = tm
            }
            // Large uploads for big worlds — effectively no request timeout
            requestTimeout = 0
        }
        install(HttpTimeout) {
            // "infinite" — HttpTimeout rejects 0, so use HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
    }

    /**
     * Pulls the canonical state for [serviceName] from the controller into [workDir].
     * Returns true if anything was pulled (canonical exists), false if the controller
     * has no canonical yet (first start — caller should fall back to template copy).
     */
    fun pull(serviceName: String, workDir: Path, excludes: List<String>): Boolean {
        val ctrlManifest = fetchManifest(serviceName) ?: return false
        if (ctrlManifest.files.isEmpty()) {
            logger.info("State sync: controller has empty manifest for '{}' — no pull needed", serviceName)
            return false
        }

        Files.createDirectories(workDir)
        val matchers = excludes.map { compileGlob(it) }
        val local = scanLocalManifest(workDir, matchers)

        var downloaded = 0
        var skipped = 0
        for ((relPath, entry) in ctrlManifest.files) {
            if (matchers.any { it(relPath) }) continue
            val localEntry = local.files[relPath]
            if (localEntry != null && localEntry.sha256.equals(entry.sha256, ignoreCase = true)) {
                skipped += 1
                continue
            }
            downloadFile(serviceName, relPath, workDir.resolve(relPath))
            downloaded += 1
        }

        // Reconcile: delete local files not in the controller's manifest (unless excluded)
        val wanted = ctrlManifest.files.keys
        var deleted = 0
        for (relPath in local.files.keys) {
            if (matchers.any { it(relPath) }) continue
            if (relPath in wanted) continue
            Files.deleteIfExists(workDir.resolve(relPath))
            deleted += 1
        }

        logger.info("State sync pull '{}': {} downloaded, {} skipped (up-to-date), {} deleted locally",
            serviceName, downloaded, skipped, deleted)
        return true
    }

    /**
     * Pushes the current contents of [workDir] to the controller, reconciling against
     * the controller's canonical copy. Only changed/new files are uploaded.
     */
    fun push(serviceName: String, workDir: Path, excludes: List<String>) {
        if (!workDir.exists()) {
            logger.warn("State sync push: workDir does not exist: {}", workDir)
            return
        }
        val matchers = excludes.map { compileGlob(it) }
        val localManifest = scanLocalManifest(workDir, matchers)
        val ctrlManifest = fetchManifest(serviceName) ?: StateManifest()

        val toUpload = localManifest.files.filter { (path, entry) ->
            val remote = ctrlManifest.files[path]
            remote == null || !remote.sha256.equals(entry.sha256, ignoreCase = true)
        }.keys

        logger.info("State sync push '{}': {} files total, {} to upload, {} unchanged",
            serviceName, localManifest.files.size, toUpload.size, localManifest.files.size - toUpload.size)

        runBlocking {
            // Stream each file from disk via ChannelProvider instead of loading into
            // memory — world syncs can be multiple GB and a readAllBytes approach would
            // OOM the agent heap on large single files.
            val parts = formData {
                append("manifest", json.encodeToString(StateManifest.serializer(), localManifest))
                for (relPath in toUpload) {
                    val file = workDir.resolve(relPath)
                    if (!file.exists() || !Files.isRegularFile(file)) continue
                    val size = Files.size(file)
                    append(
                        key = "file:$relPath",
                        value = ChannelProvider(size = size) {
                            Files.newInputStream(file).toByteReadChannel()
                        },
                        headers = Headers.build {
                            append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                            append(HttpHeaders.ContentDisposition, "filename=\"${relPath.substringAfterLast('/')}\"")
                        }
                    )
                }
            }
            val response: HttpResponse = client.submitFormWithBinaryData(
                url = "$controllerBaseUrl/api/services/$serviceName/state/sync?token=$token",
                formData = parts
            )
            if (!response.status.isSuccess()) {
                throw RuntimeException("state sync push failed: HTTP ${response.status.value} ${response.bodyAsText().take(200)}")
            }
        }
    }

    fun close() {
        client.close()
    }

    // ── HTTP helpers ────────────────────────────────────────

    private fun fetchManifest(serviceName: String): StateManifest? = runBlocking {
        val url = "$controllerBaseUrl/api/services/$serviceName/state/manifest?token=$token"
        val response: HttpResponse = client.get(url)
        when (response.status.value) {
            200 -> json.decodeFromString(StateManifest.serializer(), response.bodyAsText())
            404 -> null
            else -> {
                logger.warn("Controller returned HTTP {} for manifest of '{}'", response.status.value, serviceName)
                null
            }
        }
    }

    private fun downloadFile(serviceName: String, relPath: String, target: Path) = runBlocking {
        val encoded = relPath.split('/').joinToString("/") { java.net.URLEncoder.encode(it, Charsets.UTF_8) }
        val url = "$controllerBaseUrl/api/services/$serviceName/state/file/$encoded?token=$token"
        target.parent?.let { Files.createDirectories(it) }
        val tmp = target.resolveSibling(target.fileName.toString() + ".sync-tmp")
        try {
            client.prepareGet(url).execute { response ->
                if (!response.status.isSuccess()) {
                    throw RuntimeException("file download failed: HTTP ${response.status.value} for $relPath")
                }
                val channel: ByteReadChannel = response.bodyAsChannel()
                Files.newOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    val input = channel.toInputStream()
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw e
        }
    }

    // ── local manifest building ─────────────────────────────

    private fun scanLocalManifest(root: Path, matchers: List<(String) -> Boolean>): StateManifest {
        if (!root.exists() || !Files.isDirectory(root)) return StateManifest()
        val rootAbs = root.toAbsolutePath().normalize()
        val files = mutableMapOf<String, StateFileEntry>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val rel = rootAbs.relativize(file.toAbsolutePath()).toString().replace('\\', '/')
                if (matchers.any { it(rel) }) return FileVisitResult.CONTINUE
                files[rel] = StateFileEntry(sha256(file), attrs.size())
                return FileVisitResult.CONTINUE
            }
        })
        return StateManifest(files)
    }

    private fun sha256(file: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun compileGlob(pattern: String): (String) -> Boolean {
        if (pattern.isBlank()) return { _: String -> false }
        val trimmed = pattern.trimEnd('/')
        val isDir = pattern.endsWith('/')
        val pathSlash = trimmed.contains('/')
        val fs = FileSystems.getDefault()

        return if (pathSlash || isDir) {
            val pathMatcher = fs.getPathMatcher("glob:$trimmed")
            val dirPrefix = "$trimmed/"
            val fn: (String) -> Boolean = { rel ->
                val relPath: Path = java.nio.file.Paths.get(rel)
                pathMatcher.matches(relPath) || rel.startsWith(dirPrefix)
            }
            fn
        } else {
            val pathMatcher = fs.getPathMatcher("glob:$trimmed")
            val fn: (String) -> Boolean = { rel ->
                val relPath: Path = java.nio.file.Paths.get(rel)
                var matched = false
                for (i in 0 until relPath.nameCount) {
                    if (pathMatcher.matches(relPath.getName(i))) {
                        matched = true
                        break
                    }
                }
                matched
            }
            fn
        }
    }
}
