package dev.nimbuspowered.nimbus.module.auth.db

import org.jetbrains.exposed.sql.Table

/**
 * Dashboard session tokens.
 *
 * Only `sha256(token)` is stored — the raw token lives in the user's cookie /
 * header. Database leaks cannot be replayed as valid sessions.
 */
object DashboardSessions : Table("dashboard_sessions") {
    val tokenHash = varchar("token_hash", 64)
    val uuid = varchar("uuid", 36).index("idx_sessions_uuid")
    val name = varchar("name", 16)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at").index("idx_sessions_expires")
    val lastUsedAt = long("last_used_at")
    val ip = varchar("ip", 45).nullable()
    val userAgent = varchar("user_agent", 255).nullable()
    val revoked = bool("revoked").default(false)
    val loginMethod = varchar("login_method", 16).default("code")
    val permissionsSnapshot = text("permissions_snapshot").default("")

    override val primaryKey = PrimaryKey(tokenHash)
}

/**
 * Unified storage for login challenges — both 6-digit codes and magic-link tokens.
 * Kind column lets a single consume-challenge endpoint handle both without branching.
 */
object DashboardLoginChallenges : Table("dashboard_login_challenges") {
    val challengeHash = varchar("challenge_hash", 64)
    val kind = varchar("kind", 16)             // "code" or "magic_link"
    val uuid = varchar("uuid", 36).index("idx_challenges_uuid")
    val name = varchar("name", 16)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at").index("idx_challenges_expires")
    val consumed = bool("consumed").default(false)
    val originIp = varchar("origin_ip", 45).nullable()

    override val primaryKey = PrimaryKey(challengeHash)
}

/** TOTP secrets — AES-GCM encrypted with the module's session key. */
object DashboardTotp : Table("dashboard_totp") {
    val uuid = varchar("uuid", 36)
    val secretEnc = binary("secret_enc", 255)
    val enabled = bool("enabled").default(false)
    val enabledAt = long("enabled_at").nullable()

    /**
     * Highest RFC 6238 step value successfully accepted for this user. A valid
     * code must advance strictly past this step, so an observed code cannot be
     * replayed within the remainder of its 30-second window or any earlier
     * step inside the verify `window`.
     */
    val lastUsedStep = long("last_used_step").nullable()

    override val primaryKey = PrimaryKey(uuid)
}

/** One-time recovery codes, SHA-256 hashed. */
object DashboardRecoveryCodes : Table("dashboard_recovery_codes") {
    val codeHash = varchar("code_hash", 64)
    val uuid = varchar("uuid", 36).index("idx_recovery_uuid")
    val consumedAt = long("consumed_at").nullable()

    override val primaryKey = PrimaryKey(codeHash)
}

/**
 * WebAuthn / Passkey credentials. One user can register multiple devices.
 *
 * `credentialId` is the raw authenticator-assigned handle (base64url-encoded here for
 * storage convenience — the browser hands it back on every login). `publicKeyCose`
 * is the COSE_Key-encoded public key blob returned from registration. `signCount`
 * is the authenticator's monotonic counter used to detect cloned credentials.
 */
object DashboardWebAuthnCredentials : Table("dashboard_webauthn_credentials") {
    val credentialId = varchar("credential_id", 512)
    val uuid = varchar("uuid", 36).index("idx_webauthn_uuid")
    /** MC name captured at enrollment — used to issue sessions on usernameless login. */
    val mcName = varchar("mc_name", 16)
    val publicKeyCose = binary("public_key_cose", 512)
    val signCount = long("sign_count").default(0)
    /** User-facing device label ("MacBook Touch ID", "YubiKey 5C"). */
    val label = varchar("label", 64)
    val createdAt = long("created_at")
    val lastUsedAt = long("last_used_at").nullable()
    val aaguid = varchar("aaguid", 36).nullable()

    override val primaryKey = PrimaryKey(credentialId)
}
