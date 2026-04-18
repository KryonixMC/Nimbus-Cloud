package dev.nimbuspowered.nimbus.module.auth.service

import dev.nimbuspowered.nimbus.database.DatabaseManager
import dev.nimbuspowered.nimbus.module.auth.AuthConfig
import dev.nimbuspowered.nimbus.module.auth.db.DashboardRecoveryCodes
import dev.nimbuspowered.nimbus.module.auth.db.DashboardTotp
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * TOTP (RFC 6238) service — per-UUID secrets encrypted at rest with AES-GCM
 * using the auth module's session key. Also owns recovery codes (SHA-256
 * hashed, single-use).
 *
 * Secret size: 20 bytes (160 bits) — matches the RFC 4226 reference and
 * Google Authenticator's native format.
 *
 * Algorithm: HMAC-SHA1, 30-second step, 6 digits. These are the defaults
 * every authenticator app supports without user-visible configuration.
 */
class TotpService(
    private val db: DatabaseManager,
    private val config: () -> AuthConfig,
    private val encryptionKey: ByteArray
) {
    private val secureRandom = SecureRandom()

    data class EnrollmentMaterial(
        val secretBase32: String,
        val otpauthUri: String,
        val recoveryCodes: List<String>
    )

    data class EnrollmentState(
        val enabled: Boolean,
        val pendingEnrollment: Boolean
    )

    /**
     * Generates a fresh secret + 10 recovery codes for [uuid] and stores them
     * as `enabled = false`. The user must submit a valid TOTP via [confirm]
     * before the secret is activated. Any prior pending or active enrollment
     * for the same UUID is replaced.
     */
    suspend fun enroll(uuid: String, userName: String): EnrollmentMaterial {
        val secret = ByteArray(20).also { secureRandom.nextBytes(it) }
        val secretBase32 = base32Encode(secret)
        val encrypted = encrypt(secret)

        val recoveryCodes = List(10) { generateRecoveryCode() }
        val recoveryHashes = recoveryCodes.map { LoginChallengeService.sha256Hex(it) }

        newSuspendedTransaction(Dispatchers.IO, db.database) {
            // Replace any existing row — fresh enrollment wipes the slate.
            DashboardTotp.deleteWhere { DashboardTotp.uuid eq uuid }
            DashboardRecoveryCodes.deleteWhere { DashboardRecoveryCodes.uuid eq uuid }

            DashboardTotp.insert {
                it[DashboardTotp.uuid] = uuid
                it[secretEnc] = encrypted
                it[enabled] = false
                it[enabledAt] = null
            }
            recoveryHashes.forEach { hash ->
                DashboardRecoveryCodes.insert {
                    it[codeHash] = hash
                    it[DashboardRecoveryCodes.uuid] = uuid
                    it[consumedAt] = null
                }
            }
        }

        val otpauthUri = buildOtpauthUri(userName, secretBase32)
        return EnrollmentMaterial(secretBase32, otpauthUri, recoveryCodes)
    }

    /**
     * Activates an existing pending enrollment by verifying [code] against the
     * stored secret. Returns true on success. Safe to call repeatedly — if
     * TOTP is already enabled, returns true when the code verifies.
     */
    suspend fun confirm(uuid: String, code: String): Boolean {
        if (!verifyAndAdvance(uuid, code)) return false
        val now = System.currentTimeMillis()
        newSuspendedTransaction(Dispatchers.IO, db.database) {
            DashboardTotp.update({ DashboardTotp.uuid eq uuid }) {
                it[enabled] = true
                it[enabledAt] = now
            }
        }
        return true
    }

    /**
     * Disables TOTP for [uuid]. Requires a valid current TOTP code to prevent
     * attackers with a stolen session from turning off 2FA. Recovery codes are
     * wiped alongside the secret.
     */
    suspend fun disable(uuid: String, code: String): Boolean {
        // Accept either a live TOTP or a still-unused recovery code for the
        // disable operation — matches the "I lost my phone" escape hatch.
        val ok = verifyAndAdvance(uuid, code) || consumeRecoveryCode(uuid, code)
        if (!ok) return false
        newSuspendedTransaction(Dispatchers.IO, db.database) {
            DashboardTotp.deleteWhere { DashboardTotp.uuid eq uuid }
            DashboardRecoveryCodes.deleteWhere { DashboardRecoveryCodes.uuid eq uuid }
        }
        return true
    }

    /**
     * Verifies [code] as part of the login flow. Returns true if the code is
     * a valid live TOTP OR an unused recovery code. Both paths are replay-safe:
     * TOTP codes advance [DashboardTotp.lastUsedStep] atomically, recovery
     * codes flip their `consumedAt` flag.
     */
    suspend fun verifyForLogin(uuid: String, code: String): Boolean {
        if (verifyAndAdvance(uuid, code)) return true
        return consumeRecoveryCode(uuid, code)
    }

    /** True if [uuid] has an activated (confirmed) TOTP enrollment. */
    suspend fun isEnabled(uuid: String): Boolean {
        return newSuspendedTransaction(Dispatchers.IO, db.database) {
            DashboardTotp.selectAll()
                .where { (DashboardTotp.uuid eq uuid) and (DashboardTotp.enabled eq true) }
                .firstOrNull() != null
        }
    }

    suspend fun state(uuid: String): EnrollmentState {
        return newSuspendedTransaction(Dispatchers.IO, db.database) {
            val row = DashboardTotp.selectAll()
                .where { DashboardTotp.uuid eq uuid }
                .firstOrNull()
            if (row == null) EnrollmentState(enabled = false, pendingEnrollment = false)
            else EnrollmentState(
                enabled = row[DashboardTotp.enabled],
                pendingEnrollment = !row[DashboardTotp.enabled]
            )
        }
    }

    /** Number of unused recovery codes remaining. */
    suspend fun recoveryCodesRemaining(uuid: String): Int {
        return newSuspendedTransaction(Dispatchers.IO, db.database) {
            DashboardRecoveryCodes.selectAll()
                .where { (DashboardRecoveryCodes.uuid eq uuid) and (DashboardRecoveryCodes.consumedAt eq null) }
                .count().toInt()
        }
    }

    // ── TOTP core (RFC 6238) ────────────────────────────────────────────────

    /**
     * Matches [code] against the stored secret for [uuid] and advances the
     * per-user replay window in a single atomic transaction.
     *
     * RFC 6238 §5.2 requires that an accepted code not be re-accepted by the
     * same user within the same time step. We go a little further and reject
     * any matching step that is *less than or equal to* the most-recently
     * accepted step, so a late arrival inside the `window` tolerance can't
     * replay a code the user already used earlier in the same login burst.
     *
     * `window` is deliberately capped low (default 1 = ±30 s, hard max 2 =
     * ±60 s). Higher values would widen the replay window for any attacker
     * who can observe a code before the legitimate user submits it, which is
     * the main shoulder-surfing / MitM threat this guards against.
     */
    private suspend fun verifyAndAdvance(uuid: String, code: String): Boolean {
        val normalized = code.replace(" ", "").trim()
        if (!normalized.all { it.isDigit() } || normalized.length != 6) return false
        val expectedInt = normalized.toIntOrNull() ?: return false

        val window = config().totp.window.coerceIn(0, 2)
        val nowStep = Instant.now().epochSecond / 30

        return newSuspendedTransaction(Dispatchers.IO, db.database) {
            val row = DashboardTotp.selectAll()
                .where { DashboardTotp.uuid eq uuid }
                .firstOrNull() ?: return@newSuspendedTransaction false
            val secret = runCatching { decrypt(row[DashboardTotp.secretEnc]) }.getOrNull()
                ?: return@newSuspendedTransaction false
            val lastStep = row[DashboardTotp.lastUsedStep]

            // Walk the window from oldest to newest. Accept the first matching
            // step that is strictly greater than `lastStep`.
            for (offset in -window..window) {
                val candidateStep = nowStep + offset
                if (lastStep != null && candidateStep <= lastStep) continue
                if (generateCode(secret, candidateStep) != expectedInt) continue

                val updated = DashboardTotp.update({
                    (DashboardTotp.uuid eq uuid) and
                        (
                            (DashboardTotp.lastUsedStep.isNull()) or
                                (DashboardTotp.lastUsedStep less candidateStep)
                        )
                }) {
                    it[lastUsedStep] = candidateStep
                }
                return@newSuspendedTransaction updated == 1
            }
            false
        }
    }

    private fun generateCode(secret: ByteArray, step: Long): Int {
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

    private fun buildOtpauthUri(userName: String, secretBase32: String): String {
        val issuer = config().totp.issuer
        val label = "${url(issuer)}:${url(userName)}"
        return "otpauth://totp/$label?secret=$secretBase32&issuer=${url(issuer)}&algorithm=SHA1&digits=6&period=30"
    }

    private fun url(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    // ── Recovery codes ──────────────────────────────────────────────────────

    private suspend fun consumeRecoveryCode(uuid: String, rawCode: String): Boolean {
        // Accept with or without the dash the user sees (`ABCD-1234` vs `ABCD1234`).
        val canonical = rawCode.replace("-", "").uppercase().trim()
        if (canonical.length != 8) return false
        val hash = LoginChallengeService.sha256Hex(canonical)
        val now = System.currentTimeMillis()
        return newSuspendedTransaction(Dispatchers.IO, db.database) {
            val row = DashboardRecoveryCodes.selectAll()
                .where { DashboardRecoveryCodes.codeHash eq hash }
                .firstOrNull() ?: return@newSuspendedTransaction false
            if (row[DashboardRecoveryCodes.uuid] != uuid) return@newSuspendedTransaction false
            if (row[DashboardRecoveryCodes.consumedAt] != null) return@newSuspendedTransaction false
            DashboardRecoveryCodes.update({
                (DashboardRecoveryCodes.codeHash eq hash) and (DashboardRecoveryCodes.consumedAt eq null)
            }) {
                it[consumedAt] = now
            } == 1
        }
    }

    private fun generateRecoveryCode(): String {
        // 8 base32-safe alphanumerics, printed as `ABCD-1234` for readability.
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val sb = StringBuilder(9)
        for (i in 0 until 8) {
            if (i == 4) sb.append('-')
            sb.append(alphabet[secureRandom.nextInt(alphabet.length)])
        }
        return sb.toString()
    }

    // ── Secret crypto ───────────────────────────────────────────────────────

    /**
     * AES-GCM-256 with a per-record random 12-byte nonce. Layout:
     *   [ 12-byte nonce | ciphertext | 16-byte GCM tag ]
     *
     * Kept deliberately simple — the key is already derived from the on-disk
     * `session.key` file (chmod 600), so we don't need key rotation or a full
     * envelope scheme for Phase 4.
     */
    private fun encrypt(plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), GCMParameterSpec(128, nonce))
        val ct = cipher.doFinal(plaintext)
        return nonce + ct
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > 12) { "Ciphertext too short" }
        val nonce = blob.copyOfRange(0, 12)
        val ct = blob.copyOfRange(12, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), GCMParameterSpec(128, nonce))
        return cipher.doFinal(ct)
    }

    private fun base32Encode(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(alphabet[(buffer shr (bits - 5)) and 0x1f])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(alphabet[(buffer shl (5 - bits)) and 0x1f])
        return sb.toString()
    }

}
