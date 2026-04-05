package dev.kryonix.nimbus.module

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
     * Execute the migration within an Exposed transaction.
     * Use [org.jetbrains.exposed.sql.SchemaUtils] or raw SQL via `exec()`.
     */
    fun Transaction.migrate()
}
