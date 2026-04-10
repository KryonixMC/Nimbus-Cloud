package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.NimbusVersion
import dev.nimbuspowered.nimbus.api.ApiErrors
import dev.nimbuspowered.nimbus.api.ChangelogEntry
import dev.nimbuspowered.nimbus.api.ChangelogResponse
import dev.nimbuspowered.nimbus.api.ControllerInfoResponse
import dev.nimbuspowered.nimbus.api.apiError
import dev.nimbuspowered.nimbus.update.UpdateChecker
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.time.Duration
import java.time.Instant

// Cache the changelog for 5 minutes — avoids hammering GitHub raw
private const val CHANGELOG_URL =
    "https://raw.githubusercontent.com/NimbusPowered/Nimbus/main/docs/content/docs/project/changelog.mdx"
private const val CHANGELOG_CACHE_TTL_MS = 5 * 60 * 1000L

@Volatile
private var changelogCache: ChangelogResponse? = null

@Volatile
private var changelogCacheAt: Long = 0

fun Route.controllerInfoRoutes(
    startedAt: Instant,
    updateChecker: UpdateChecker?
) {
    val httpClient = HttpClient(CIO)

    route("/api/controller") {

        // GET /api/controller/info — runtime stats + update check
        get("info") {
            val runtime = Runtime.getRuntime()
            val maxHeap = runtime.maxMemory()
            val allocatedHeap = runtime.totalMemory()
            val freeHeap = runtime.freeMemory()
            val usedHeap = allocatedHeap - freeHeap

            // Update check (non-blocking: skip if no checker or if anything fails)
            var updateAvailable = false
            var latestVersion: String? = null
            var updateType: String? = null
            var releaseUrl: String? = null
            if (updateChecker != null) {
                try {
                    val result = updateChecker.checkForUpdate()
                    if (result != null) {
                        updateAvailable = true
                        latestVersion = result.latestVersion.toString()
                        updateType = result.type.name
                        releaseUrl = result.releaseUrl
                    }
                } catch (_: Exception) {
                    // Silently ignore — the dashboard just won't show an update banner
                }
            }

            call.respond(
                ControllerInfoResponse(
                    version = NimbusVersion.version,
                    startedAt = startedAt.toString(),
                    uptimeSeconds = Duration.between(startedAt, Instant.now()).seconds,
                    jvmMemoryUsedMb = usedHeap / (1024 * 1024),
                    jvmMemoryMaxMb = maxHeap / (1024 * 1024),
                    jvmMemoryAllocatedMb = allocatedHeap / (1024 * 1024),
                    updateAvailable = updateAvailable,
                    latestVersion = latestVersion,
                    updateType = updateType,
                    releaseUrl = releaseUrl
                )
            )
        }

        // GET /api/controller/changelog — parsed changelog from docs/ on GitHub main
        get("changelog") {
            val cached = changelogCache
            if (cached != null && System.currentTimeMillis() - changelogCacheAt < CHANGELOG_CACHE_TTL_MS) {
                return@get call.respond(cached)
            }

            try {
                val response = httpClient.get(CHANGELOG_URL) {
                    header("User-Agent", "Nimbus-Cloud/${NimbusVersion.version}")
                }
                if (response.status != HttpStatusCode.OK) {
                    return@get call.respond(
                        HttpStatusCode.BadGateway,
                        apiError("GitHub returned ${response.status.value} fetching changelog", ApiErrors.INTERNAL_ERROR)
                    )
                }

                val mdx = response.bodyAsText()
                val parsed = parseChangelog(mdx)
                changelogCache = parsed
                changelogCacheAt = System.currentTimeMillis()
                call.respond(parsed)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadGateway,
                    apiError("Failed to fetch changelog: ${e.message}", ApiErrors.INTERNAL_ERROR)
                )
            }
        }
    }
}

/**
 * Splits the changelog MDX into per-version entries.
 *
 * Expected format:
 * ```
 * ## v0.6.0 (April 10, 2026)
 *
 * Short summary.
 *
 * ### Added
 * - ...
 * ```
 */
internal fun parseChangelog(mdx: String): ChangelogResponse {
    // Strip frontmatter (between the first two `---` lines)
    val withoutFrontmatter = if (mdx.startsWith("---")) {
        val end = mdx.indexOf("\n---", 3)
        if (end >= 0) mdx.substring(end + 4).trimStart() else mdx
    } else mdx

    // Strip import statements and MDX component usages at the top
    val cleaned = withoutFrontmatter.lines()
        .dropWhile { it.isBlank() || it.startsWith("import ") || !it.startsWith("## ") && !it.startsWith("# ") }
        .joinToString("\n")

    val headingRegex = Regex("""^## (v\d+\.\d+\.\d+[^\n]*)$""", RegexOption.MULTILINE)
    val matches = headingRegex.findAll(cleaned).toList()

    if (matches.isEmpty()) return ChangelogResponse(emptyList())

    val entries = mutableListOf<ChangelogEntry>()
    for (i in matches.indices) {
        val match = matches[i]
        val title = match.groupValues[1].trim()
        val version = Regex("""v?(\d+\.\d+\.\d+)""").find(title)?.groupValues?.get(1) ?: continue
        val start = match.range.last + 1
        val end = if (i + 1 < matches.size) matches[i + 1].range.first else cleaned.length
        val body = cleaned.substring(start, end)
            .trim()
            .removeSuffix("---")
            .trim()
        entries.add(ChangelogEntry(version = version, title = title, body = body))
    }
    return ChangelogResponse(entries)
}
