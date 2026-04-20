package dev.nimbuspowered.nimbus.module.auth.service

import dev.nimbuspowered.nimbus.module.auth.AuthConfig
import dev.nimbuspowered.nimbus.module.auth.TotpConfig
import dev.nimbuspowered.nimbus.module.auth.buildAuthTestDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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

class TotpServiceTest {

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

    @Test
    fun `enroll produces base32 secret and 10 recovery codes`() = runTest {
        val material = service.enroll("uuid-1", "Player")
        assertEquals(32, material.secretBase32.length) // 20 bytes → 32 base32 chars
        assertTrue(material.secretBase32.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" })
        assertEquals(10, material.recoveryCodes.size)
        material.recoveryCodes.forEach { code ->
            // Display format: AAAA-AAAA
            assertEquals(9, code.length)
            assertEquals('-', code[4])
        }
        assertTrue(material.otpauthUri.startsWith("otpauth://totp/"))
        assertTrue(material.otpauthUri.contains("secret=${material.secretBase32}"))
    }

    @Test
    fun `state reflects pending enrollment before confirm`() = runTest {
        service.enroll("uuid-a", "A")
        val state = service.state("uuid-a")
        assertFalse(state.enabled)
        assertTrue(state.pendingEnrollment)
        assertFalse(service.isEnabled("uuid-a"))
    }

    @Test
    fun `confirm activates with valid code`() = runTest {
        val mat = service.enroll("uuid-b", "B")
        val secret = base32Decode(mat.secretBase32)
        val now = Instant.now().epochSecond / 30
        val code = formatCode(generateTotp(secret, now))
        assertTrue(service.confirm("uuid-b", code))
        assertTrue(service.isEnabled("uuid-b"))
    }

    @Test
    fun `verify rejects replay of same code`() = runTest {
        val mat = service.enroll("uuid-c", "C")
        val secret = base32Decode(mat.secretBase32)
        val now = Instant.now().epochSecond / 30
        val code = formatCode(generateTotp(secret, now))
        assertTrue(service.confirm("uuid-c", code))
        // Replay attempt within the same step / window must fail.
        assertFalse(service.verifyForLogin("uuid-c", code))
    }

    @Test
    fun `verify rejects wrong code`() = runTest {
        service.enroll("uuid-d", "D")
        assertFalse(service.verifyForLogin("uuid-d", "000000"))
        assertFalse(service.verifyForLogin("uuid-d", "abcdef"))
        assertFalse(service.verifyForLogin("uuid-d", "12345"))  // wrong length
    }

    @Test
    fun `verify accepts code within window tolerance`() = runTest {
        val mat = service.enroll("uuid-e", "E")
        val secret = base32Decode(mat.secretBase32)
        val now = Instant.now().epochSecond / 30
        // A code from the previous step should still be accepted (window = 1 default).
        val prevCode = formatCode(generateTotp(secret, now - 1))
        assertTrue(service.verifyForLogin("uuid-e", prevCode))
    }

    @Test
    fun `enroll stores 10 recovery code rows`() = runTest {
        service.enroll("uuid-f", "F")
        assertEquals(10, service.recoveryCodesRemaining("uuid-f"))
    }

    @Test
    fun `recovery codes for other users are not accepted for this user`() = runTest {
        service.enroll("uuid-g", "G")
        val matH = service.enroll("uuid-h", "H")
        // Regardless of whether the recovery-code path is wired up: a code
        // belonging to another user must never log this user in.
        assertFalse(service.verifyForLogin("uuid-g", matH.recoveryCodes.first()))
    }

    @Test
    fun `recovery code issued at enroll is accepted in either format`() = runTest {
        // Regression test: enroll produced `ABCD-1234` with dash, consume stripped
        // the dash before hashing — hashes never matched, recovery was broken in prod.
        val mat = service.enroll("uuid-rc", "RC")
        val dashed = mat.recoveryCodes.first()
        assertEquals('-', dashed[4])
        assertTrue(service.verifyForLogin("uuid-rc", dashed))

        val plain = mat.recoveryCodes[1].replace("-", "")
        assertTrue(service.verifyForLogin("uuid-rc", plain))
    }

    @Test
    fun `recovery code is single-use`() = runTest {
        val mat = service.enroll("uuid-rc2", "RC2")
        val code = mat.recoveryCodes.first()
        assertTrue(service.verifyForLogin("uuid-rc2", code))
        assertFalse(service.verifyForLogin("uuid-rc2", code))
        assertEquals(9, service.recoveryCodesRemaining("uuid-rc2"))
    }

    @Test
    fun `disable wipes secret and recovery codes when invoked with live TOTP`() = runTest {
        val mat = service.enroll("uuid-i", "I")
        val secret = base32Decode(mat.secretBase32)
        // Use the step-for-one-previous code to confirm (lastUsedStep = now-1),
        // then disable with the current step (> lastUsedStep, inside window).
        val step = Instant.now().epochSecond / 30
        service.confirm("uuid-i", formatCode(generateTotp(secret, step - 1)))
        assertTrue(service.isEnabled("uuid-i"))
        val disableCode = formatCode(generateTotp(secret, step))
        assertTrue(service.disable("uuid-i", disableCode))
        assertFalse(service.isEnabled("uuid-i"))
        assertEquals(0, service.recoveryCodesRemaining("uuid-i"))
    }

    @Test
    fun `AES-GCM roundtrip works - enroll then verify after restart`() = runTest {
        // Same TestDb can be reused only if backed by shared-cache; we just verify a
        // second service instance with the same key can decrypt the secret.
        val db = buildAuthTestDb(tmp)
        val s1 = TotpService(db, { config }, key)
        val mat = s1.enroll("uuid-j", "J")
        val secret = base32Decode(mat.secretBase32)
        val code = formatCode(generateTotp(secret, Instant.now().epochSecond / 30 + 1))

        val s2 = TotpService(db, { config }, key)
        assertTrue(s2.confirm("uuid-j", code))
    }

    @Test
    fun `wrong encryption key fails to verify`() = runTest {
        val db = buildAuthTestDb(tmp)
        val s1 = TotpService(db, { config }, key)
        val mat = s1.enroll("uuid-k", "K")
        val secret = base32Decode(mat.secretBase32)
        val code = formatCode(generateTotp(secret, Instant.now().epochSecond / 30))

        val wrongKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val s2 = TotpService(db, { config }, wrongKey)
        // Decrypt should fail silently — code is rejected.
        assertFalse(s2.verifyForLogin("uuid-k", code))
    }

    @Test
    fun `window is clamped to max 2`() = runTest {
        config = AuthConfig(totp = TotpConfig(window = 99))
        val mat = service.enroll("uuid-l", "L")
        val secret = base32Decode(mat.secretBase32)
        val now = Instant.now().epochSecond / 30
        // Step -3 is outside clamped window of 2 → must reject.
        val oldCode = formatCode(generateTotp(secret, now - 3))
        assertFalse(service.verifyForLogin("uuid-l", oldCode))
    }

    @Test
    fun `recovery code format is 8 alphanumerics split by dash`() = runTest {
        val mat = service.enroll("uuid-m", "M")
        mat.recoveryCodes.forEach { code ->
            assertEquals(9, code.length)
            assertEquals('-', code[4])
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            code.filter { it != '-' }.forEach { c -> assertTrue(c in alphabet) }
        }
    }

    // ── Helpers — reference RFC 6238 implementation ─────────────────────

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
