package dev.nimbuspowered.nimbus.module.scaling.migrations

import dev.nimbuspowered.nimbus.module.api.Migration
import dev.nimbuspowered.nimbus.module.scaling.ScalingDecisions
import dev.nimbuspowered.nimbus.module.scaling.ScalingSnapshots
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction

/** Baseline migration: smart scaling tables. Version range 2000+ reserved for scaling module. */
object ScalingV1_Baseline : Migration {
    override val version = 2000
    override val description = "Smart scaling tables: snapshots, decisions"
    override val baseline = true

    override fun Transaction.migrate() {
        SchemaUtils.createMissingTablesAndColumns(ScalingSnapshots, ScalingDecisions)
    }
}
