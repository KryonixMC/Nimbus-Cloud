package dev.nimbuspowered.nimbus.module.perms

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class PermissionContextExpiryTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var mgr: PermissionManager
    private lateinit var db: dev.nimbuspowered.nimbus.database.DatabaseManager

    /** A plain group with no wildcard permissions — safe for targeted hasPermission assertions. */
    private val testGroup = "ContextTestGroup"

    @BeforeEach
    fun setup() = runTest {
        db = buildPermsTestDb(tmp)
        mgr = PermissionManager(db)
        mgr.init()
        // Create a clean group without any wildcard so we can assert specific permissions.
        mgr.createGroup(testGroup)
    }

    private val testUuid = UUID.randomUUID().toString()

    /** Returns an ISO-8601 timestamp that is already expired (1 second in the past). */
    private fun pastTimestamp(): String = Instant.now().minusSeconds(1).toString()

    /** Returns an ISO-8601 timestamp that expires far in the future. */
    private fun futureTimestamp(): String = Instant.now().plusSeconds(3600).toString()

    // ── Group permission context expiry ─────────────────────────

    @Test
    fun `expired contextual permission is cleaned up`() = runTest {
        // Add contextual permission with a past expiry
        mgr.addPermission(testGroup, "test.expired", PermissionContext(expiresAt = pastTimestamp()))
        val cleanedCount = mgr.cleanupExpired()
        assertTrue(cleanedCount >= 1, "Expected at least 1 expired context cleaned, got $cleanedCount")
        // After cleanup (which calls reload), the contextual perm list should be empty
        val group = mgr.getGroup(testGroup)!!
        assertFalse(
            group.contextualPermissions.containsKey("test.expired"),
            "Expired contextual permission should have been removed from in-memory state after cleanup+reload"
        )
    }

    @Test
    fun `non-expired contextual permission survives cleanup`() = runTest {
        mgr.addPermission(testGroup, "test.active", PermissionContext(expiresAt = futureTimestamp()))
        val cleanedCount = mgr.cleanupExpired()
        assertEquals(0, cleanedCount, "No contexts should be cleaned for a future expiry")
        val group = mgr.getGroup(testGroup)!!
        assertTrue(
            group.contextualPermissions.containsKey("test.active"),
            "Non-expired contextual permission should survive cleanup"
        )
    }

    @Test
    fun `null expiry context is permanent`() = runTest {
        // Permission with a context that has server restriction but no expiry
        mgr.addPermission(testGroup, "test.permanent", PermissionContext(server = "survival", expiresAt = null))
        val cleanedCount = mgr.cleanupExpired()
        assertEquals(0, cleanedCount, "Permanent contexts should not be cleaned up")
        val group = mgr.getGroup(testGroup)!!
        assertTrue(
            group.contextualPermissions.containsKey("test.permanent"),
            "Permanent contextual permission should survive cleanup"
        )
    }

    @Test
    fun `cleanupExpired returns correct count`() = runTest {
        // Add 3 expired group permission contexts
        mgr.addPermission(testGroup, "test.exp1", PermissionContext(expiresAt = pastTimestamp()))
        mgr.addPermission(testGroup, "test.exp2", PermissionContext(expiresAt = pastTimestamp()))
        mgr.addPermission(testGroup, "test.exp3", PermissionContext(expiresAt = pastTimestamp()))
        // Add 1 valid (non-expired) context
        mgr.addPermission(testGroup, "test.valid", PermissionContext(expiresAt = futureTimestamp()))

        val cleanedCount = mgr.cleanupExpired()
        assertEquals(3, cleanedCount, "Should clean exactly 3 expired contexts")
    }

    @Test
    fun `expired contextual permission is not effective at runtime`() = runTest {
        mgr.registerPlayer(testUuid, "TestPlayer")
        // Use a group with no wildcard. Add a server-scoped, expired permission.
        mgr.addPermission(testGroup, "test.expiredperm", PermissionContext(server = "survival", expiresAt = pastTimestamp()))
        mgr.setPlayerGroup(testUuid, "TestPlayer", testGroup)

        // contextMatches returns false for expired entries, so the permission should not be granted.
        assertFalse(
            mgr.hasPermission(testUuid, "test.expiredperm", server = "survival"),
            "hasPermission should return false for a contextual permission whose expiry is in the past"
        )
    }

    @Test
    fun `active contextual permission is effective at runtime`() = runTest {
        mgr.registerPlayer(testUuid, "TestPlayer")
        mgr.addPermission(testGroup, "test.activeperm", PermissionContext(server = "survival", expiresAt = futureTimestamp()))
        mgr.setPlayerGroup(testUuid, "TestPlayer", testGroup)

        assertTrue(
            mgr.hasPermission(testUuid, "test.activeperm", server = "survival"),
            "hasPermission should return true for a contextual permission with a future expiry"
        )
    }

    // ── Player group context expiry ─────────────────────────────

    @Test
    fun `expired player group assignment is cleaned up`() = runTest {
        mgr.registerPlayer(testUuid, "TestPlayer")
        // Assign player to testGroup with a scoped (expired) context
        mgr.setPlayerGroup(testUuid, "TestPlayer", testGroup, PermissionContext(expiresAt = pastTimestamp()))

        val cleanedCount = mgr.cleanupExpired()
        assertTrue(cleanedCount >= 1, "Expected at least 1 expired player group context cleaned")

        // After cleanup+reload the player's group context should be gone
        val entry = mgr.getPlayer(testUuid)!!
        val contexts = entry.groupContexts[testGroup.lowercase()]
        assertTrue(
            contexts == null || contexts.isEmpty(),
            "Expired player group context should be removed after cleanup"
        )
    }

    @Test
    fun `non-expired player group context survives cleanup`() = runTest {
        mgr.registerPlayer(testUuid, "TestPlayer")
        mgr.setPlayerGroup(testUuid, "TestPlayer", testGroup, PermissionContext(expiresAt = futureTimestamp()))

        val cleanedCount = mgr.cleanupExpired()
        assertEquals(0, cleanedCount, "Non-expired player group context should not be cleaned")

        val entry = mgr.getPlayer(testUuid)!!
        val contexts = entry.groupContexts[testGroup.lowercase()]
        assertTrue(
            !contexts.isNullOrEmpty(),
            "Non-expired player group context should survive cleanup"
        )
    }
}
