package dev.nimbuspowered.nimbus.module.punishments

import dev.nimbuspowered.nimbus.database.DatabaseManager
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistence + business logic for punishments.
 *
 * Hot paths (login / connect / chat checks) are served from in-memory indexes
 * that mirror active bans/mutes. They're invalidated on every mutation and
 * reloaded from DB during [init]. Scope filtering happens in memory against
 * these indexes so scoped bans don't need an extra DB hop on the hot path.
 */
class PunishmentManager(private val db: DatabaseManager) {

    private val logger = LoggerFactory.getLogger(PunishmentManager::class.java)

    // Indexed by target UUID — all active login-blocking records (NETWORK + scoped).
    // Callers filter by scope at read time.
    private val activeBans = ConcurrentHashMap<String, MutableList<PunishmentRecord>>()
    private val activeBansByIp = ConcurrentHashMap<String, MutableList<PunishmentRecord>>()
    private val activeMutes = ConcurrentHashMap<String, MutableList<PunishmentRecord>>()

    /** Load active punishments into the in-memory cache. Call after migrations have run. */
    suspend fun init() {
        newSuspendedTransaction(Dispatchers.IO, db.database) {
            activeBans.clear()
            activeBansByIp.clear()
            activeMutes.clear()
            Punishments.selectAll().where { Punishments.active eq true }
                .forEach { row ->
                    val record = row.toRecord()
                    indexCache(record)
                }
            logger.info(
                "Loaded active punishments: {} login-blocking, {} IP, {} mutes (across all scopes)",
                activeBans.values.sumOf { it.size },
                activeBansByIp.values.sumOf { it.size },
                activeMutes.values.sumOf { it.size }
            )
        }
    }

    private fun indexCache(record: PunishmentRecord) {
        when {
            record.type == PunishmentType.IPBAN && record.targetIp != null ->
                activeBansByIp.computeIfAbsent(record.targetIp) { mutableListOf() }.add(record)
            record.type.blocksLogin() ->
                activeBans.computeIfAbsent(record.targetUuid) { mutableListOf() }.add(record)
            record.type.blocksChat() ->
                activeMutes.computeIfAbsent(record.targetUuid) { mutableListOf() }.add(record)
            else -> {}
        }
    }

    private fun unindexCache(record: PunishmentRecord) {
        fun <T> remove(map: ConcurrentHashMap<T, MutableList<PunishmentRecord>>, key: T) {
            map[key]?.let { list ->
                list.removeAll { it.id == record.id }
                if (list.isEmpty()) map.remove(key)
            }
        }
        when {
            record.type == PunishmentType.IPBAN && record.targetIp != null -> remove(activeBansByIp, record.targetIp)
            record.type.blocksLogin() -> remove(activeBans, record.targetUuid)
            record.type.blocksChat() -> remove(activeMutes, record.targetUuid)
        }
    }

    // ── Hot-path reads ────────────────────────────────────────────

    /**
     * Login-time check: does the player have a NETWORK-scoped ban that should
     * deny proxy login entirely? Group/service-scoped bans do NOT fire here —
     * those are handled per-connection by [checkConnectCached].
     */
    fun checkLoginCached(uuid: String, ip: String?): PunishmentRecord? {
        activeBans[uuid]?.firstOrNull { it.scope == PunishmentScope.NETWORK && !it.isExpired() }?.let { return it }
        if (ip != null) {
            activeBansByIp[ip]?.firstOrNull { it.scope == PunishmentScope.NETWORK && !it.isExpired() }?.let { return it }
        }
        return null
    }

    /**
     * Per-connection check: returns any active ban that applies when the player
     * tries to enter a specific `group`/`service`. Covers NETWORK + GROUP-matching
     * + SERVICE-matching bans. Used by ServerPreConnectEvent on the proxy.
     */
    fun checkConnectCached(uuid: String, ip: String?, group: String?, service: String?): PunishmentRecord? {
        val matches = buildList {
            activeBans[uuid]?.let { addAll(it) }
            if (ip != null) activeBansByIp[ip]?.let { addAll(it) }
        }
        return matches.firstOrNull { !it.isExpired() && it.appliesIn(group, service) }
    }

    /**
     * Chat-time check: returns any active mute that applies for the given context.
     * Pass the player's current `group` + `service` so scoped mutes only fire there.
     */
    fun checkMuteCached(uuid: String, group: String?, service: String?): PunishmentRecord? {
        return activeMutes[uuid]?.firstOrNull { !it.isExpired() && it.appliesIn(group, service) }
    }

    suspend fun getById(id: Int): PunishmentRecord? = newSuspendedTransaction(Dispatchers.IO, db.database) {
        Punishments.selectAll().where { Punishments.id eq id }.firstOrNull()?.toRecord()
    }

    suspend fun getHistory(uuid: String, limit: Int = 100): List<PunishmentRecord> =
        newSuspendedTransaction(Dispatchers.IO, db.database) {
            Punishments.selectAll().where { Punishments.targetUuid eq uuid }
                .orderBy(Punishments.issuedAt, SortOrder.DESC)
                .limit(limit)
                .map { it.toRecord() }
        }

