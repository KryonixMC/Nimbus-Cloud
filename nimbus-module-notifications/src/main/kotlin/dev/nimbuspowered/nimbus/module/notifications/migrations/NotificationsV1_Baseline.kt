package dev.nimbuspowered.nimbus.module.notifications.migrations

import dev.nimbuspowered.nimbus.module.Migration
import org.jetbrains.exposed.sql.Transaction

/**
 * Baseline migration for the Notifications module.
 * Version range 4000+ is reserved for this module.
 *
 * No tables are needed — the module is purely stateless (webhooks
 * are config-driven, no persistence required).
 */
object NotificationsV1_Baseline : Migration {
    override val version = 4000
    override val description = "Notifications module baseline (no tables)"
    override val baseline = true

    override fun Transaction.migrate() {
        // No-op: this module does not require any database tables.
    }
}
