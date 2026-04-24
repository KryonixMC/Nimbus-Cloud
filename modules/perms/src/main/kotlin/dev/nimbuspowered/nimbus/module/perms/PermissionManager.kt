package dev.nimbuspowered.nimbus.module.perms

import dev.nimbuspowered.nimbus.database.DatabaseManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Manages permission groups and player-to-group assignments.
 * Data is stored in SQLite via Exposed, with in-memory caches for fast reads.
 *
 * Supports:
 * - Contextual permissions (server/world scoping with optional expiry)
 * - Circular parent detection
 * - Permission node validation
 */
class PermissionManager(private val db: DatabaseManager) {

    private val logger = LoggerFactory.getLogger(PermissionManager::class.java)

    private val groups = mutableMapOf<String, PermissionGroup>()
    private val players = mutableMapOf<String, PlayerEntry>() // UUID -> PlayerEntry
    private val tracks = mutableMapOf<String, PermissionTrack>()

    private val resolver by lazy { PermissionResolver(groups, players) }
    private val trackManager by lazy {
        PermissionTrackManager(
            tracks = tracks,
            db = db,
            groups = groups,
            players = players,
            setPlayerGroup = { uuid, name, groupName -> setPlayerGroup(uuid, name, groupName) },
            removePlayerGroup = { uuid, groupName -> removePlayerGroup(uuid, groupName) }
        )
    }

    suspend fun init() {
        reload()
        ensureDefaultGroup()
    }

    private suspend fun ensureDefaultGroup() {
        if (groups.isNotEmpty()) return
        logger.info("No permission groups found — creating default groups")

        val defaultGroup = PermissionGroup(
            name = "Default",
            default = true,
            prefix = "&9[User] &7",
            priority = 0
        )
        groups[defaultGroup.name.lowercase()] = defaultGroup
        saveGroup(defaultGroup)

        val adminGroup = PermissionGroup(
            name = "Admin",
            prefix = "&c[Admin] &7",
            priority = 100,
            weight = 100,
            permissions = mutableListOf("*")
        )
        groups[adminGroup.name.lowercase()] = adminGroup
        saveGroup(adminGroup)

        logger.info("Created 'Default' (prefix: &9[User] &7) and 'Admin' (prefix: &c[Admin] &7, perm: *) groups")
    }

    suspend fun reload() {
        groups.clear()
        players.clear()
        tracks.clear()
        loadGroups()
        loadPlayers()
        loadTracks()
        logger.info("Loaded {} permission group(s), {} player assignment(s), {} track(s)", groups.size, players.size, tracks.size)
    }

    // ── Group CRUD ──────────────────────────────────────────────

    fun getAllGroups(): List<PermissionGroup> = groups.values.toList()

    fun getGroup(name: String): PermissionGroup? =
        groups.values.find { it.name.equals(name, ignoreCase = true) }

    fun getDefaultGroup(): PermissionGroup? =
        groups.values.find { it.default }

    suspend fun createGroup(name: String, default: Boolean = false): PermissionGroup {
        require(getGroup(name) == null) { "Group '$name' already exists" }
        val group = PermissionGroup(name = name, default = default)
        groups[name.lowercase()] = group
        saveGroup(group)
        return group
    }

    suspend fun deleteGroup(name: String) {
        val group = getGroup(name) ?: throw IllegalArgumentException("Group '$name' not found")
        groups.remove(group.name.lowercase())

        db.query {
            val permIds = GroupPermissions.selectAll()
                .where { GroupPermissions.groupId inSubQuery PermissionGroups.select(PermissionGroups.id).where { PermissionGroups.name.lowerCase() eq group.name.lowercase() } }
                .map { it[GroupPermissions.id] }
            for (permId in permIds) {
                GroupPermissionContexts.deleteWhere { GroupPermissionContexts.groupPermissionId eq permId }
            }

            PermissionGroups.deleteWhere { PermissionGroups.name.lowerCase() eq group.name.lowercase() }
            PlayerGroups.deleteWhere { PlayerGroups.groupName.lowerCase() eq group.name.lowercase() }
            PlayerGroupContexts.deleteWhere { PlayerGroupContexts.groupName.lowerCase() eq group.name.lowercase() }
        }

        players.values.forEach {
            it.groups.removeAll { g -> g.equals(name, ignoreCase = true) }
            it.groupContexts.remove(name.lowercase())
        }
    }