    suspend fun list(activeOnly: Boolean, type: PunishmentType?, limit: Int, offset: Int): List<PunishmentRecord> =
        newSuspendedTransaction(Dispatchers.IO, db.database) {
            val query = Punishments.selectAll()
            if (activeOnly && type != null) {
                query.andWhere { (Punishments.active eq true) and (Punishments.type eq type.name) }
            } else if (activeOnly) {
                query.andWhere { Punishments.active eq true }
            } else if (type != null) {
                query.andWhere { Punishments.type eq type.name }
            }
            query.orderBy(Punishments.issuedAt, SortOrder.DESC)
                .limit(limit, offset.toLong())
                .map { it.toRecord() }
        }

    // ── Write ────────────────────────────────────────────────────

    /**
     * Issue a new punishment. Supersedes any prior active record of the same
     * *class* (login-blocking vs chat-blocking) against the same target and
     * scope — keeps only one active ban/mute per target+scope so revoking
     * one record doesn't leave a hidden second one in place.
     */
    suspend fun issue(
        type: PunishmentType,
        targetUuid: String,
        targetName: String,
        targetIp: String?,
        duration: Duration?,
        reason: String,
        issuer: String,
        issuerName: String,
        scope: PunishmentScope = PunishmentScope.NETWORK,
        scopeTarget: String? = null
    ): PunishmentRecord {
        val now = Instant.now()
        val expiresAt = duration?.let { now.plus(it) }

        val record = newSuspendedTransaction(Dispatchers.IO, db.database) {
            val superTypes = when {
                type.blocksLogin() -> listOf(PunishmentType.BAN.name, PunishmentType.TEMPBAN.name, PunishmentType.IPBAN.name)
                type.blocksChat() -> listOf(PunishmentType.MUTE.name, PunishmentType.TEMPMUTE.name)
                else -> emptyList()
            }
            if (superTypes.isNotEmpty()) {
                val scopeMatch: Op<Boolean> = if (scopeTarget == null) {
                    (Punishments.scope eq scope.name) and (Punishments.scopeTarget eq null as String?)
                } else {
                    (Punishments.scope eq scope.name) and (Punishments.scopeTarget eq scopeTarget)
                }
                Punishments.update({
                    (Punishments.targetUuid eq targetUuid) and
                    (Punishments.active eq true) and
                    (Punishments.type.inList(superTypes)) and
                    scopeMatch
                }) {
                    it[active] = false
                    it[revokedBy] = issuer
                    it[revokedAt] = now.toString()
                    it[revokeReason] = "Superseded by new punishment"
                }
            }

            val storeActive = type.isRevocable()

            val id = Punishments.insertAndGetId {
                it[Punishments.type] = type.name
                it[Punishments.targetUuid] = targetUuid
                it[Punishments.targetName] = targetName
                it[Punishments.targetIp] = targetIp
                it[Punishments.reason] = reason
                it[Punishments.issuer] = issuer
                it[Punishments.issuerName] = issuerName
                it[Punishments.issuedAt] = now.toString()
                it[Punishments.expiresAt] = expiresAt?.toString()
                it[Punishments.active] = storeActive
                it[Punishments.scope] = scope.name
                it[Punishments.scopeTarget] = scopeTarget
            }

            PunishmentRecord(
                id = id.value,
                type = type,
                targetUuid = targetUuid,
                targetName = targetName,
                targetIp = targetIp,
                reason = reason,
                issuer = issuer,
                issuerName = issuerName,
                issuedAt = now.toString(),
                expiresAt = expiresAt?.toString(),
                active = storeActive,
                revokedBy = null,
                revokedAt = null,
                revokeReason = null,
                scope = scope,
                scopeTarget = scopeTarget
            )
        }

        // Refresh cache after commit — remove superseded entries + add the new one
        rebuildIndexForTarget(targetUuid)
        if (targetIp != null) rebuildIpIndex(targetIp)

        return record
    }

    /**
     * Revoke an active punishment (unban/unmute). Returns the revoked record or null
     * if there was nothing to revoke.
     */
    suspend fun revoke(id: Int, revokedBy: String, reason: String?): PunishmentRecord? {
        val updated = newSuspendedTransaction(Dispatchers.IO, db.database) {
            val row = Punishments.selectAll().where { Punishments.id eq id }.firstOrNull()
                ?: return@newSuspendedTransaction null
            if (!row[Punishments.active]) return@newSuspendedTransaction null
            val now = Instant.now().toString()
            Punishments.update({ Punishments.id eq id }) {
                it[active] = false
                it[Punishments.revokedBy] = revokedBy
                it[Punishments.revokedAt] = now
                it[Punishments.revokeReason] = reason
            }
            Punishments.selectAll().where { Punishments.id eq id }.first().toRecord()
        } ?: return null

        unindexCache(updated)
        return updated
    }

