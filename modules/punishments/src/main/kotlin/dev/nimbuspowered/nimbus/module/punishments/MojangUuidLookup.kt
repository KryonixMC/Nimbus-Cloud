package dev.nimbuspowered.nimbus.module.punishments

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves Minecraft usernames to Mojang UUIDs via the public profile API.
 * Used by the punishments module so staff can ban / mute players who have never
 * touched the network — by name alone.
 *
 * Results are cached in-memory for the lifetime of the controller. Mojang names
 * aren't permanent but changing them invalidates the old→UUID mapping in Mojang's
 * side too, so a stale cache entry would just fail to match online players.
 */
object MojangUuidLookup {

    private val logger = LoggerFactory.getLogger(MojangUuidLookup::class.java)
    private val cache = ConcurrentHashMap<String, Result>()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class MojangProfile(val id: String, val name: String)

    /** Cached result — either a hit (uuid + canonical name) or a miss (e.g. user doesn't exist). */
    sealed class Result {
        data class Hit(val uuid: String, val name: String) : Result()
        data object Miss : Result()
    }

    /**
     * Resolve a username synchronously. Callers must already be off the main/request
     * thread — this performs blocking HTTP.
     *
     * Returns `null` for unknown usernames / network failures so callers can fall
     * back to storing the punishment by name-only.
     */
    fun resolve(username: String): Pair<String, String>? {
        val key = username.trim().lowercase()
        if (key.isEmpty() || key.length > 16) return null

        cache[key]?.let {
            return when (it) {
                is Result.Hit -> it.uuid to it.name
                Result.Miss -> null
            }
        }

        val url = URI.create("https://api.mojang.com/users/profiles/minecraft/$key").toURL()
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 3000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Nimbus/Punishments")
            val code = conn.responseCode
            when {
                code == 200 -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val profile = json.decodeFromString(MojangProfile.serializer(), body)
                    val dashed = formatUuid(profile.id)
                    cache[key] = Result.Hit(dashed, profile.name)
                    dashed to profile.name
                }
                code == 204 || code == 404 -> {
                    cache[key] = Result.Miss
                    null
                }
                code == 429 -> {
                    logger.warn("Mojang API rate-limited us — not caching '{}'", key)
                    null
                }
                else -> {
                    logger.debug("Mojang API returned HTTP {} for '{}'", code, key)
                    null
                }
            }
        } catch (e: Exception) {
            logger.debug("Mojang lookup failed for '{}': {}", key, e.message)
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Inserts dashes into a Mojang-format UUID (32 hex chars, no dashes). */
    private fun formatUuid(undashed: String): String {
        if (undashed.length != 32) return undashed
        return "${undashed.substring(0, 8)}-${undashed.substring(8, 12)}-" +
               "${undashed.substring(12, 16)}-${undashed.substring(16, 20)}-" +
               "${undashed.substring(20)}"
    }
}
