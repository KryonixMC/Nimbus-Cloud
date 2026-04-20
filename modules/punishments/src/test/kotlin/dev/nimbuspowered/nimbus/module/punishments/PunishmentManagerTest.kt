package dev.nimbuspowered.nimbus.module.punishments

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class PunishmentManagerTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var db: dev.nimbuspowered.nimbus.database.DatabaseManager
    private lateinit var mgr: PunishmentManager

    @BeforeEach
    fun setup() = runTest {
        db = buildPunishTestDb(tmp)
        mgr = PunishmentManager(db)
        mgr.init()
    }

    // ── Issue ───────────────────────────────────────────────────

    @Test
    fun `issue BAN creates active record and indexes cache`() = runTest {
        val rec = mgr.issue(
            type = PunishmentType.BAN,
            targetUuid = "uuid-a",
            targetName = "Alice",
            targetIp = null,
            duration = null,
            reason = "test",
            issuer = "console",
            issuerName = "Console"
        )
        assertTrue(rec.active)
        assertNull(rec.expiresAt)
        assertNotNull(mgr.checkLoginCached("uuid-a", null))
    }

    @Test
    fun `issue TEMPBAN records expiresAt`() = runTest {
        val rec = mgr.issue(
            PunishmentType.TEMPBAN, "uuid-b", "Bob", null,
            Duration.ofHours(1), "test", "console", "Console"
        )
        assertNotNull(rec.expiresAt)
    }

    @Test
    fun `issue IPBAN indexes by IP`() = runTest {
        mgr.issue(
            PunishmentType.IPBAN, "uuid-c", "Carol", "1.2.3.4",
            null, "test", "console", "Console"
        )
        assertNotNull(mgr.checkLoginCached("different-uuid", "1.2.3.4"))
    }

    @Test
    fun `issue MUTE indexes in mute cache only`() = runTest {
        mgr.issue(
            PunishmentType.MUTE, "uuid-d", "Dan", null,
            null, "test", "console", "Console"
        )
        assertNull(mgr.checkLoginCached("uuid-d", null))
        assertNotNull(mgr.checkMuteCached("uuid-d", null, null))
    }

    @Test
    fun `issue KICK is not indexed (one-shot, not revocable)`() = runTest {
        val rec = mgr.issue(
            PunishmentType.KICK, "uuid-e", "Eve", null,
            null, "test", "console", "Console"
        )
        assertFalse(rec.active)  // KICK stored inactive since not revocable
        assertNull(mgr.checkLoginCached("uuid-e", null))
    }

    // ── Supersede ───────────────────────────────────────────────

    @Test
    fun `new ban supersedes prior active ban at same scope`() = runTest {
        val first = mgr.issue(
            PunishmentType.BAN, "uuid-f", "F", null, null,
            "first", "console", "Console"
        )
        val second = mgr.issue(
            PunishmentType.BAN, "uuid-f", "F", null, null,
            "second", "console", "Console"
        )
        val firstReloaded = mgr.getById(first.id)!!
        assertFalse(firstReloaded.active)
        assertEquals("Superseded by new punishment", firstReloaded.revokeReason)
        // Cache holds only the new record.
        val cached = mgr.checkLoginCached("uuid-f", null)!!
        assertEquals(second.id, cached.id)
    }

    @Test
    fun `new mute does not supersede ban of same target`() = runTest {
        val ban = mgr.issue(
            PunishmentType.BAN, "uuid-g", "G", null, null,
            "ban", "console", "Console"
        )
        mgr.issue(
            PunishmentType.MUTE, "uuid-g", "G", null, null,
            "mute", "console", "Console"
        )
        // Ban still active — mute is a different class.
        assertTrue(mgr.getById(ban.id)!!.active)
    }

    @Test
    fun `scoped ban does not supersede network-scoped ban`() = runTest {
        val net = mgr.issue(
            PunishmentType.BAN, "uuid-h", "H", null, null,
            "net", "console", "Console",
            scope = PunishmentScope.NETWORK
        )
        mgr.issue(
            PunishmentType.BAN, "uuid-h", "H", null, null,
            "group-scoped", "console", "Console",
            scope = PunishmentScope.GROUP, scopeTarget = "BedWars"
        )
        assertTrue(mgr.getById(net.id)!!.active)
    }

    // ── Scope Filtering ─────────────────────────────────────────

    @Test
    fun `checkLoginCached ignores group-scoped bans`() = runTest {
        mgr.issue(
            PunishmentType.BAN, "uuid-i", "I", null, null,
            "scoped", "console", "Console",
            scope = PunishmentScope.GROUP, scopeTarget = "BedWars"
        )
        // Login check only honors NETWORK scope.
        assertNull(mgr.checkLoginCached("uuid-i", null))
    }

    @Test
    fun `checkConnectCached matches group-scoped ban for matching group`() = runTest {
        mgr.issue(
            PunishmentType.BAN, "uuid-j", "J", null, null,
            "scoped", "console", "Console",
            scope = PunishmentScope.GROUP, scopeTarget = "BedWars"
        )
        assertNotNull(mgr.checkConnectCached("uuid-j", null, "BedWars", "BedWars-1"))
        assertNull(mgr.checkConnectCached("uuid-j", null, "Lobby", "Lobby-1"))
    }

    @Test
    fun `checkConnectCached matches service-scoped ban for matching service`() = runTest {
        mgr.issue(
            PunishmentType.BAN, "uuid-k", "K", null, null,
            "svc", "console", "Console",
            scope = PunishmentScope.SERVICE, scopeTarget = "Lobby-1"
        )
        assertNotNull(mgr.checkConnectCached("uuid-k", null, "Lobby", "Lobby-1"))
        assertNull(mgr.checkConnectCached("uuid-k", null, "Lobby", "Lobby-2"))
    }

    @Test
    fun `checkMuteCached scopes apply`() = runTest {
        mgr.issue(
            PunishmentType.MUTE, "uuid-l", "L", null, null,
            "m", "console", "Console",
            scope = PunishmentScope.SERVICE, scopeTarget = "Lobby-1"
        )
        assertNotNull(mgr.checkMuteCached("uuid-l", "Lobby", "Lobby-1"))
        assertNull(mgr.checkMuteCached("uuid-l", "Lobby", "Lobby-2"))
    }

    // ── Revoke ──────────────────────────────────────────────────

    @Test
    fun `revoke flips active and clears cache`() = runTest {
        val rec = mgr.issue(
            PunishmentType.BAN, "uuid-m", "M", null, null,
            "r", "console", "Console"
        )
        val revoked = mgr.revoke(rec.id, "admin", "lifted")!!
        assertFalse(revoked.active)
        assertEquals("lifted", revoked.revokeReason)
        assertNull(mgr.checkLoginCached("uuid-m", null))
    }

    @Test
    fun `revoke already-revoked returns null`() = runTest {
        val rec = mgr.issue(
            PunishmentType.BAN, "uuid-n", "N", null, null,
            "", "console", "Console"
        )
        mgr.revoke(rec.id, "admin", null)
        assertNull(mgr.revoke(rec.id, "admin", null))
    }

    // ── Expiry Loop ─────────────────────────────────────────────

    @Test
    fun `expireOverdue deactivates expired tempbans`() = runTest {
        val rec = mgr.issue(
            PunishmentType.TEMPBAN, "uuid-o", "O", null,
            Duration.ofHours(1), "", "console", "Console"
        )
        // Force expiry by rewriting expires_at to the past.
        newSuspendedTransaction(Dispatchers.IO, db.database) {
            Punishments.update({ Punishments.id eq rec.id }) {
                it[expiresAt] = Instant.now().minusSeconds(60).toString()
            }
        }
        val expired = mgr.expireOverdue()
        assertEquals(1, expired.size)
        assertEquals(rec.id, expired[0].id)
        assertNull(mgr.checkLoginCached("uuid-o", null))
    }

    @Test
    fun `expireOverdue skips unexpired records`() = runTest {
        mgr.issue(
            PunishmentType.TEMPBAN, "uuid-p", "P", null,
            Duration.ofHours(24), "", "console", "Console"
        )
        val expired = mgr.expireOverdue()
        assertTrue(expired.isEmpty())
    }

    // ── History & List ──────────────────────────────────────────

    @Test
    fun `getHistory returns all records for target, newest first`() = runTest {
        val r1 = mgr.issue(
            PunishmentType.WARN, "uuid-q", "Q", null, null,
            "first", "console", "Console"
        )
        val r2 = mgr.issue(
            PunishmentType.KICK, "uuid-q", "Q", null, null,
            "second", "console", "Console"
        )
        // Stamp deterministic timestamps so the DESC ordering is not racing
        // Instant.now() resolution on fast systems.
        newSuspendedTransaction(Dispatchers.IO, db.database) {
            Punishments.update({ Punishments.id eq r1.id }) { it[issuedAt] = "2020-01-01T00:00:00Z" }
            Punishments.update({ Punishments.id eq r2.id }) { it[issuedAt] = "2020-01-02T00:00:00Z" }
        }
        val history = mgr.getHistory("uuid-q")
        assertEquals(2, history.size)
        assertEquals(r2.id, history[0].id)
        assertEquals(r1.id, history[1].id)
    }

    @Test
    fun `findActiveBan finds NETWORK ban only by default`() = runTest {
        mgr.issue(
            PunishmentType.BAN, "uuid-r", "R", null, null,
            "net", "console", "Console"
        )
        mgr.issue(
            PunishmentType.BAN, "uuid-r", "R", null, null,
            "grp", "console", "Console",
            scope = PunishmentScope.GROUP, scopeTarget = "Arena"
        )
        val network = mgr.findActiveBan("uuid-r")
        assertNotNull(network)
        assertEquals(PunishmentScope.NETWORK, network!!.scope)
        val grp = mgr.findActiveBan("uuid-r", PunishmentScope.GROUP, "Arena")
        assertEquals(PunishmentScope.GROUP, grp!!.scope)
        assertEquals("Arena", grp.scopeTarget)
    }
}
