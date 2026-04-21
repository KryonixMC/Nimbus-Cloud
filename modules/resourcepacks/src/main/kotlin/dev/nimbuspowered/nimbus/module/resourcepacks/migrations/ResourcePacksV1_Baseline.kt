package dev.nimbuspowered.nimbus.module.resourcepacks.migrations

import dev.nimbuspowered.nimbus.module.api.Migration
import dev.nimbuspowered.nimbus.module.resourcepacks.ResourcePackAssignments
import dev.nimbuspowered.nimbus.module.resourcepacks.ResourcePacks
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction

/** Baseline migration for resource packs module. Version range 6000+ reserved. */
object ResourcePacksV1_Baseline : Migration {
    override val version = 6000
    override val description = "Resource packs table + per-group/service assignments"
    override val baseline = false

    override fun Transaction.migrate() {
        SchemaUtils.createMissingTablesAndColumns(ResourcePacks, ResourcePackAssignments)
    }
}