    /**
     * Find the most recent active login-blocking punishment for a player (by uuid or name),
     * limited to a particular scope. `scope` + `scopeTarget` null → only NETWORK matches,
     * mirroring `punish unban <player>` where no --group/--service flag was given.
     */
    suspend fun findActiveBan(
        targetUuidOrName: String,
        scope: PunishmentScope = PunishmentScope.NETWORK,
        scopeTarget: String? = null
    ): PunishmentRecord? = newSuspendedTransaction(Dispatchers.IO, db.database) {
        val scopeMatch = if (scopeTarget == null) {
            (Punishments.scope eq scope.name) and (Punishments.scopeTarget eq null as String?)
        } else {
            (Punishments.scope eq scope.name) and (Punishments.scopeTarget eq scopeTarget)
        }
        Punishments.selectAll().where {
            (Punishments.active eq true) and
            ((Punishments.targetUuid eq targetUuidOrName) or (Punishments.targetName eq targetUuidOrName)) and
            (Punishments.type.inList(listOf(PunishmentType.BAN.name, PunishmentType.TEMPBAN.name, PunishmentType.IPBAN.name))) and
            scopeMatch
        }
            .orderBy(Punishments.issuedAt, SortOrder.DESC)
            .firstOrNull()?.toRecord()
    }

    suspend fun findActiveMute(
        targetUuidOrName: String,
        scope: PunishmentScope = PunishmentScope.NETWORK,
        scopeTarget: String? = null
    ): PunishmentRecord? = newSuspendedTransaction(Dispatchers.IO, db.database) {
        val scopeMatch = if (scopeTarget == null) {
            (Punishments.scope eq scope.name) and (Punishments.scopeTarget eq null as String?)
        } else {
            (Punishments.scope eq scope.name) and (Punishments.scopeTarget eq scopeTarget)
        }
        Punishments.selectAll().where {
            (Punishments.active eq true) and
            ((Punishments.targetUuid eq targetUuidOrName) or (Punishments.targetName eq targetUuidOrName)) and
            (Punishments.type.inList(listOf(PunishmentType.MUTE.name, PunishmentType.TEMPMUTE.name))) and
            scopeMatch
        }
            .orderBy(Punishments.issuedAt, SortOrder.DESC)
            .firstOrNull()?.toRecord()
    }

    /**
     * Deactivate tempbans/tempmutes whose `expires_at` has passed. Returns the
     * records that were expired so callers can emit events for them.
     */
    suspend fun expireOverdue(): List<PunishmentRecord> {
        val nowIso = Instant.now().toString()
        val expired = newSuspendedTransaction(Dispatchers.IO, db.database) {
            val candidates = Punishments.selectAll().where {
                (Punishments.active eq true) and
                Punishments.expiresAt.isNotNull() and
                (Punishments.expiresAt less nowIso)
            }.map { it.toRecord() }

            if (candidates.isNotEmpty()) {
                Punishments.update({
                    (Punishments.active eq true) and
                    Punishments.expiresAt.isNotNull() and
                    (Punishments.expiresAt less nowIso)
                }) {
                    it[active] = false
                }
            }
            candidates
        }

        expired.forEach { unindexCache(it) }
        return expired
    }

    // ── Cache maintenance helpers ────────────────────────────────

    private suspend fun rebuildIndexForTarget(uuid: String) {
        val fresh = newSuspendedTransaction(Dispatchers.IO, db.database) {
            Punishments.selectAll().where {
                (Punishments.targetUuid eq uuid) and (Punishments.active eq true)
            }.map { it.toRecord() }
        }
        activeBans.remove(uuid)
        activeMutes.remove(uuid)
        fresh.forEach { indexCache(it) }
    }

    private suspend fun rebuildIpIndex(ip: String) {
        val fresh = newSuspendedTransaction(Dispatchers.IO, db.database) {
            Punishments.selectAll().where {
                (Punishments.targetIp eq ip) and (Punishments.active eq true) and
                (Punishments.type eq PunishmentType.IPBAN.name)
            }.map { it.toRecord() }
        }
        activeBansByIp.remove(ip)
        fresh.forEach { indexCache(it) }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun ResultRow.toRecord() = PunishmentRecord(
        id = this[Punishments.id].value,
        type = PunishmentType.valueOf(this[Punishments.type]),
        targetUuid = this[Punishments.targetUuid],
        targetName = this[Punishments.targetName],
        targetIp = this[Punishments.targetIp],
        reason = this[Punishments.reason],
        issuer = this[Punishments.issuer],
        issuerName = this[Punishments.issuerName],
        issuedAt = this[Punishments.issuedAt],
        expiresAt = this[Punishments.expiresAt],
        active = this[Punishments.active],
        revokedBy = this[Punishments.revokedBy],
        revokedAt = this[Punishments.revokedAt],
        revokeReason = this[Punishments.revokeReason],
        scope = runCatching { PunishmentScope.valueOf(this[Punishments.scope]) }.getOrDefault(PunishmentScope.NETWORK),
        scopeTarget = this[Punishments.scopeTarget]
    )

    private fun PunishmentRecord.isExpired(): Boolean {
        val iso = expiresAt ?: return false
        return try {
            Instant.parse(iso).isBefore(Instant.now())
        } catch (_: Exception) { false }
    }
}
