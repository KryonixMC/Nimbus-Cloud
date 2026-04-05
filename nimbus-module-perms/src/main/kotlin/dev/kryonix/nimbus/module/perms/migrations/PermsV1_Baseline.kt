package dev.kryonix.nimbus.module.perms.migrations

import dev.kryonix.nimbus.module.Migration
import dev.kryonix.nimbus.module.perms.*
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction

/** Baseline migration: all permission tables. Version range 1000+ reserved for perms module. */
object PermsV1_Baseline : Migration {
    override val version = 1000
    override val description = "Permission tables: groups, permissions, parents, players, meta, contexts, tracks, audit log"
    override val baseline = true

    override fun Transaction.migrate() {
        SchemaUtils.createMissingTablesAndColumns(
            PermissionGroups, GroupPermissions, GroupParents,
            Players, PlayerGroups,
            GroupMeta, PlayerMeta,
            GroupPermissionContexts, PlayerGroupContexts,
            PermissionTracks, PermissionAuditLog
        )
    }
}
