package dev.nimbuspowered.nimbus.module.auth.service

import dev.nimbuspowered.nimbus.module.PermissionSet
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory store for half-authenticated login challenges: the user has
 * proven account ownership via code/magic-link, but TOTP still needs to
 * verify. A short-lived [challengeId] handoff token is returned to the
 * dashboard and traded back (with the TOTP code) at `/api/auth/totp-verify`.
 *
 * Kept out of the database deliberately — these entries live for at most
 * ~5 minutes and survive neither crashes nor restarts, which is the correct
 * behaviour (a crashed auth module should force the user to restart from
 * `consume-challenge`).
 */
class PendingTotpStore {

    data class Pending(
        val challengeId: String,
        val uuid: UUID,
        val name: String,
        val permissions: PermissionSet,
        val ip: String?,
        val userAgent: String?,
        val loginMethod: String,
        val expiresAt: Long
    )

    private val secureRandom = SecureRandom()
    private val entries = ConcurrentHashMap<String, Pending>()

    fun create(
        uuid: UUID,
        name: String,
        permissions: PermissionSet,
        ip: String?,
        userAgent: String?,
        loginMethod: String,
        ttlMs: Long = 5 * 60_000L
    ): Pending {
        prune()
        val id = randomId()
        val pending = Pending(
            challengeId = id,
            uuid = uuid,
            name = name,
            permissions = permissions,
            ip = ip,
            userAgent = userAgent,
            loginMethod = loginMethod,
            expiresAt = System.currentTimeMillis() + ttlMs
        )
        entries[id] = pending
        return pending
    }

    /** Fetches a pending entry without consuming it — used for `/me`-style lookups. */
    fun peek(challengeId: String): Pending? {
        prune()
        return entries[challengeId]?.takeIf { it.expiresAt >= System.currentTimeMillis() }
    }

    /** Atomically consumes the entry so a given challenge-id can be traded at most once. */
    fun consume(challengeId: String): Pending? {
        val p = entries.remove(challengeId) ?: return null
        if (p.expiresAt < System.currentTimeMillis()) return null
        return p
    }

    fun invalidate(challengeId: String) {
        entries.remove(challengeId)
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        entries.entries.removeIf { it.value.expiresAt < now }
    }

    private fun randomId(): String {
        val buf = ByteArray(24)
        secureRandom.nextBytes(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }
}
