package dev.nimbuspowered.nimbus.module.perms

import kotlinx.serialization.Serializable

// ── Permission DTOs ─────────────────────────────────────────────────

@Serializable
data class PlayerListEntry(
    val uuid: String,
    val name: String,
    val groups: List<String>,
    val displayGroup: String,
    val prefix: String
)

@Serializable
data class PermissionGroupResponse(
    val name: String,
    val default: Boolean,
    val prefix: String = "",
    val suffix: String = "",
    val priority: Int = 0,
    val weight: Int = 0,
    val permissions: List<String>,
    val parents: List<String>,
    val meta: Map<String, String> = emptyMap()
)

@Serializable
data class PermissionGroupListResponse(
    val groups: List<PermissionGroupResponse>,
    val total: Int
)

@Serializable
data class CreatePermissionGroupRequest(
    val name: String,
    val default: Boolean = false
)

@Serializable
data class UpdatePermissionGroupRequest(
    val default: Boolean? = null,
    val prefix: String? = null,
    val suffix: String? = null,
    val priority: Int? = null,
    val weight: Int? = null,
    val permissions: List<String>? = null,
    val parents: List<String>? = null,
    val meta: Map<String, String>? = null
)

@Serializable
data class PermissionModifyRequest(
    val permission: String,
    val server: String? = null,
    val world: String? = null,
    val expiresAt: String? = null
)

@Serializable
data class ParentModifyRequest(
    val parent: String
)

@Serializable
data class PlayerPermissionResponse(
    val uuid: String,
    val name: String,
    val groups: List<String>,
    val effectivePermissions: List<String>,
    val prefix: String = "",
    val suffix: String = "",
    val displayGroup: String = "",
    val priority: Int = 0,
    val meta: Map<String, String> = emptyMap()
)

@Serializable
data class PlayerRegisterRequest(
    val name: String
)

@Serializable
data class PlayerGroupRequest(
    val group: String,
    val name: String? = null,
    val server: String? = null,
    val world: String? = null,
    val expiresAt: String? = null
)

@Serializable
data class PermissionCheckResponse(
    val uuid: String,
    val permission: String,
    val allowed: Boolean
)

// ── Tracks ──────────────────────────────────────────────────────────

@Serializable
data class PermissionTrackResponse(
    val name: String,
    val groups: List<String>
)

@Serializable
data class PermissionTrackListResponse(
    val tracks: List<PermissionTrackResponse>,
    val total: Int
)

@Serializable
data class CreateTrackRequest(
    val name: String,
    val groups: List<String>
)

@Serializable
data class PromoteDemoteResponse(
    val success: Boolean,
    val previousGroup: String? = null,
    val newGroup: String? = null,
    val message: String
)

// ── Meta ────────────────────────────────────────────────────────────

@Serializable
data class MetaResponse(
    val meta: Map<String, String>
)

@Serializable
data class MetaSetRequest(
    val key: String,
    val value: String
)

// ── Debug ───────────────────────────────────────────────────────────

@Serializable
data class PermissionDebugResponse(
    val uuid: String,
    val permission: String,
    val result: Boolean,
    val reason: String,
    val chain: List<DebugStepResponse>
)

@Serializable
data class DebugStepResponse(
    val source: String,
    val permission: String,
    val type: String,
    val granted: Boolean
)

// ── Bulk Operations ────────────────────────────────────────────────

@Serializable
data class BulkPermissionRequest(
    val groups: List<String>,
    val permission: String,
    val server: String? = null,
    val world: String? = null,
    val expiresAt: String? = null
)

@Serializable
data class BulkGroupAssignRequest(
    val players: List<String>,
    val group: String,
    val server: String? = null,
    val world: String? = null,
    val expiresAt: String? = null
)

@Serializable
data class BulkOperationResponse(
    val success: Boolean,
    val processed: Int,
    val failed: Int,
    val errors: List<String> = emptyList()
)

// ── Audit ───────────────────────────────────────────────────────────

@Serializable
data class AuditEntryResponse(
    val timestamp: String,
    val actor: String,
    val action: String,
    val target: String,
    val details: String
)

@Serializable
data class AuditLogResponse(
    val entries: List<AuditEntryResponse>,
    val total: Int
)
