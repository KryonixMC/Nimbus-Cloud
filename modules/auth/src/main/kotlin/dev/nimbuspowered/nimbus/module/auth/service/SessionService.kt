package dev.nimbuspowered.nimbus.module.auth.service

import dev.nimbuspowered.nimbus.database.DatabaseManager
import dev.nimbuspowered.nimbus.module.AuthPrincipal
import dev.nimbuspowered.nimbus.module.PermissionSet
import dev.nimbuspowered.nimbus.module.auth.AuthConfig
import dev.nimbuspowered.nimbus.module.auth.db.DashboardSessions
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

data class IssuedSession(
    val rawToken: String,
    val principal: AuthPrincipal.UserSession
)

/**
 * Creates, validates, refreshes, and revokes dashboard sessions.
 *
 * Tokens are 256-bit URL-safe random strings. Only `sha256(token)` is persisted.
 */
class SessionService(
    private val db: DatabaseManager,
    private val config: () -> AuthConfig
) {
    private val secureRandom = SecureRandom()
    private val snapshotJson = Json { ignoreUnknownKeys = true }
    private val snapshotSerializer = ListSerializer(String.serializer())

    /** Permissions are persisted as a JSON array so nodes containing commas
     *  round-trip cleanly. Legacy comma-joined snapshots are still readable. */
    private fun encodeSnapshot(permissions: PermissionSet): String =
        snapshotJson.encodeToString(snapshotSerializer, permissions.asSet().toList())

    private fun decodeSnapshot(raw: String): Set<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptySet()
        if (trimmed.startsWith("[")) {
            return runCatching {
                snapshotJson.decodeFromString(snapshotSerializer, trimmed).toSet()
            }.getOrElse { emptySet() }
        }
        // Pre-0.11.1 rows used comma-joined strings. Keep the decoder
        // backwards-compatible so old sessions survive the upgrade.
        return trimmed.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * Issue a new session for the given user. Enforces `max_per_user` by revoking
     * the oldest sessions if the limit would be exceeded.
     *
     * Permissions Phase 1: [permissions] is typically [PermissionSet.EMPTY] or a
     * stub — real resolution lands in Phase 2 (Perms module integration).
     */
    suspend fun issue(
        uuid: UUID,
        name: String,
        permissions: PermissionSet,
        ip: String?,
        userAgent: String?,
        loginMethod: String
    ): IssuedSession {
        val cfg = config().sessions
        val now = System.currentTimeMillis()
        val expiresAt = now + cfg.ttlSeconds * 1000L
        val raw = generateToken()
        val hash = LoginChallengeService.sha256Hex(raw)
        val sessionId = hash.take(16)

        val snapshot = encodeSnapshot(permissions)

        newSuspendedTransaction(Dispatchers.IO, db.database) {
            // Evict oldest sessions if we're over max_per_user (non-revoked, non-expired).
            val existing = DashboardSessions
                .selectAll()
                .where {
                    (DashboardSessions.uuid eq uuid.toString()) and
                        (DashboardSessions.revoked eq false) and
                        (DashboardSessions.expiresAt greaterEq now)
                }
                .orderBy(DashboardSessions.createdAt, SortOrder.ASC)
                .toList()
            val toEvict = (existing.size - (cfg.maxPerUser - 1)).coerceAtLeast(0)
            existing.take(toEvict).forEach { row ->
                DashboardSessions.update({ DashboardSessions.tokenHash eq row[DashboardSessions.tokenHash] }) {
                    it[revoked] = true
                }
            }

            DashboardSessions.insert {
                it[tokenHash] = hash
                it[DashboardSessions.uuid] = uuid.toString()
                it[DashboardSessions.name] = name.take(16)
                it[createdAt] = now
                it[DashboardSessions.expiresAt] = expiresAt
                it[lastUsedAt] = now
                it[DashboardSessions.ip] = ip?.take(45)
                it[DashboardSessions.userAgent] = userAgent?.take(255)
                it[revoked] = false
                it[DashboardSessions.loginMethod] = loginMethod.take(16)
                it[permissionsSnapshot] = snapshot
            }
        }

        return IssuedSession(
            rawToken = raw,
            principal = AuthPrincipal.UserSession(
                uuid = uuid,
                name = name,
                permissions = permissions,
                sessionId = sessionId,
                expiresAt = expiresAt
            )
        )
    }

    /**
     * Validate a raw session token. Returns the user session principal if the
     * token is active, non-revoked, and not expired. If rolling refresh is
     * enabled, also extends `expires_at` and updates `last_used_at`.
     */
    suspend fun validate(rawToken: String): AuthPrincipal.UserSession? {
        val hash = LoginChallengeService.sha256Hex(rawToken.trim())
        val cfg = config().sessions
        val now = System.currentTimeMillis()

        return newSuspendedTransaction(Dispatchers.IO, db.database) {
            // Lazy pruning of expired/revoked sessions
            DashboardSessions.deleteWhere {
                (DashboardSessions.expiresAt less (now - 86_400_000L)) or (DashboardSessions.revoked eq true)
            }

            val row = DashboardSessions.selectAll()
                .where { DashboardSessions.tokenHash eq hash }
                .firstOrNull() ?: return@newSuspendedTransaction null
            if (row[DashboardSessions.revoked]) return@newSuspendedTransaction null
            if (row[DashboardSessions.expiresAt] < now) return@newSuspendedTransaction null

            val newExpires = if (cfg.rollingRefresh) now + cfg.ttlSeconds * 1000L else row[DashboardSessions.expiresAt]
            DashboardSessions.update({ DashboardSessions.tokenHash eq hash }) {
                it[lastUsedAt] = now
                if (cfg.rollingRefresh) it[expiresAt] = newExpires
            }

            val permSet = decodeSnapshot(row[DashboardSessions.permissionsSnapshot])

            AuthPrincipal.UserSession(
                uuid = runCatching { UUID.fromString(row[DashboardSessions.uuid]) }.getOrNull()
                    ?: return@newSuspendedTransaction null,
                name = row[DashboardSessions.name],
                permissions = PermissionSet(permSet),
                sessionId = hash.take(16),
                expiresAt = newExpires
            )
        }
    }

    /**
     * Refresh the cached `permissions_snapshot` for every active session of a
     * given UUID. Called from the `PermissionsChanged` event subscription so
     * group/permission mutations take effect without waiting for re-login.
     *
     * Returns the number of sessions updated.
     */
    suspend fun refreshPermissionsFor(uuid: String, permissions: PermissionSet): Int {
        val snapshot = encodeSnapshot(permissions)
        return newSuspendedTransaction(Dispatchers.IO, db.database) {
            DashboardSessions.update({
                (DashboardSessions.uuid eq uuid) and
                    (DashboardSessions.revoked eq false)
            }) {
                it[permissionsSnapshot] = snapshot
            }
        }
    }

    /**
     * Refresh permissions for every active session by re-resolving each
     * unique UUID via [resolve]. Used on group-scope perms mutations (group
     * create/update/delete, track create/delete) where any group member may
     * be affected.
     */
    suspend fun refreshAllActive(resolve: (String) -> PermissionSet): Int {
        val now = System.currentTimeMillis()
        val uuids = newSuspendedTransaction(Dispatchers.IO, db.database) {
            DashboardSessions
                .selectAll()
                .where {
                    (DashboardSessions.revoked eq false) and
                        (DashboardSessions.expiresAt greaterEq now)
                }
                .map { it[DashboardSessions.uuid] }
                .toSet()
        }
        var total = 0
        for (uuid in uuids) {
            total += refreshPermissionsFor(uuid, resolve(uuid))
        }
        return total
    }

    suspend fun revoke(rawToken: String): Boolean {
        val hash = LoginChallengeService.sha256Hex(rawToken.trim())
        return newSuspendedTransaction(Dispatchers.IO, db.database) {
            DashboardSessions.update({ DashboardSessions.tokenHash eq hash }) {
                it[revoked] = true
            } > 0
        }
    }

    /**
     * Revoke a single session belonging to [uuid], matched by its public
     * `sessionId` (the first 16 hex chars of the token hash). Matching is
     * done in-process because `max_per_user` caps the row count tight
     * enough that a full-row scan per uuid is cheaper than smuggling a
     * cross-DB substring function into the SQL.
     *
     * Returns whether a matching session was found and updated; callers
     * should treat false as "not found" (avoids leaking existence).
     */
    suspend fun revokeOwnedSession(uuid: UUID, sessionId: String): Boolean {
        if (sessionId.isBlank() || sessionId.length > 64) return false
        return newSuspendedTransaction(Dispatchers.IO, db.database) {
            val match = DashboardSessions.selectAll()
                .where { (DashboardSessions.uuid eq uuid.toString()) and (DashboardSessions.revoked eq false) }
                .firstOrNull { it[DashboardSessions.tokenHash].startsWith(sessionId) }
                ?: return@newSuspendedTransaction false
            val hash = match[DashboardSessions.tokenHash]
            DashboardSessions.update({ DashboardSessions.tokenHash eq hash }) {
                it[revoked] = true
            } > 0
        }
    }

    /**
     * Revoke every session for [uuid] except the one identified by
     * [keepSessionId]. Returns the number of rows flipped.
     */
    suspend fun revokeOtherSessions(uuid: UUID, keepSessionId: String): Int {
        return newSuspendedTransaction(Dispatchers.IO, db.database) {
            val rows = DashboardSessions.selectAll()
                .where { (DashboardSessions.uuid eq uuid.toString()) and (DashboardSessions.revoked eq false) }
                .toList()
            var revokedCount = 0
            for (row in rows) {
                val hash = row[DashboardSessions.tokenHash]
                if (hash.startsWith(keepSessionId)) continue
                DashboardSessions.update({ DashboardSessions.tokenHash eq hash }) {
                    it[revoked] = true
                }
                revokedCount++
            }
            revokedCount
        }
    }

    suspend fun revokeAll(uuid: UUID): Int {
        return newSuspendedTransaction(Dispatchers.IO, db.database) {
            DashboardSessions.update({ DashboardSessions.uuid eq uuid.toString() }) {
                it[revoked] = true
            }
        }
    }

    /** Summary of an active session, returned by `/api/auth/sessions`. */
    data class SessionSummary(
        val sessionId: String,
        val name: String,
        val createdAt: Long,
        val expiresAt: Long,
        val lastUsedAt: Long,
        val ip: String?,
        val userAgent: String?,
        val loginMethod: String
    )

    /**
     * List active (non-revoked, non-expired) sessions for a given uuid.
     * Used by the in-game `/dashboard sessions` command and the future
     * `Profile -> Sessions` dashboard page.
     */
    suspend fun listForUser(uuid: UUID): List<SessionSummary> {
        val now = System.currentTimeMillis()
        return newSuspendedTransaction(Dispatchers.IO, db.database) {
            DashboardSessions.selectAll()
                .where {
                    (DashboardSessions.uuid eq uuid.toString()) and
                        (DashboardSessions.revoked eq false) and
                        (DashboardSessions.expiresAt greaterEq now)
                }
                .orderBy(DashboardSessions.lastUsedAt, SortOrder.DESC)
                .map { row ->
                    SessionSummary(
                        sessionId = row[DashboardSessions.tokenHash].take(16),
                        name = row[DashboardSessions.name],
                        createdAt = row[DashboardSessions.createdAt],
                        expiresAt = row[DashboardSessions.expiresAt],
                        lastUsedAt = row[DashboardSessions.lastUsedAt],
                        ip = row[DashboardSessions.ip],
                        userAgent = row[DashboardSessions.userAgent],
                        loginMethod = row[DashboardSessions.loginMethod]
                    )
                }
        }
    }

    private fun generateToken(): String {
        val buf = ByteArray(32)
        secureRandom.nextBytes(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }
}