    suspend fun addPermission(groupName: String, permission: String, context: PermissionContext = PermissionContext()) {
        validatePermission(permission)
        val group = getGroup(groupName) ?: throw IllegalArgumentException("Group '$groupName' not found")

        if (context.server == null && context.world == null && context.expiresAt == null) {
            if (permission !in group.permissions) {
                group.permissions.add(permission)
            }
            group.contextualPermissions.remove(permission)
            saveGroup(group)
        } else {
            group.permissions.remove(permission)
            val contexts = group.contextualPermissions.getOrPut(permission) { mutableListOf() }
            if (contexts.none { it == context }) {
                contexts.add(context)
            }
            saveGroup(group)
        }
    }

    suspend fun removePermission(groupName: String, permission: String) {
        val group = getGroup(groupName) ?: throw IllegalArgumentException("Group '$groupName' not found")
        val removedGlobal = group.permissions.remove(permission)
        val removedContextual = group.contextualPermissions.remove(permission) != null
        if (removedGlobal || removedContextual) {
            saveGroup(group)
        }
    }

    suspend fun renamePermissionInAllGroups(old: String, new: String): PermissionRenameReport {
        validatePermission(new)
        val updatedGroups = mutableListOf<String>()
        var total = 0
        val negOld = "-$old"
        val negNew = "-$new"

        for (group in groups.values.toList()) {
            var changed = false

            val idxOld = group.permissions.indexOf(old)
            if (idxOld >= 0) {
                group.permissions.removeAt(idxOld)
                if (new !in group.permissions) group.permissions.add(new)
                changed = true; total++
            }
            val idxNegOld = group.permissions.indexOf(negOld)
            if (idxNegOld >= 0) {
                group.permissions.removeAt(idxNegOld)
                if (negNew !in group.permissions) group.permissions.add(negNew)
                changed = true; total++
            }

            val ctxOld = group.contextualPermissions.remove(old)
            if (ctxOld != null) {
                val merged = group.contextualPermissions.getOrPut(new) { mutableListOf() }
                for (c in ctxOld) if (merged.none { it == c }) merged.add(c)
                changed = true; total += ctxOld.size
            }
            val ctxNegOld = group.contextualPermissions.remove(negOld)
            if (ctxNegOld != null) {
                val merged = group.contextualPermissions.getOrPut(negNew) { mutableListOf() }
                for (c in ctxNegOld) if (merged.none { it == c }) merged.add(c)
                changed = true; total += ctxNegOld.size
            }

            if (changed) {
                saveGroup(group)
                updatedGroups.add(group.name)
            }
        }
        return PermissionRenameReport(old, new, updatedGroups, total)
    }

    suspend fun setDefault(groupName: String, default: Boolean) {
        val group = getGroup(groupName) ?: throw IllegalArgumentException("Group '$groupName' not found")
        if (default) {
            groups.values.filter { it.default && it.name != group.name }.forEach {
                groups[it.name.lowercase()] = it.copy(default = false)
                saveGroup(groups[it.name.lowercase()]!!)
            }
        }
        groups[group.name.lowercase()] = group.copy(default = default)
        saveGroup(groups[group.name.lowercase()]!!)
    }

