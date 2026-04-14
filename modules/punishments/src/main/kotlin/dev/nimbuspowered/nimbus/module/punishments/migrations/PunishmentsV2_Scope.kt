package dev.nimbuspowered.nimbus.module.punishments.migrations

import dev.nimbuspowered.nimbus.module.Migration
import dev.nimbuspowered.nimbus.module.punishments.Punishments
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction

/**
 * Adds the `scope` + `scope_target` columns to existing installs.
 *
 * Fresh installs already get these via V1 (SchemaUtils creates every declared
 * column), so this migration is a no-op there. For upgrades from 0.9.0 → 0.9.x,
 * it adds the new columns with defaults that keep existing rows as NETWORK-scoped.
 */
object PunishmentsV2_Scope : Migration {
    override val version = 5001
    override val description = "Add scope + scope_target columns (network / group / service)"
    override val baseline = false

    override fun Transaction.migrate() {
        // createMissingTablesAndColumns adds absent columns with their defaults.
        // Existing rows inherit the default "NETWORK" for scope and NULL for scope_target.
        SchemaUtils.createMissingTablesAndColumns(Punishments)
    }
}
