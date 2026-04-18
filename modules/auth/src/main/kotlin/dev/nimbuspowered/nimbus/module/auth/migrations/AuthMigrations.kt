package dev.nimbuspowered.nimbus.module.auth.migrations

import dev.nimbuspowered.nimbus.module.Migration
import dev.nimbuspowered.nimbus.module.auth.db.DashboardLoginChallenges
import dev.nimbuspowered.nimbus.module.auth.db.DashboardRecoveryCodes
import dev.nimbuspowered.nimbus.module.auth.db.DashboardSessions
import dev.nimbuspowered.nimbus.module.auth.db.DashboardTotp
import dev.nimbuspowered.nimbus.module.auth.db.DashboardWebAuthnCredentials
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction

/** Version range 8000+ reserved for the auth module. */

object AuthV8000_Sessions : Migration {
    override val version = 8000
    override val description = "Dashboard sessions table (token hash, uuid, ttl, user-agent)"
    override val baseline = false
    override fun Transaction.migrate() {
        SchemaUtils.createMissingTablesAndColumns(DashboardSessions)
    }
}

object AuthV8001_Challenges : Migration {
    override val version = 8001
    override val description = "Unified login challenges table (code + magic link)"
    override val baseline = false
    override fun Transaction.migrate() {
        SchemaUtils.createMissingTablesAndColumns(DashboardLoginChallenges)
    }
}

object AuthV8002_Totp : Migration {
    override val version = 8002
    override val description = "TOTP secrets table (AES-GCM encrypted)"
    override val baseline = false
    override fun Transaction.migrate() {
        SchemaUtils.createMissingTablesAndColumns(DashboardTotp)
    }
}

object AuthV8003_RecoveryCodes : Migration {
    override val version = 8003
    override val description = "TOTP recovery codes (SHA-256 hashed, single-use)"
    override val baseline = false
    override fun Transaction.migrate() {
        SchemaUtils.createMissingTablesAndColumns(DashboardRecoveryCodes)
    }
}

object AuthV8004_WebAuthnCredentials : Migration {
    override val version = 8004
    override val description = "WebAuthn / Passkey credentials (COSE public key, sign counter)"
    override val baseline = false
    override fun Transaction.migrate() {
        SchemaUtils.createMissingTablesAndColumns(DashboardWebAuthnCredentials)
    }
}

/**
 * Adds `last_used_step` to `dashboard_totp` for RFC 6238 §5.2 replay prevention.
 * Existing rows are left `NULL`, which the service reads as "no code seen yet"
 * and admits the next verification normally — no user-facing disruption.
 */
object AuthV8005_TotpLastUsedStep : Migration {
    override val version = 8005
    override val description = "TOTP replay-prevention column (last_used_step)"
    override val baseline = false
    override fun Transaction.migrate() {
        SchemaUtils.createMissingTablesAndColumns(DashboardTotp)
    }
}
