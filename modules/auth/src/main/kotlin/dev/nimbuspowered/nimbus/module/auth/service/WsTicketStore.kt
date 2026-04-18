package dev.nimbuspowered.nimbus.module.auth.service

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived one-time tickets minted in exchange for a valid dashboard
 * session, used to authenticate WebSocket / SSE connections without putting
 * the long-lived session token in a URL query string.
 *
 * Tickets are:
 *   - opaque 24-byte random tokens, URL-safe base64, `wt_` prefixed so the
 *     [SessionValidator] hook can recognise and consume them
 *   - single-use — [consume] removes the entry atomically
 *   - TTL-bounded (30 seconds by default) — long enough for a browser to
 *     fetch-then-connect, short enough that a leaked access log cannot be
 *     replayed meaningfully
 *
 * The store is in-memory and intentionally crash-ephemeral: on restart the
 * dashboard simply re-fetches a ticket before re-opening the WebSocket.
 */
class WsTicketStore(private val ttlMillis: Long = 30_000L) {
    private data class Entry(val sessionRawToken: String, val expiresAt: Long)

    private val tickets = ConcurrentHashMap<String, Entry>()
    private val random = SecureRandom()

    /** Mint a new ticket that redeems to [sessionRawToken] exactly once. */
    fun mint(sessionRawToken: String): Ticket {
        val bytes = ByteArray(24).also { random.nextBytes(it) }
        val token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val expiresAt = System.currentTimeMillis() + ttlMillis
        tickets[token] = Entry(sessionRawToken, expiresAt)
        if (tickets.size >= CLEANUP_THRESHOLD) cleanup()
        return Ticket(token, expiresAt)
    }

    /**
     * Consume [ticket] and return the bound session raw token, or `null` if
     * the ticket is unknown, already used, or expired. The entry is removed
     * on any call — legitimate or not — to keep the store small.
     */
    fun consume(ticket: String): String? {
        val entry = tickets.remove(ticket) ?: return null
        if (entry.expiresAt < System.currentTimeMillis()) return null
        return entry.sessionRawToken
    }

    fun looksLikeTicket(candidate: String): Boolean = candidate.startsWith(PREFIX)

    private fun cleanup() {
        val now = System.currentTimeMillis()
        tickets.entries.removeIf { it.value.expiresAt < now }
    }

    data class Ticket(val token: String, val expiresAt: Long)

    companion object {
        const val PREFIX = "wt_"
        private const val CLEANUP_THRESHOLD = 128
    }
}