    suspend fun addParent(groupName: String, parentName: String) {
        val group = getGroup(groupName) ?: throw IllegalArgumentException("Group '$groupName' not found")
        getGroup(parentName) ?: throw IllegalArgumentException("Parent group '$parentName' not found")
        require(!wouldCreateCycle(groupName, parentName)) {
            "Adding parent '$parentName' to group '$groupName' would create a circular inheritance chain"
        }
        if (!group.parents.any { it.equals(parentName, ignoreCase = true) }) {
            group.parents.add(parentName)
            saveGroup(group)
        }
    }

    suspend fun removeParent(groupName: String, parentName: String) {
        val group = getGroup(groupName) ?: throw IllegalArgumentException("Group '$groupName' not found")
        if (group.parents.removeAll { it.equals(parentName, ignoreCase = true) }) {
            saveGroup(group)
        }
    }

    // ── Circular Inheritance Detection ──────────────────────────

    /**
     * Checks if adding [parentName] as a parent of [groupName] would create a cycle.
     * Uses BFS through the parent chain of [parentName] to see if it leads back to [groupName].
     */
    private fun wouldCreateCycle(groupName: String, parentName: String): Boolean {
        val target = groupName.lowercase()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(parentName.lowercase())

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current == target) return true
            if (current in visited) continue
            visited.add(current)
            val group = getGroup(current) ?: continue
            group.parents.forEach { queue.add(it.lowercase()) }
        }
        return false
    }

    // ── Weight ──────────────────────────────────────────────────

    suspend fun setGroupWeight(groupName: String, weight: Int) {
        val group = getGroup(groupName) ?: throw IllegalArgumentException("Group '$groupName' not found")
        groups[group.name.lowercase()] = group.copy(weight = weight)
        saveGroup(groups[group.name.lowercase()]!!)
    }

    // ── Group Meta ──────────────────────────────────────────────

    fun getGroupMeta(groupName: String): Map<String, String> {
        val group = getGroup(groupName) ?: throw IllegalArgumentException("Group '$groupName' not found")
        return group.meta.toMap()
    }

    suspend fun setGroupMeta(groupName: String, key: String, value: String) {
        val group = getGroup(groupName) ?: throw IllegalArgumentException("Group '$groupName' not found")
        group.meta[key] = value
        saveGroup(group)
    }

    suspend fun removeGroupMeta(groupName: String, key: String) {
        val group = getGroup(groupName) ?: throw IllegalArgumentException("Group '$groupName' not found")
        if (group.meta.remove(key) != null) {
            saveGroup(group)
        }
    }

    // ── Player Meta ─────────────────────────────────────────────

    fun getPlayerMeta(uuid: String): Map<String, String> {
        val entry = players[uuid] ?: return emptyMap()
        return entry.meta.toMap()
    }

    suspend fun setPlayerMeta(uuid: String, key: String, value: String) {
        val entry = players[uuid] ?: throw IllegalArgumentException("Player not found")
        entry.meta[key] = value
        savePlayerMeta(uuid, entry.meta)
    }

    suspend fun removePlayerMeta(uuid: String, key: String) {
        val entry = players[uuid] ?: throw IllegalArgumentException("Player not found")
        if (entry.meta.remove(key) != null) {
            savePlayerMeta(uuid, entry.meta)
        }
    }

    // ── Player CRUD ─────────────────────────────────────────────

    fun getAllPlayers(): Map<String, PlayerEntry> = players.toMap()

    fun getPlayer(uuid: String): PlayerEntry? = players[uuid]

    fun getPlayerByName(name: String): Pair<String, PlayerEntry>? =
        players.entries.find { it.value.name.equals(name, ignoreCase = true) }?.let { it.key to it.value }

    suspend fun registerPlayer(uuid: String, playerName: String): Boolean {
        val existing = players[uuid]
        val isNew = existing == null

        if (!isNew && existing!!.name == playerName) return false

        players[uuid] = existing?.copy(name = playerName) ?: PlayerEntry(name = playerName)

        db.query {
            val exists = Players.selectAll().where { Players.uuid eq uuid }.count() > 0
            if (exists) {
                Players.update({ Players.uuid eq uuid }) { it[name] = playerName }
            } else {
                Players.insert {
                    it[Players.uuid] = uuid
                    it[name] = playerName
                }
            }
        }

        if (isNew) {
            val defaultGroup = getDefaultGroup()
            if (defaultGroup != null) {
                setPlayerGroup(uuid, playerName, defaultGroup.name)
                logger.debug("Auto-assigned player '{}' to default group '{}'", playerName, defaultGroup.name)
            }
        }

        return true
    }

    suspend fun setPlayerGroup(uuid: String, playerName: String, groupName: String, context: PermissionContext = PermissionContext()) {
        getGroup(groupName) ?: throw IllegalArgumentException("Group '$groupName' not found")
        val entry = players.getOrPut(uuid) { PlayerEntry(name = playerName) }
        if (!entry.groups.any { it.equals(groupName, ignoreCase = true) }) {
            entry.groups.add(groupName)
        }

        if (context.server != null || context.world != null || context.expiresAt != null) {
            val contexts = entry.groupContexts.getOrPut(groupName.lowercase()) { mutableListOf() }
            if (contexts.none { it == context }) {
                contexts.add(context)
            }
        }

        players[uuid] = entry.copy(name = playerName)

        db.query {
            val exists = Players.selectAll().where { Players.uuid eq uuid }.count() > 0
            if (exists) {
                Players.update({ Players.uuid eq uuid }) { it[name] = playerName }
            } else {
                Players.insert {
                    it[Players.uuid] = uuid
                    it[name] = playerName
                }
            }
            val alreadyAssigned = PlayerGroups.selectAll().where {
                (PlayerGroups.playerUuid eq uuid) and (PlayerGroups.groupName.lowerCase() eq groupName.lowercase())
            }.count() > 0
            if (!alreadyAssigned) {
                PlayerGroups.insert {
                    it[playerUuid] = uuid
                    it[PlayerGroups.groupName] = groupName
                }
            }

            if (context.server != null || context.world != null || context.expiresAt != null) {
                PlayerGroupContexts.insert {
                    it[PlayerGroupContexts.playerUuid] = uuid
                    it[PlayerGroupContexts.groupName] = groupName
                    it[server] = context.server
                    it[world] = context.world
                    it[expiresAt] = context.expiresAt
                }
            }
        }
    }

    suspend fun removePlayerGroup(uuid: String, groupName: String) {
        val entry = players[uuid] ?: throw IllegalArgumentException("Player not found")
        if (entry.groups.removeAll { it.equals(groupName, ignoreCase = true) }) {
            entry.groupContexts.remove(groupName.lowercase())
            db.query {
                PlayerGroups.deleteWhere {
                    (PlayerGroups.playerUuid eq uuid) and (PlayerGroups.groupName.lowerCase() eq groupName.lowercase())
                }
                PlayerGroupContexts.deleteWhere {
                    (PlayerGroupContexts.playerUuid eq uuid) and (PlayerGroupContexts.groupName.lowerCase() eq groupName.lowercase())
                }
            }
        }
    }

    // ── Permission Resolution (delegated to PermissionResolver) ─

    fun getEffectivePermissions(uuid: String, server: String? = null, world: String? = null): Set<String> =
        resolver.getEffectivePermissions(uuid, server, world)

    fun hasPermission(uuid: String, permission: String, server: String? = null, world: String? = null): Boolean =
        resolver.hasPermission(uuid, permission, server, world)

    fun checkPermission(uuid: String, permission: String, server: String? = null, world: String? = null): PermissionDebugResult =
        resolver.checkPermission(uuid, permission, server, world)

    fun getPlayerDisplay(uuid: String): PlayerDisplay =
        resolver.getPlayerDisplay(uuid)

    data class PlayerDisplay(
        val prefix: String,
        val suffix: String,
        val groupName: String,
        val priority: Int
    )

    suspend fun updateGroupDisplay(groupName: String, prefix: String?, suffix: String?, priority: Int?) {
        val group = getGroup(groupName) ?: throw IllegalArgumentException("Group '$groupName' not found")
        groups[group.name.lowercase()] = group.copy(
            prefix = prefix ?: group.prefix,
            suffix = suffix ?: group.suffix,
            priority = priority ?: group.priority
        )
        saveGroup(groups[group.name.lowercase()]!!)
    }

    // ── Tracks (delegated to PermissionTrackManager) ─────────────

    fun getAllTracks(): List<PermissionTrack> = trackManager.getAllTracks()

    fun getTrack(name: String): PermissionTrack? = trackManager.getTrack(name)

    suspend fun createTrack(name: String, trackGroups: List<String>): PermissionTrack =
        trackManager.createTrack(name, trackGroups)

    suspend fun deleteTrack(name: String) = trackManager.deleteTrack(name)

    suspend fun promote(uuid: String, trackName: String): String? = trackManager.promote(uuid, trackName)

    suspend fun demote(uuid: String, trackName: String): String? = trackManager.demote(uuid, trackName)

    // ── Temporary Permission Cleanup ────────────────────────────

    /**
     * Removes expired context entries from the database and reloads in-memory state.
     * Called automatically every 60 seconds by the PermsModule.
     */
    suspend fun cleanupExpired(): Int {
        val now = Instant.now().toString()
        var cleaned = 0

        db.query {
            val expiredPermContexts = GroupPermissionContexts.selectAll()
                .where { (GroupPermissionContexts.expiresAt.isNotNull()) and (GroupPermissionContexts.expiresAt less now) }
                .map { it[GroupPermissionContexts.id] }
            for (id in expiredPermContexts) {
                GroupPermissionContexts.deleteWhere { GroupPermissionContexts.id eq id }
                cleaned++
            }

            val expiredPlayerContexts = PlayerGroupContexts.selectAll()
                .where { (PlayerGroupContexts.expiresAt.isNotNull()) and (PlayerGroupContexts.expiresAt less now) }
                .map { it[PlayerGroupContexts.id] }
            for (id in expiredPlayerContexts) {
                PlayerGroupContexts.deleteWhere { PlayerGroupContexts.id eq id }
                cleaned++
            }
        }

        if (cleaned > 0) {
            reload()
            logger.info("Cleaned up {} expired permission context(s)", cleaned)
        }

        return cleaned
    }

    // ── Audit Log ───────────────────────────────────────────────

    suspend fun logAudit(actor: String, action: String, target: String, details: String) {
        db.query {
            PermissionAuditLog.insert {
                it[timestamp] = Instant.now().toString()
                it[PermissionAuditLog.actor] = actor
                it[PermissionAuditLog.action] = action
                it[PermissionAuditLog.target] = target
                it[PermissionAuditLog.details] = details
            }
        }
    }

    data class AuditEntry(
        val timestamp: String,
        val actor: String,
        val action: String,
        val target: String,
        val details: String
    )

    suspend fun getAuditLog(limit: Int = 50, offset: Int = 0): List<AuditEntry> {
        return db.query {
            PermissionAuditLog.selectAll()
                .orderBy(PermissionAuditLog.timestamp, SortOrder.DESC)
                .limit(limit).offset(offset.toLong())
                .map {
                    AuditEntry(
                        timestamp = it[PermissionAuditLog.timestamp],
                        actor = it[PermissionAuditLog.actor],
                        action = it[PermissionAuditLog.action],
                        target = it[PermissionAuditLog.target],
                        details = it[PermissionAuditLog.details]
                    )
                }
        }
    }

    companion object {
        private val PERMISSION_PATTERN = Regex("^-?[a-zA-Z0-9_.#*-]+$")

        /**
         * Validates a permission node string.
         * Only letters, digits, dots, underscores, hashes, asterisks, and hyphens allowed.
         * Negation prefix (-) is permitted.
         */
        fun validatePermission(permission: String) {
            require(permission.isNotBlank()) { "Permission node cannot be blank" }
            require(PERMISSION_PATTERN.matches(permission)) {
                "Invalid permission node '$permission' — only letters, digits, dots, underscores, hashes, asterisks, and hyphens allowed"
            }
        }

        fun matchesPermission(effective: Set<String>, permission: String): Boolean {
            if (permission in effective) return true
            if ("*" in effective) return true

            val parts = permission.split(".")
            for (i in parts.indices) {
                val wildcard = parts.subList(0, i + 1).joinToString(".").removeSuffix(".${parts[i]}") + ".*"
                if (i > 0 && wildcard in effective) return true
            }

            for (i in 1..parts.size) {
                val prefix = parts.subList(0, i - 1).joinToString(".")
                val wildcard = if (prefix.isEmpty()) "*" else "$prefix.*"
                if (wildcard in effective) return true
            }

            return false
        }
    }

    // ── Database I/O ────────────────────────────────────────────

    private suspend fun loadGroups() {
        db.query {
            PermissionGroups.selectAll().forEach { row ->
                val groupId = row[PermissionGroups.id]
                val name = row[PermissionGroups.name]

                val allPermissions = GroupPermissions.selectAll()
                    .where { GroupPermissions.groupId eq groupId }
                    .map { it[GroupPermissions.id].value to it[GroupPermissions.permission] }

                val globalPerms = mutableListOf<String>()
                val contextualPerms = mutableMapOf<String, MutableList<PermissionContext>>()

                for ((permId, permission) in allPermissions) {
                    val contexts = GroupPermissionContexts.selectAll()
                        .where { GroupPermissionContexts.groupPermissionId eq permId }
                        .map { ctxRow ->
                            PermissionContext(
                                server = ctxRow[GroupPermissionContexts.server],
                                world = ctxRow[GroupPermissionContexts.world],
                                expiresAt = ctxRow[GroupPermissionContexts.expiresAt]
                            )
                        }

                    if (contexts.isEmpty()) {
                        globalPerms.add(permission)
                    } else {
                        contextualPerms[permission] = contexts.toMutableList()
                    }
                }

                val parents = GroupParents.selectAll()
                    .where { GroupParents.groupId eq groupId }
                    .map { it[GroupParents.parentName] }
                    .toMutableList()

                val meta = GroupMeta.selectAll()
                    .where { GroupMeta.groupId eq groupId }
                    .associate { it[GroupMeta.key] to it[GroupMeta.value] }
                    .toMutableMap()

                groups[name.lowercase()] = PermissionGroup(
                    name = name,
                    default = row[PermissionGroups.isDefault],
                    prefix = row[PermissionGroups.prefix],
                    suffix = row[PermissionGroups.suffix],
                    priority = row[PermissionGroups.priority],
                    weight = row[PermissionGroups.weight],
                    permissions = globalPerms,
                    contextualPermissions = contextualPerms,
                    parents = parents,
                    meta = meta
                )
            }
        }
    }

    private suspend fun loadPlayers() {
        db.query {
            Players.selectAll().forEach { row ->
                val uuid = row[Players.uuid]
                val name = row[Players.name]

                val playerGroupNames = PlayerGroups.selectAll()
                    .where { PlayerGroups.playerUuid eq uuid }
                    .map { it[PlayerGroups.groupName] }
                    .toMutableList()

                val groupContexts = mutableMapOf<String, MutableList<PermissionContext>>()
                PlayerGroupContexts.selectAll()
                    .where { PlayerGroupContexts.playerUuid eq uuid }
                    .forEach { ctxRow ->
                        val gName = ctxRow[PlayerGroupContexts.groupName]
                        groupContexts.getOrPut(gName.lowercase()) { mutableListOf() }.add(
                            PermissionContext(
                                server = ctxRow[PlayerGroupContexts.server],
                                world = ctxRow[PlayerGroupContexts.world],
                                expiresAt = ctxRow[PlayerGroupContexts.expiresAt]
                            )
                        )
                    }

                val meta = PlayerMeta.selectAll()
                    .where { PlayerMeta.playerUuid eq uuid }
                    .associate { it[PlayerMeta.key] to it[PlayerMeta.value] }
                    .toMutableMap()

                players[uuid] = PlayerEntry(
                    name = name,
                    groups = playerGroupNames,
                    groupContexts = groupContexts,
                    meta = meta
                )
            }
        }
    }

    private suspend fun loadTracks() {
        db.query {
            PermissionTracks.selectAll().forEach { row ->
                val name = row[PermissionTracks.name]
                val groupsJson = row[PermissionTracks.groups]
                val groupList = Json.parseToJsonElement(groupsJson).jsonArray.map { it.jsonPrimitive.content }
                tracks[name.lowercase()] = PermissionTrack(name = name, groups = groupList)
            }
        }
    }

    private suspend fun saveGroup(group: PermissionGroup) {
        db.query {
            val existing = PermissionGroups.selectAll()
                .where { PermissionGroups.name.lowerCase() eq group.name.lowercase() }
                .firstOrNull()

            val groupId = if (existing != null) {
                PermissionGroups.update({ PermissionGroups.name.lowerCase() eq group.name.lowercase() }) {
                    it[name] = group.name
                    it[isDefault] = group.default
                    it[prefix] = group.prefix
                    it[suffix] = group.suffix
                    it[priority] = group.priority
                    it[weight] = group.weight
                }
                existing[PermissionGroups.id]
            } else {
                PermissionGroups.insertAndGetId {
                    it[name] = group.name
                    it[isDefault] = group.default
                    it[prefix] = group.prefix
                    it[suffix] = group.suffix
                    it[priority] = group.priority
                    it[weight] = group.weight
                }
            }

            val existingPermIds = GroupPermissions.selectAll()
                .where { GroupPermissions.groupId eq groupId }
                .map { it[GroupPermissions.id] }
            for (permId in existingPermIds) {
                GroupPermissionContexts.deleteWhere { GroupPermissionContexts.groupPermissionId eq permId }
            }

            GroupPermissions.deleteWhere { GroupPermissions.groupId eq groupId }

            for (perm in group.permissions) {
                GroupPermissions.insert {
                    it[GroupPermissions.groupId] = groupId
                    it[permission] = perm
                }
            }

            for ((perm, contexts) in group.contextualPermissions) {
                val permId = GroupPermissions.insertAndGetId {
                    it[GroupPermissions.groupId] = groupId
                    it[permission] = perm
                }
                for (ctx in contexts) {
                    GroupPermissionContexts.insert {
                        it[groupPermissionId] = permId
                        it[server] = ctx.server
                        it[world] = ctx.world
                        it[expiresAt] = ctx.expiresAt
                    }
                }
            }

            GroupParents.deleteWhere { GroupParents.groupId eq groupId }
            for (parent in group.parents) {
                GroupParents.insert {
                    it[GroupParents.groupId] = groupId
                    it[parentName] = parent
                }
            }

            GroupMeta.deleteWhere { GroupMeta.groupId eq groupId }
            for ((key, value) in group.meta) {
                GroupMeta.insert {
                    it[GroupMeta.groupId] = groupId
                    it[GroupMeta.key] = key
                    it[GroupMeta.value] = value
                }
            }
        }
    }

    // ── Player Meta DB I/O ─────────────────────────────────────

    private suspend fun savePlayerMeta(uuid: String, meta: Map<String, String>) {
        db.query {
            PlayerMeta.deleteWhere { PlayerMeta.playerUuid eq uuid }
            for ((key, value) in meta) {
                PlayerMeta.insert {
                    it[playerUuid] = uuid
                    it[PlayerMeta.key] = key
                    it[PlayerMeta.value] = value
                }
            }
        }
    }
}
