package dev.nimbuspowered.nimbus.module.auth.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WsTicketStoreTest {

    private lateinit var store: WsTicketStore

    @BeforeEach
    fun setup() {
        store = WsTicketStore()
    }

    @Test
    fun `mint returns a non-blank ticket string`() {
        val ticket = store.mint("session-token-123")
        assertTrue(ticket.token.isNotBlank(), "ticket token must be non-blank")
        assertTrue(ticket.token.startsWith(WsTicketStore.PREFIX), "ticket must start with prefix '${WsTicketStore.PREFIX}'")
    }

    @Test
    fun `consume returns the original session token`() {
        val sessionToken = "my-raw-session-token"
        val ticket = store.mint(sessionToken)
        val result = store.consume(ticket.token)
        assertEquals(sessionToken, result)
    }

    @Test
    fun `consume is single-use`() {
        val ticket = store.mint("session-token")
        val first = store.consume(ticket.token)
        val second = store.consume(ticket.token)
        assertNotNull(first, "first consume should return the session token")
        assertNull(second, "second consume should return null (single-use)")
    }

    @Test
    fun `consume unknown ticket returns null`() {
        val result = store.consume("wt_nonexistentticket")
        assertNull(result)
    }

    @Test
    fun `different sessions get different tickets`() {
        val ticket1 = store.mint("session-token-A")
        val ticket2 = store.mint("session-token-B")
        assertNotEquals(ticket1.token, ticket2.token, "each mint must produce a unique ticket")

        assertEquals("session-token-A", store.consume(ticket1.token))
        assertEquals("session-token-B", store.consume(ticket2.token))
    }

    @Test
    fun `concurrent consume calls on same ticket — exactly one succeeds`() = runTest {
        val ticket = store.mint("session-token-concurrent")
        val results = withContext(Dispatchers.Default) {
            (1..10).map { async { store.consume(ticket.token) } }.awaitAll()
        }
        val nonNullCount = results.count { it != null }
        assertEquals(1, nonNullCount, "exactly one concurrent consume should return non-null")
    }

    @Test
    fun `expired ticket returns null`() {
        // Use a store with a 1 ms TTL so the ticket expires immediately
        val shortLivedStore = WsTicketStore(ttlMillis = 1L)
        val ticket = shortLivedStore.mint("session-token-expired")
        Thread.sleep(50) // ensure the TTL has elapsed
        val result = shortLivedStore.consume(ticket.token)
        assertNull(result, "expired ticket must return null")
    }

    @Test
    fun `looksLikeTicket returns true for minted ticket`() {
        val ticket = store.mint("session-token")
        assertTrue(store.looksLikeTicket(ticket.token))
    }

    @Test
    fun `looksLikeTicket returns false for arbitrary string`() {
        assertTrue(!store.looksLikeTicket("Bearer eyJhbGciOiJIUzI1NiJ9"))
    }
}
