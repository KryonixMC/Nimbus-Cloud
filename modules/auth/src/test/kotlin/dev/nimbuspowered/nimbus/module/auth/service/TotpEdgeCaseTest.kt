package dev.nimbuspowered.nimbus.module.auth.service

import dev.nimbuspowered.nimbus.module.auth.AuthConfig
import dev.nimbuspowered.nimbus.module.auth.TotpConfig
import dev.nimbuspowered.nimbus.module.auth.buildAuthTestDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Edge-case tests for [TotpService] covering window clamping boundaries,
 * failed confirm/disable flows, and recovery-code accounting.
 *
 * The RFC 6238 helpers at the bottom are copied from [TotpServiceTest].
 */
class TotpEdgeCaseTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var service: TotpService
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private var config = AuthConfig()

    @BeforeEach
    fun setup() {
        config = AuthConfig()
        service = TotpService(buildAuthTestDb(tmp), { config }, key)
    }

    // ── Window boundary ─────────────────────────────────────────────────────

    /**
     * window=0 means only the exact current step is accepted. A code from
     * step-1 must be rejected; a code from the current step must be accepted.
     */
    @Test
    fun `window 0 only accepts current step`() = runTest {
        config = AuthConfig(totp = TotpConfig(window = 0))
        val mat = service.enroll("uuid-w0", "W0")
        val secret = base32Decode(mat.secretBase32)
        val now = Instant.now().epochSecond / 30

        val prevCode = formatCode(generateTotp(secret, now - 1))
        assertFalse(
            service.verifyForLogin("uuid-w0", prevCode),
            "Step-1 code should be rejected when window=0"
        )

        val currentCode = formatCode(generateTotp(secret, now))
        assertTrue(
            service.verifyForLogin("uuid-w0", currentCode),
            "Current-step code should be accepted when window=0"
        )
    }

    /**
     * Even when window is configured to an absurdly large value (10), the
     * implementation clamps to max 2. A code from step-3 must still be
     * rejected because it falls outside the clamped window of ±2.
     *
     * NOTE: TotpServiceTest already covers window=99 clamped at max-2.
     * This test uses window=10 to verify the clamp is not specific to
     * extreme values.
     */
    @Test
    fun `window clamped to 2 rejects step-3 even with window config 10`() = runTest {
        config = AuthConfig(totp = TotpConfig(window = 10))
        val mat = service.enroll("uuid-w10", "W10")
        val secret = base32Decode(mat.secretBase32)
        val now = Instant.now().epochSecond / 30

        // Step -3 is outside the clamped window of 2.
        val tooOldCode = formatCode(generateTotp(secret, now - 3))
        assertFalse(
            service.verifyForLogin("uuid-w10", tooOldCode),
            "Step-3 code must be rejected when window is clamped to 2"
        )

        // Step -2 is at the boundary of the clamped window — must be accepted.
        val boundaryCode = formatCode(generateTotp(secret, now - 2))
        assertTrue(
            service.verifyForLogin("uuid-w10", boundaryCode),
            "Step-2 code must be accepted at the boundary of clamped window=2"
        )
    }

    // ── Confirm edge cases ──────────────────────────────────────────────────

    /**
     * Submitting a wrong code to confirm() must return false AND leave the
     * enrollment in the pending (not enabled) state so the user can retry.
     */
    @Test
    fun `confirm with wrong code leaves enrollment pending`() = runTest {
        service.enroll("uuid-cf", "CF")

        val confirmed = service.confirm("uuid-cf", "000000")
        assertFalse(confirmed, "confirm with wrong code must return false")

        val state = service.state("uuid-cf")
        assertFalse(state.enabled, "TOTP must remain disabled after failed confirm")
        assertTrue(state.pendingEnrollment, "Enrollment must still be pending after failed confirm")
        assertFalse(service.isEnabled("uuid-cf"), "isEnabled must return false after failed confirm")
    }

    // ── Disable edge cases ──────────────────────────────────────────────────

    /**
     * Calling disable() with an incorrect code must return false and leave
     * TOTP active — the user can still log in with 2FA.
     */
    @Test
    fun `disable with wrong code does not disable totp`() = runTest {
        val mat = service.enroll("uuid-dis", "DIS")
        val secret = base32Decode(mat.secretBase32)
        val now = Instant.now().epochSecond / 30

        // Confirm with a valid code to activate TOTP.
        val confirmCode = formatCode(generateTotp(secret, now - 1))
        assertTrue(service.confirm("uuid-dis", confirmCode), "Setup: confirm should succeed")
        assertTrue(service.isEnabled("uuid-dis"), "Setup: TOTP should be enabled")

        // Attempt disable with wrong code.
        val disabled = service.disable("uuid-dis", "000000")
        assertFalse(disabled, "disable with wrong code must return false")
        assertTrue(service.isEnabled("uuid-dis"), "TOTP must remain enabled after failed disable")
    }

    // ── Recovery code accounting ────────────────────────────────────────────

    /**
     * Each successful recovery-code use decrements recoveryCodesRemaining by
     * exactly one. After consuming 3 codes the count goes from 10 → 9 → 8 → 7.
     */
    @Test
    fun `recoveryCodesRemaining decrements on each use`() = runTest {
        val mat = service.enroll("uuid-rcr", "RCR")

        assertEquals(10, service.recoveryCodesRemaining("uuid-rcr"), "Should start with 10 recovery codes")

        // Consume first three codes one by one and verify the count each time.
        for (i in 1..3) {
            val code = mat.recoveryCodes[i - 1]
            assertTrue(
                service.verifyForLogin("uuid-rcr", code),
                "Recovery code $i should be accepted"
            )
            assertEquals(
                10 - i,
                service.recoveryCodesRemaining("uuid-rcr"),
                "Remaining count should be ${10 - i} after consuming $i codes"
            )
        }
    }

    /**
     * Enrolling again (re-enrollment) for the same UUID replaces the previous
     * pending enrollment's recovery codes — the old codes are not usable.
     */
    @Test
    fun `re-enroll replaces pending recovery codes`() = runTest {
        val mat1 = service.enroll("uuid-reenroll", "RE1")
        val firstCode = mat1.recoveryCodes.first()

        // Re-enroll before confirming — replaces the previous secret + codes.
        service.enroll("uuid-reenroll", "RE2")

        // The code from the first enrollment must now be invalid.
        assertFalse(
            service.verifyForLogin("uuid-reenroll", firstCode),
            "Recovery code from first enrollment must be invalid after re-enroll"
        )

        // Fresh enrollment has 10 new codes.
        assertEquals(10, service.recoveryCodesRemaining("uuid-reenroll"))
    }

    /**
     * A code from a future step (step+1) outside the window boundary should be
     * rejected, ensuring the window is truly bounded on both sides.
     */
    @Test
    fun `code from future step beyond window is rejected`() = runTest {
        config = AuthConfig(totp = TotpConfig(window = 1))
        val mat = service.enroll("uuid-fut", "FUT")
        val secret = base32Decode(mat.secretBase32)
        val now = Instant.now().epochSecond / 30

        // Step +2 is strictly beyond window=1.
        val futureCode = formatCode(generateTotp(secret, now + 2))
        assertFalse(
            service.verifyForLogin("uuid-fut", futureCode),
            "Code from step+2 must be rejected when window=1"
        )
    }

    // ── Helpers — RFC 6238 reference implementation ─────────────────────────

    private fun generateTotp(secret: ByteArray, step: Long): Int {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val counter = ByteBuffer.allocate(8).putLong(step).array()
        val hmac = mac.doFinal(counter)
        val offset = hmac[hmac.size - 1].toInt() and 0x0f
        val binary = ((hmac[offset].toInt() and 0x7f) shl 24) or
            ((hmac[offset + 1].toInt() and 0xff) shl 16) or
            ((hmac[offset + 2].toInt() and 0xff) shl 8) or
            (hmac[offset + 3].toInt() and 0xff)
        return binary % 1_000_000
    }

    private fun formatCode(n: Int) = "%06d".format(n)

    private fun base32Decode(s: String): ByteArray {
        val alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = s.uppercase().replace("=", "")
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = alpha.indexOf(c)
            if (v < 0) continue
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
