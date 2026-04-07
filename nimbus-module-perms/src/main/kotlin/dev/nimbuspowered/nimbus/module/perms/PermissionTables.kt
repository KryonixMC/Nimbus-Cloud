package dev.nimbuspowered.nimbus.module.perms

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Table

// ── Permission Tables ───────────────────────────────────────

object PermissionGroups : IntIdTable("permission_groups") {
    val name = varchar("name", 64).uniqueIndex()
    val isDefault = bool("is_default").default(false)
    val prefix = varchar("prefix", 256).default("")
    val suffix = varchar("suffix", 256).default("")
    val priority = integer("priority").default(0)
    val weight = integer("weight").default(0)
}

object GroupPermissions : IntIdTable("group_permissions") {
    val groupId = reference("group_id", PermissionGroups)
    val permission = varchar("permission", 256)

    init {
        uniqueIndex(groupId, permission)
    }
}

object GroupParents : IntIdTable("group_parents") {
    val groupId = reference("group_id", PermissionGroups)
    val parentName = varchar("parent_name", 64)

    init {
        uniqueIndex(groupId, parentName)
    }
}

object Players : Table("players") {
    val uuid = varchar("uuid", 36)
    val name = varchar("name", 16)

    override val primaryKey = PrimaryKey(uuid)
}

object PlayerGroups : Table("player_groups") {
    val playerUuid = varchar("player_uuid", 36).references(Players.uuid)
    val groupName = varchar("group_name", 64)

    override val primaryKey = PrimaryKey(playerUuid, groupName)
}

// ── Permission Meta Tables ──────────────────────────────────

object GroupMeta : IntIdTable("group_meta") {
    val groupId = reference("group_id", PermissionGroups)
    val key = varchar("key", 128)
    val value = varchar("value", 1024)

    init {
        uniqueIndex(groupId, key)
    }
}

object PlayerMeta : IntIdTable("player_meta") {
    val playerUuid = varchar("player_uuid", 36).references(Players.uuid)
    val key = varchar("key", 128)
    val value = varchar("value", 1024)

    init {
        uniqueIndex(playerUuid, key)
    }
}

// ── Permission Context Tables ───────────────────────────────

object GroupPermissionContexts : IntIdTable("group_permission_contexts") {
    val groupPermissionId = reference("group_permission_id", GroupPermissions)
    val server = varchar("server", 128).nullable()
    val world = varchar("world", 128).nullable()
    val expiresAt = varchar("expires_at", 30).nullable()
}

object PlayerGroupContexts : IntIdTable("player_group_contexts") {
    val playerUuid = varchar("player_uuid", 36).references(Players.uuid)
    val groupName = varchar("group_name", 64)
    val server = varchar("server", 128).nullable()
    val world = varchar("world", 128).nullable()
    val expiresAt = varchar("expires_at", 30).nullable()

    init {
        uniqueIndex(playerUuid, groupName, server, world)
    }
}

// ── Permission Tracks ───────────────────────────────────────

object PermissionTracks : IntIdTable("permission_tracks") {
    val name = varchar("name", 64).uniqueIndex()
    val groups = text("groups") // JSON array: ["Member","VIP","MVP","Admin"]
}

// ── Permission Audit Log ────────────────────────────────────

object PermissionAuditLog : IntIdTable("permission_audit_log") {
    val timestamp = varchar("timestamp", 30)
    val actor = varchar("actor", 128)
    val action = varchar("action", 64)
    val target = varchar("target", 128)
    val details = text("details").default("")

    init {
        index(false, timestamp)
    }
}
