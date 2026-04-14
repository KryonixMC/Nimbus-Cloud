package dev.nimbuspowered.nimbus.module.punishments

import org.jetbrains.exposed.dao.id.IntIdTable

/**
 * Network-wide punishments table.
 *
 * `active` is denormalized from `expires_at` so the hot login-check query
 * can use a partial index (active=true) instead of scanning.
 */
object Punishments : IntIdTable("punishments") {
    val type = varchar("type", 16)
    val targetUuid = varchar("target_uuid", 36)
    val targetName = varchar("target_name", 64)
    val targetIp = varchar("target_ip", 45).nullable()
    val reason = text("reason").default("")
    val issuer = varchar("issuer", 128)            // uuid of player OR "console" / "api:<name>"
    val issuerName = varchar("issuer_name", 64)
    val issuedAt = varchar("issued_at", 30)        // ISO-8601
    val expiresAt = varchar("expires_at", 30).nullable()
    val active = bool("active").default(true)
    val revokedBy = varchar("revoked_by", 128).nullable()
    val revokedAt = varchar("revoked_at", 30).nullable()
    val revokeReason = text("revoke_reason").nullable()

    // Scope: NETWORK (default) blocks login/mute everywhere; GROUP and SERVICE restrict
    // enforcement to a single target so staff can scope out of a specific game/lobby.
    val scope = varchar("scope", 16).default("NETWORK")
    val scopeTarget = varchar("scope_target", 128).nullable()

    init {
        index(false, targetUuid, type, active)
        index(false, targetIp, type, active)
        index(false, active)
        index(false, scope, scopeTarget)
    }
}
