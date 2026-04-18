package dev.nimbuspowered.nimbus.module.auth.service

import dev.nimbuspowered.nimbus.database.DatabaseManager
import dev.nimbuspowered.nimbus.module.auth.AuthConfig
import dev.nimbuspowered.nimbus.module.auth.db.DashboardLoginChallenges
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

enum class ChallengeKind(val wire: String) {
    CODE("code"),
    MAGIC_LINK("magic_link");

    companion object {
        fun fromWire(s: String): ChallengeKind? = entries.firstOrNull { it.wire == s }
    }
}

data class IssuedChallenge(
    val raw: String,
    val kind: ChallengeKind,
    val uuid: String,
    val name: String,
    val expiresAt: Long
)

data class ConsumedChallenge(
    val kind: ChallengeKind,
    val uuid: String,
    val name: String,
    val originIp: String?
)

/**
 * Issues and consumes short-lived login challenges.
 *
 * - 6-digit numeric code for the `/dashboard login` flow
 * - 256-bit URL-safe random token for the magic-link flow
 *
 * Raw secrets are never stored — only `sha256(secret)`. A single-use consume
 * operation atomically flips the `consumed` flag so replays are impossible.
 *
 * A small in-process rate limiter (N generates / minute / uuid) protects
 * against brute-forcing 6-digit codes within the 60s TTL.
 */
class LoginChallengeService(
    private val db: DatabaseManager,
    private val config: () -> AuthConfig
) {
    private val logger = LoggerFactory.getLogger(LoginChallengeService::class.java)
    private val secureRandom = SecureRandom()

    class RateLimitedException(msg: String) : RuntimeException(msg)

    suspend fun issueCode(uuid: String, name: String): IssuedChallenge =
        issue(uuid, name, ChallengeKind.CODE, originIp = null)

    suspend fun issueMagicLink(uuid: String, name: String, originIp: String?): IssuedChallenge {
        if (!config().loginChallenge.magicLinkEnabled) {
            throw IllegalStateException("Magic link login is disabled")
        }
        return issue(uuid, name, ChallengeKind.MAGIC_LINK, originIp)
    }

    private suspend fun issue(uuid: String, name: String, kind: ChallengeKind, originIp: String?): IssuedChallenge {
        val cfg = config().loginChallenge

        val raw = when (kind) {
            ChallengeKind.CODE -> generateCode(cfg.codeLength, cfg.codeAlphabet)
            ChallengeKind.MAGIC_LINK -> generateMagicToken(cfg.magicLinkTokenBytes)
        }
        val ttlSec = when (kind) {
            ChallengeKind.CODE -> cfg.codeTtlSeconds
            ChallengeKind.MAGIC_LINK -> cfg.magicLinkTtlSeconds
        }
        val now = System.currentTimeMillis()
        val expiresAt = now + ttlSec * 1000L
        val hash = sha256Hex(raw)

        // Rate-limit check + issue happen in a single transaction so the
        // counter survives controller restarts (the `dashboard_login_challenges`
        // rows are the source of truth, not an in-memory map).
        newSuspendedTransaction(Dispatchers.IO, db.database) {
            // Prune expired rows lazily — keeps table slim without a dedicated loop.
            DashboardLoginChallenges.deleteWhere { DashboardLoginChallenges.expiresAt less now }

            val windowStart = now - 60_000
            val recent = DashboardLoginChallenges.selectAll()
                .where {
                    (DashboardLoginChallenges.uuid eq uuid) and
                        (DashboardLoginChallenges.createdAt greaterEq windowStart)
                }
                .count()
            if (recent >= cfg.maxGeneratesPerMinute.toLong()) {
                throw RateLimitedException("Too many login challenges requested for $uuid")
            }

            DashboardLoginChallenges.insert {
                it[challengeHash] = hash
                it[DashboardLoginChallenges.kind] = kind.wire
                it[DashboardLoginChallenges.uuid] = uuid
                it[DashboardLoginChallenges.name] = name
                it[createdAt] = now
                it[DashboardLoginChallenges.expiresAt] = expiresAt
                it[consumed] = false
                it[DashboardLoginChallenges.originIp] = originIp
            }
        }
        return IssuedChallenge(raw, kind, uuid, name, expiresAt)
    }

    /**
     * Atomically consume a challenge by its raw secret. Returns `null` if the
     * challenge does not exist, has already been consumed, or has expired.
     */
    suspend fun consume(raw: String): ConsumedChallenge? {
        val hash = sha256Hex(raw.trim())
        val now = System.currentTimeMillis()
        return newSuspendedTransaction(Dispatchers.IO, db.database) {
            val row = DashboardLoginChallenges.selectAll()
                .where { DashboardLoginChallenges.challengeHash eq hash }
                .firstOrNull() ?: return@newSuspendedTransaction null
            if (row[DashboardLoginChallenges.consumed]) return@newSuspendedTransaction null
            if (row[DashboardLoginChallenges.expiresAt] < now) return@newSuspendedTransaction null

            val updated = DashboardLoginChallenges.update({
                (DashboardLoginChallenges.challengeHash eq hash) and (DashboardLoginChallenges.consumed eq false)
            }) {
                it[consumed] = true
            }
            if (updated != 1) return@newSuspendedTransaction null  // Lost race

            val kind = ChallengeKind.fromWire(row[DashboardLoginChallenges.kind])
                ?: return@newSuspendedTransaction null
            ConsumedChallenge(
                kind = kind,
                uuid = row[DashboardLoginChallenges.uuid],
                name = row[DashboardLoginChallenges.name],
                originIp = row[DashboardLoginChallenges.originIp]
            )
        }
    }

    private fun generateCode(length: Int, alphabet: String): String {
        val alpha = when (alphabet.lowercase()) {
            "alphanumeric" -> "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  // Ambiguity-safe (no 0/O, 1/I, etc.)
            else -> "0123456789"
        }
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            sb.append(alpha[secureRandom.nextInt(alpha.length)])
        }
        return sb.toString()
    }

    private fun generateMagicToken(bytes: Int): String {
        val buf = ByteArray(bytes)
        secureRandom.nextBytes(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    companion object {
        fun sha256Hex(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
