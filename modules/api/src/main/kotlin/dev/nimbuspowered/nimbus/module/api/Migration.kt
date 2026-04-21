package dev.nimbuspowered.nimbus.module.api

import org.jetbrains.exposed.sql.Transaction

/**
 * Represents a versioned database schema migration.
 *
 * Migrations are applied in order of [version] on startup.
 * Core migrations use versions 1–999. Module migrations use 1000+
 * with a module-specific prefix (e.g. perms: 1000+, scaling: 2000+).
 */
interface Migration {

    /** Unique version number. Migrations run in ascending order. */
    val version: Int

    /** Human-readable description (logged when applied). */
    val description: String

    /**
     * Whether this migration represents a pre-existing schema (before the migration system was introduced).
     * Baseline migrations are marked as "already applied" on existing databases without being executed.
     * New migrations (baseline = false) are always executed, even on upgrade from pre-0.2.0.
     */
    val baseline: Boolean get() = false

    /**
     * Execute the migration within an Exposed transaction.
     * Use [org.jetbrains.exposed.sql.SchemaUtils] or raw SQL via `exec()`.
     */
    fun Transaction.migrate()
}
