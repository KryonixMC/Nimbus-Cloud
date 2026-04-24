package dev.nimbuspowered.nimbus.module.auth.service

import dev.nimbuspowered.nimbus.database.DatabaseManager
import dev.nimbuspowered.nimbus.module.api.PermissionSet
import dev.nimbuspowered.nimbus.module.auth.AuthConfig
import dev.nimbuspowered.nimbus.module.auth.SessionsConfig
import dev.nimbuspowered.nimbus.module.auth.buildAuthTestDb
import dev.nimbuspowered.nimbus.module.auth.db.DashboardSessions
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class SessionServiceTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var db: DatabaseManager
    private lateinit var service: SessionService
    private var config = AuthConfig()

    @BeforeEach
    fun setup() {
        config = AuthConfig()
        db = buildAuthTestDb(tmp)
        service = SessionService(db) { config }
    }

    @Test
    fun `issue returns raw token and principal matches input`() = runTest {
        val uuid = UUID.randomUUID()
        val perms = PermissionSet.of("nimbus.dashboard.view")
        val result = service.issue(uuid, "Alice", perms, "1.2.3.4", "Mozilla", "code")
        assertTrue(result.rawToken.isNotEmpty())
        assertEquals(uuid, result.principal.uuid)
        assertEquals("Alice", result.principal.name)
        assertTrue(result.principal.permissions.has("nimbus.dashboard.view"))
    }

    @Test
    fun `DB stores only SHA256 hash, never raw token`() = runTest {
        val uuid = UUID.randomUUID()
        val issued = service.issue(uuid, "Bob", PermissionSet.EMPTY, null, null, "code")
        val expectedHash = LoginChallengeService.sha256Hex(issued.rawToken)
        transaction(db.database) {
            val row = DashboardSessions.selectAll()
                .where { DashboardSessions.uuid eq uuid.toString() }
                .first()
            assertEquals(expectedHash, row[DashboardSessions.tokenHash])
            // And the raw token is not stored anywhere accessible.
            assertNotEquals(issued.rawToken, row[DashboardSessions.tokenHash])
        }
    }

    @Test
    fun `validate returns principal for live token`() = runTest {
        val uuid = UUID.randomUUID()
        val issued = service.issue(uuid, "Carol", PermissionSet.of("a.b"), null, null, "code")
        val principal = service.validate(issued.rawToken)
        assertNotNull(principal)
        assertEquals(uuid, principal!!.uuid)
        assertTrue(principal.permissions.has("a.b"))
    }

    @Test
    fun `validate returns null for unknown token`() = runTest {
        assertNull(service.validate("definitely-not-a-real-token"))
    }

    @Test
    fun `validate returns null for revoked session`() = runTest {
        val uuid = UUID.randomUUID()
        val issued = service.issue(uuid, "Dan", PermissionSet.EMPTY, null, null, "code")
        assertTrue(service.revoke(issued.rawToken))
        assertNull(service.validate(issued.rawToken))
    }

    @Test
    fun `validate returns null for expired session`() = runTest {
        val uuid = UUID.randomUUID()
        val issued = service.issue(uuid, "Eve", PermissionSet.EMPTY, null, null, "code")
        val hash = LoginChallengeService.sha256Hex(issued.rawToken)
        transaction(db.database) {
            DashboardSessions.update({ DashboardSessions.tokenHash eq hash }) {
                it[expiresAt] = System.currentTimeMillis() - 10_000
            }
        }
        assertNull(service.validate(issued.rawToken))
    }

    @Test
    fun `max per user evicts oldest sessions`() = runTest {
        config = AuthConfig(sessions = SessionsConfig(maxPerUser = 3))
        val uuid = UUID.randomUUID()
        val tokens = (1..4).map {
            service.issue(uuid, "F", PermissionSet.EMPTY, null, null, "code").rawToken
        }
        // After 4 issues with cap 3, the very first should be revoked.
        assertNull(service.validate(tokens[0]))
        assertNotNull(service.validate(tokens[1]))
        assertNotNull(service.validate(tokens[3]))
    }

    @Test
    fun `rolling refresh extends expiry on validate`() = runTest {
        config = AuthConfig(sessions = SessionsConfig(ttlSeconds = 3600, rollingRefresh = true))
        val uuid = UUID.randomUUID()
        val issued = service.issue(uuid, "G", PermissionSet.EMPTY, null, null, "code")
        // Force the stored expires_at into the past of what a rolling refresh
        // would produce so the assertion is deterministic regardless of clock jitter.
        val hash = LoginChallengeService.sha256Hex(issued.rawToken)
        val rewound = System.currentTimeMillis() - 60_000
        transaction(db.database) {
            DashboardSessions.update({ DashboardSessions.tokenHash eq hash }) {
                it[expiresAt] = rewound + 3_600_000L
                it[lastUsedAt] = rewound
            }
        }
        val validated = service.validate(issued.rawToken)!!
        assertTrue(validated.expiresAt > rewound + 3_600_000L)
    }

    @Test
    fun `rolling refresh disabled keeps original expiry`() = runTest {
        config = AuthConfig(sessions = SessionsConfig(ttlSeconds = 3600, rollingRefresh = false))
        val uuid = UUID.randomUUID()
        val issued = service.issue(uuid, "H", PermissionSet.EMPTY, null, null, "code")
        val validated = service.validate(issued.rawToken)!!
        assertEquals(issued.principal.expiresAt, validated.expiresAt)
    }

    @Test
    fun `revokeAll revokes every session for a uuid`() = runTest {
        val uuid = UUID.randomUUID()
        val t1 = service.issue(uuid, "I", PermissionSet.EMPTY, null, null, "code").rawToken
        val t2 = service.issue(uuid, "I", PermissionSet.EMPTY, null, null, "code").rawToken
        val count = service.revokeAll(uuid)
        assertTrue(count >= 2)
        assertNull(service.validate(t1))
        assertNull(service.validate(t2))
    }

    @Test
    fun `refreshPermissionsFor updates snapshot for active sessions`() = runTest {
        val uuid = UUID.randomUUID()
        val issued = service.issue(uuid, "J", PermissionSet.of("old.perm"), null, null, "code")
        val updated = service.refreshPermissionsFor(uuid.toString(), PermissionSet.of("new.perm"))
        assertEquals(1, updated)
        val validated = service.validate(issued.rawToken)!!
        assertFalse(validated.permissions.has("old.perm"))
        assertTrue(validated.permissions.has("new.perm"))
    }

    @Test
    fun `listForUser returns active sessions sorted by last used desc`() = runTest {
        val uuid = UUID.randomUUID()
        val t1 = service.issue(uuid, "K", PermissionSet.EMPTY, null, null, "code").rawToken
        val t2 = service.issue(uuid, "K", PermissionSet.EMPTY, null, null, "code").rawToken
        // Pin lastUsedAt deterministically rather than relying on wall-clock separation
        // between two sub-millisecond issue() calls.
        val h1 = LoginChallengeService.sha256Hex(t1)
        val h2 = LoginChallengeService.sha256Hex(t2)
        transaction(db.database) {
            DashboardSessions.update({ DashboardSessions.tokenHash eq h1 }) { it[lastUsedAt] = 1_000L }
            DashboardSessions.update({ DashboardSessions.tokenHash eq h2 }) { it[lastUsedAt] = 2_000L }
        }
        val list = service.listForUser(uuid)
        assertEquals(2, list.size)
        assertTrue(list[0].lastUsedAt > list[1].lastUsedAt)
    }

    @Test
    fun `revokeOwnedSession only revokes sessions of matching uuid`() = runTest {
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()
        val aliceToken = service.issue(alice, "A", PermissionSet.EMPTY, null, null, "code").rawToken
        val bobToken = service.issue(bob, "B", PermissionSet.EMPTY, null, null, "code").rawToken
        val bobSessionId = service.listForUser(bob).first().sessionId
        // Alice tries to revoke Bob's session → must fail.
        assertFalse(service.revokeOwnedSession(alice, bobSessionId))
        assertNotNull(service.validate(bobToken))
        // Bob's own revoke succeeds.
        assertTrue(service.revokeOwnedSession(bob, bobSessionId))
        assertNull(service.validate(bobToken))
        // Alice's session untouched.
        assertNotNull(service.validate(aliceToken))
    }

    @Test
    fun `permissions snapshot is JSON array with comma-safe nodes`() = runTest {
        val uuid = UUID.randomUUID()
        // Nodes containing commas would break legacy comma-joined storage —
        // JSON array serialization must preserve them exactly.
        val weird = "service:Lobby-1,BedWars-3"
        val issued = service.issue(uuid, "L", PermissionSet.of(weird, "normal.node"), null, null, "code")
        val validated = service.validate(issued.rawToken)!!
        assertTrue(validated.permissions.has(weird))
        assertTrue(validated.permissions.has("normal.node"))
    }
}
