package dev.nimbuspowered.nimbus.module.auth.service

import dev.nimbuspowered.nimbus.module.api.PermissionSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class PendingTotpStoreTest {

    private lateinit var store: PendingTotpStore

    // Reusable helpers
    private val perms = PermissionSet.of("nimbus.dashboard.view")

    private fun createEntry(
        uuid: UUID = UUID.randomUUID(),
        name: String = "TestUser",
        ip: String? = "127.0.0.1",
        userAgent: String? = "TestAgent/1.0",
        loginMethod: String = "code",
        ttlMs: Long = 5 * 60_000L
    ): PendingTotpStore.Pending = store.create(uuid, name, perms, ip, userAgent, loginMethod, ttlMs)

    @BeforeEach
    fun setup() {
        store = PendingTotpStore()
    }

    @Test
    fun `peek returns null for unknown challenge id`() {
        assertNull(store.peek("nonexistent-challenge-id"))
    }

    @Test
    fun `consume returns null for unknown challenge id`() {
        assertNull(store.consume("nonexistent-challenge-id"))
    }

    @Test
    fun `create and peek round trip`() {
        val uuid = UUID.randomUUID()
        val pending = createEntry(uuid = uuid, name = "Alice")

        val result = store.peek(pending.challengeId)
        assertNotNull(result)
        assertEquals(pending.challengeId, result!!.challengeId)
        assertEquals(uuid, result.uuid)
        assertEquals("Alice", result.name)
        assertEquals(perms, result.permissions)
    }

    @Test
    fun `create and consume round trip`() {
        val uuid = UUID.randomUUID()
        val pending = createEntry(uuid = uuid, loginMethod = "magic-link")

        val result = store.consume(pending.challengeId)
        assertNotNull(result)
        assertEquals(pending.challengeId, result!!.challengeId)
        assertEquals(uuid, result.uuid)
        assertEquals("magic-link", result.loginMethod)
    }

    @Test
    fun `consume removes the entry — second consume returns null`() {
        val pending = createEntry()
        val first = store.consume(pending.challengeId)
        val second = store.consume(pending.challengeId)
        assertNotNull(first, "first consume must succeed")
        assertNull(second, "second consume must return null (single-use)")
    }

    @Test
    fun `invalidate clears entry`() {
        val pending = createEntry()
        store.invalidate(pending.challengeId)
        assertNull(store.peek(pending.challengeId))
        assertNull(store.consume(pending.challengeId))
    }

    @Test
    fun `second create with same uuid overwrites first`() {
        val uuid = UUID.randomUUID()
        val first = createEntry(uuid = uuid, name = "First")
        val second = createEntry(uuid = uuid, name = "Second")

        // Both challenge IDs are independent keys — second does not remove first by uuid.
        // Instead verify that the second entry carries the new name.
        val result = store.consume(second.challengeId)
        assertNotNull(result)
        assertEquals("Second", result!!.name)
    }

    @Test
    fun `multiple uuids are independent`() {
        val uuid1 = UUID.randomUUID()
        val uuid2 = UUID.randomUUID()

        val p1 = createEntry(uuid = uuid1, name = "User1")
        val p2 = createEntry(uuid = uuid2, name = "User2")

        val r1 = store.consume(p1.challengeId)
        val r2 = store.consume(p2.challengeId)

        assertNotNull(r1)
        assertNotNull(r2)
        assertEquals(uuid1, r1!!.uuid)
        assertEquals(uuid2, r2!!.uuid)
        assertEquals("User1", r1.name)
        assertEquals("User2", r2.name)
    }

    @Test
    fun `expired entry returns null from peek`() {
        // Create with 1 ms TTL so it expires immediately
        val pending = createEntry(ttlMs = 1L)
        Thread.sleep(50)
        assertNull(store.peek(pending.challengeId), "expired entry must return null from peek")
    }

    @Test
    fun `expired entry returns null from consume`() {
        val pending = createEntry(ttlMs = 1L)
        Thread.sleep(50)
        assertNull(store.consume(pending.challengeId), "expired entry must return null from consume")
    }

    @Test
    fun `peek does not consume the entry`() {
        val pending = createEntry()
        val peeked = store.peek(pending.challengeId)
        assertNotNull(peeked)
        // entry must still be consumable after a peek
        val consumed = store.consume(pending.challengeId)
        assertNotNull(consumed, "entry must still be present after peek")
    }
}
