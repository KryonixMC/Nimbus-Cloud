package dev.nimbuspowered.nimbus.module.auth.service

import dev.nimbuspowered.nimbus.database.DatabaseManager
import dev.nimbuspowered.nimbus.module.auth.AuthConfig
import dev.nimbuspowered.nimbus.module.auth.LoginChallengeConfig
import dev.nimbuspowered.nimbus.module.auth.buildAuthTestDb
import dev.nimbuspowered.nimbus.module.auth.db.DashboardLoginChallenges
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class LoginChallengeServiceTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var db: DatabaseManager
    private lateinit var service: LoginChallengeService
    private var config = AuthConfig()

    @BeforeEach
    fun setup() {
        config = AuthConfig()
        db = buildAuthTestDb(tmp)
        service = LoginChallengeService(db) { config }
    }

    @Test
    fun `issueCode produces 6-digit numeric code`() = runTest {
        val issued = service.issueCode("uuid-1", "Alice")
        assertEquals(6, issued.raw.length)
        assertTrue(issued.raw.all { it.isDigit() })
        assertEquals(ChallengeKind.CODE, issued.kind)
        assertEquals("uuid-1", issued.uuid)
        assertTrue(issued.expiresAt > System.currentTimeMillis())
    }

    @Test
    fun `raw code is never stored - only SHA256 hash`() = runTest {
        val issued = service.issueCode("uuid-2", "Bob")
        val expectedHash = LoginChallengeService.sha256Hex(issued.raw)
        transaction(db.database) {
            val row = DashboardLoginChallenges.selectAll()
                .where { DashboardLoginChallenges.uuid eq "uuid-2" }
                .first()
            assertEquals(expectedHash, row[DashboardLoginChallenges.challengeHash])
        }
    }

    @Test
    fun `consume accepts valid code once and marks it consumed`() = runTest {
        val issued = service.issueCode("uuid-3", "Carol")
        val consumed = service.consume(issued.raw)
        assertNotNull(consumed)
        assertEquals("uuid-3", consumed!!.uuid)
        assertEquals(ChallengeKind.CODE, consumed.kind)
        // Second attempt must fail (single-use).
        assertNull(service.consume(issued.raw))
    }

    @Test
    fun `consume rejects unknown code`() = runTest {
        assertNull(service.consume("000000"))
    }

    @Test
    fun `consume rejects expired code`() = runTest {
        val issued = service.issueCode("uuid-4", "Dan")
        // Simulate expiry by rewriting the row's expiresAt to the past.
        transaction(db.database) {
            DashboardLoginChallenges.update({ DashboardLoginChallenges.uuid eq "uuid-4" }) {
                it[expiresAt] = System.currentTimeMillis() - 1000
            }
        }
        assertNull(service.consume(issued.raw))
    }

    @Test
    fun `magic link produces URL-safe token and can be consumed`() = runTest {
        val issued = service.issueMagicLink("uuid-5", "Eve", "1.2.3.4")
        assertEquals(ChallengeKind.MAGIC_LINK, issued.kind)
        assertTrue(issued.raw.length > 6)
        // No padding in URL-safe base64 (withoutPadding()).
        assertTrue(!issued.raw.contains("="))
        val consumed = service.consume(issued.raw)
        assertNotNull(consumed)
        assertEquals("1.2.3.4", consumed!!.originIp)
    }

    @Test
    fun `magic link disabled throws`() = runTest {
        config = AuthConfig(loginChallenge = LoginChallengeConfig(magicLinkEnabled = false))
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                service.issueMagicLink("uuid-6", "Frank", null)
            }
        }
    }

    @Test
    fun `rate limit prevents more than max generates per minute`() = runTest {
        config = AuthConfig(loginChallenge = LoginChallengeConfig(maxGeneratesPerMinute = 2))
        service.issueCode("uuid-7", "G")
        service.issueCode("uuid-7", "G")
        assertThrows(LoginChallengeService.RateLimitedException::class.java) {
            kotlinx.coroutines.runBlocking {
                service.issueCode("uuid-7", "G")
            }
        }
    }

    @Test
    fun `consume throttle blocks after N failures from same IP`() = runTest {
        config = AuthConfig(loginChallenge = LoginChallengeConfig(maxConsumeFailuresPerMinute = 3))
        // 3 wrong attempts
        repeat(3) { assertNull(service.consume("000$it", "9.9.9.9")) }
        // 4th attempt: throttle raises even before DB lookup.
        assertThrows(LoginChallengeService.RateLimitedException::class.java) {
            kotlinx.coroutines.runBlocking {
                service.consume("0004", "9.9.9.9")
            }
        }
    }

    @Test
    fun `successful consume clears IP failure window`() = runTest {
        config = AuthConfig(loginChallenge = LoginChallengeConfig(maxConsumeFailuresPerMinute = 3))
        repeat(2) { assertNull(service.consume("bad$it", "8.8.8.8")) }
        val issued = service.issueCode("uuid-8", "H")
        val ok = service.consume(issued.raw, "8.8.8.8")
        assertNotNull(ok)
        // Now can fail 3 more times without tripping (window was cleared).
        repeat(3) { assertNull(service.consume("bad-after-$it", "8.8.8.8")) }
        assertThrows(LoginChallengeService.RateLimitedException::class.java) {
            kotlinx.coroutines.runBlocking { service.consume("x", "8.8.8.8") }
        }
    }

    @Test
    fun `sha256Hex is deterministic`() {
        val a = LoginChallengeService.sha256Hex("hello")
        val b = LoginChallengeService.sha256Hex("hello")
        assertEquals(a, b)
        assertEquals(64, a.length)  // 32 bytes hex
    }
}
