package dev.kryonix.nimbus.permissions

import dev.kryonix.nimbus.database.*
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
 */
class PermissionManager(private val db: DatabaseManager) {

    private val logger = LoggerFactory.getLogger(PermissionManager::class.java)

    private val groups = mutableMapOf<String, PermissionGroup>()
    private val players = mutableMapOf<String, PlayerEntry>() // UUID -> PlayerEntry
    private val tracks = mutableMapOf<String, PermissionTrack>()

    suspend fun init() {
        reload()
        ensureDefaultGroup()
    }

    private suspend fun ensureDefaultGroup() {
        if (groups.isNotEmpty()) return
        logger.info("No permission groups found — creating default groups")

        // Default group for all players
        val defaultGroup = PermissionGroup(
            name = "Default",
            default = true,
            prefix = "&9[User] &7",
            priority = 0
        )
        groups[defaultGroup.name.lowercase()] = defaultGroup
        saveGroup(defaultGroup)

        // Admin group with all permissions
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
            // CASCADE deletes group_permissions and group_parents
            PermissionGroups.deleteWhere { PermissionGroups.name.lowerCase() eq group.name.lowercase() }
            // Clean up player_groups references
            PlayerGroups.deleteWhere { PlayerGroups.groupName.lowerCase() eq group.name.lowercase() }
            // Clean up player group contexts
            PlayerGroupContexts.deleteWhere { PlayerGroupContexts.groupName.lowerCase() eq group.name.lowercase() }
        }

        // Remove group from in-memory player cache
        players.values.forEach { it.groups.removeAll { g -> g.equals(name, ignoreCase = true) } }
    }

    suspend fun addPermission(groupName: String, permission: String, context: PermissionContext = PermissionContext()) {
        val group = getGroup(groupName) ?: throw IllegalArgumentException("Group '$groupName' not found")

        // Global permissions (no context) go into the in-memory list
        if (context.server == null && context.world == null && context.expiresAt == null) {
            if (permission !in group.permissions) {
                group.permissions.add(permission)
                saveGroup(group)
            }
        } else {
            // Contextual permissions: add to main list if not there, then store context
            if (permission !in group.permissions) {
                group.permissions.add(permission)
                saveGroup(group)
            }
            // Store context in GroupPermissionContexts
            db.query {
                val groupId = PermissionGroups.selectAll()
                    .where { PermissionGroups.name.lowerCase() eq groupName.lowercase() }
                    .first()[PermissionGroups.id]

                val permId = GroupPermissions.selectAll()
                    .where { (GroupPermissions.groupId eq groupId) and (GroupPermissions.permission eq permission) }
                    .first()[GroupPermissions.id]

                GroupPermissionContexts.insert {
                    it[groupPermissionId] = permId
                    it[server] = context.server
                    it[world] = context.world
                    it[expiresAt] = context.expiresAt
                }
            }
        }
    }

    suspend fun removePermission(groupName: String, permission: String) {
        val group = getGroup(groupName) ?: throw IllegalArgumentException("Group '$groupName' not found")
        if (group.permissions.remove(permission)) {
            saveGroup(group)
        }
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

        // Auto-assign new players to the default group
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
        players[uuid] = entry.copy(name = playerName)

        db.query {
            // Ensure player exists
            val exists = Players.selectAll().where { Players.uuid eq uuid }.count() > 0
            if (exists) {
                Players.update({ Players.uuid eq uuid }) { it[name] = playerName }
            } else {
                Players.insert {
                    it[Players.uuid] = uuid
                    it[name] = playerName
                }
            }
            // Add group assignment (ignore if duplicate)
            val alreadyAssigned = PlayerGroups.selectAll().where {
                (PlayerGroups.playerUuid eq uuid) and (PlayerGroups.groupName.lowerCase() eq groupName.lowercase())
            }.count() > 0
            if (!alreadyAssigned) {
                PlayerGroups.insert {
                    it[playerUuid] = uuid
                    it[PlayerGroups.groupName] = groupName
                }
            }

            // Store context if any
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

    // ── Permission Resolution ───────────────────────────────────

    fun getEffectivePermissions(uuid: String, server: String? = null, world: String? = null): Set<String> {
        val result = mutableSetOf<String>()
        val negated = mutableSetOf<String>()

        getDefaultGroup()?.let { collectPermissions(it, result, negated, mutableSetOf()) }

        val entry = players[uuid]
        if (entry != null) {
            for (groupName in entry.groups) {
                val group = getGroup(groupName) ?: continue
                collectPermissions(group, result, negated, mutableSetOf())
            }
        }

        result.removeAll(negated)
        return result
    }

    fun hasPermission(uuid: String, permission: String, server: String? = null, world: String? = null): Boolean {
        val effective = getEffectivePermissions(uuid, server, world)
        return matchesPermission(effective, permission)
    }

    // ── Permission Debug ────────────────────────────────────────

    fun checkPermission(uuid: String, permission: String, server: String? = null, world: String? = null): PermissionDebugResult {
        val chain = mutableListOf<DebugStep>()
        val entry = players[uuid]
        val defaultGroup = getDefaultGroup()

        // Collect debug info from default group
        if (defaultGroup != null) {
            collectDebugSteps(defaultGroup, permission, chain, mutableSetOf(), "default")
        }

        // Collect debug info from player's groups
        if (entry != null) {
            for (groupName in entry.groups) {
                val group = getGroup(groupName) ?: continue
                collectDebugSteps(group, permission, chain, mutableSetOf(), "assigned")
            }
        }

        val effective = getEffectivePermissions(uuid, server, world)
        val result = matchesPermission(effective, permission)

        val reason = if (chain.isEmpty()) {
            if (result) "Granted (no specific matching node found)" else "Denied — no matching permission node"
        } else {
            val decisive = chain.lastOrNull { it.granted != result }?.let { chain.last() } ?: chain.last()
            if (result) {
                "Granted by group '${decisive.source}' via ${decisive.type} match on '${decisive.permission}'"
            } else {
                val negatedStep = chain.find { !it.granted }
                if (negatedStep != null) {
                    "Denied — negated by '${negatedStep.permission}' in group '${negatedStep.source}'"
                } else {
                    "Denied — no matching permission node"
                }
            }
        }

        return PermissionDebugResult(
            permission = permission,
            result = result,
            reason = reason,
            chain = chain
        )
    }

    private fun collectDebugSteps(
        group: PermissionGroup,
        permission: String,
        chain: MutableList<DebugStep>,
        visited: MutableSet<String>,
        assignmentType: String
    ) {
        if (group.name.lowercase() in visited) return
        visited.add(group.name.lowercase())

        // Check parents first (inherited)
        for (parentName in group.parents) {
            val parent = getGroup(parentName) ?: continue
            collectDebugSteps(parent, permission, chain, visited, "inherited")
        }

        // Check this group's permissions
        for (perm in group.permissions) {
            val isNegated = perm.startsWith("-")
            val actualPerm = if (isNegated) perm.removePrefix("-") else perm

            val matches = actualPerm == permission ||
                    actualPerm == "*" ||
                    (actualPerm.endsWith(".*") && permission.startsWith(actualPerm.removeSuffix(".*")))

            if (matches) {
                val type = when {
                    isNegated -> "negated"
                    assignmentType == "inherited" -> "inherited"
                    actualPerm == permission -> "exact"
                    else -> "wildcard"
                }
                chain.add(DebugStep(
                    source = group.name,
                    permission = perm,
                    type = type,
                    granted = !isNegated
                ))
            }
        }
    }

    // ── Tracks ──────────────────────────────────────────────────

    fun getAllTracks(): List<PermissionTrack> = tracks.values.toList()

    fun getTrack(name: String): PermissionTrack? =
        tracks.values.find { it.name.equals(name, ignoreCase = true) }

    suspend fun createTrack(name: String, trackGroups: List<String>): PermissionTrack {
        require(getTrack(name) == null) { "Track '$name' already exists" }
        require(trackGroups.size >= 2) { "Track must have at least 2 groups" }
        for (g in trackGroups) {
            getGroup(g) ?: throw IllegalArgumentException("Group '$g' not found")
        }

        val track = PermissionTrack(name = name, groups = trackGroups)
        tracks[name.lowercase()] = track

        db.query {
            PermissionTracks.insert {
                it[PermissionTracks.name] = name
                it[groups] = Json.encodeToString(trackGroups)
            }
        }

        return track
    }

    suspend fun deleteTrack(name: String) {
        val track = getTrack(name) ?: throw IllegalArgumentException("Track '$name' not found")
        tracks.remove(track.name.lowercase())

        db.query {
            PermissionTracks.deleteWhere { PermissionTracks.name.lowerCase() eq name.lowercase() }
        }
    }

    suspend fun promote(uuid: String, trackName: String): String? {
        val track = getTrack(trackName) ?: throw IllegalArgumentException("Track '$trackName' not found")
        val entry = players[uuid] ?: throw IllegalArgumentException("Player not found")

        // Find the highest group the player has on this track
        var currentIndex = -1
        for ((i, groupName) in track.groups.withIndex()) {
            if (entry.groups.any { it.equals(groupName, ignoreCase = true) }) {
                currentIndex = i
            }
        }

        if (currentIndex >= track.groups.size - 1) return null // already at top

        val newGroup = track.groups[currentIndex + 1]

        // Remove current track group if any
        if (currentIndex >= 0) {
            removePlayerGroup(uuid, track.groups[currentIndex])
        }

        // Add new group
        setPlayerGroup(uuid, entry.name, newGroup)
        return newGroup
    }

    suspend fun demote(uuid: String, trackName: String): String? {
        val track = getTrack(trackName) ?: throw IllegalArgumentException("Track '$trackName' not found")
        val entry = players[uuid] ?: throw IllegalArgumentException("Player not found")

        // Find the highest group the player has on this track
        var currentIndex = -1
        for ((i, groupName) in track.groups.withIndex()) {
            if (entry.groups.any { it.equals(groupName, ignoreCase = true) }) {
                currentIndex = i
            }
        }

        if (currentIndex <= 0) return null // already at bottom or not on track

        val newGroup = track.groups[currentIndex - 1]

        // Remove current track group
        removePlayerGroup(uuid, track.groups[currentIndex])

        // Add new group
        setPlayerGroup(uuid, entry.name, newGroup)
        return newGroup
    }

    // ── Temporary Permission Cleanup ────────────────────────────

    suspend fun cleanupExpired(): Int {
        val now = Instant.now().toString()
        var cleaned = 0

        db.query {
            // Clean expired group permission contexts
            val expiredPermContexts = GroupPermissionContexts.selectAll()
                .where { (GroupPermissionContexts.expiresAt.isNotNull()) and (GroupPermissionContexts.expiresAt less now) }
                .toList()

            for (row in expiredPermContexts) {
                GroupPermissionContexts.deleteWhere { GroupPermissionContexts.id eq row[GroupPermissionContexts.id] }
                cleaned++
            }

            // Clean expired player group contexts
            val expiredPlayerContexts = PlayerGroupContexts.selectAll()
                .where { (PlayerGroupContexts.expiresAt.isNotNull()) and (PlayerGroupContexts.expiresAt less now) }
                .toList()

            for (row in expiredPlayerContexts) {
                val puuid = row[PlayerGroupContexts.playerUuid]
                val gname = row[PlayerGroupContexts.groupName]

                // Check if this was the only context for the player-group assignment
                val otherContexts = PlayerGroupContexts.selectAll().where {
                    (PlayerGroupContexts.playerUuid eq puuid) and
                            (PlayerGroupContexts.groupName eq gname) and
                            (PlayerGroupContexts.id neq row[PlayerGroupContexts.id])
                }.count()

                // Also check if there's a permanent (no-context) assignment in PlayerGroups
                // We only remove the context row, not the base assignment
                PlayerGroupContexts.deleteWhere { PlayerGroupContexts.id eq row[PlayerGroupContexts.id] }
                cleaned++
            }
        }

        if (cleaned > 0) {
            logger.debug("Cleaned up {} expired permission entries", cleaned)
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

    // ── Display (Prefix/Suffix) ──────────────────────────────────

    fun getPlayerDisplay(uuid: String): PlayerDisplay {
        val entry = players[uuid]
        val playerGroups = entry?.groups?.mapNotNull { getGroup(it) } ?: emptyList()
        val defaultGroup = getDefaultGroup()

        val allGroups = if (playerGroups.isEmpty() && defaultGroup != null) {
            listOf(defaultGroup)
        } else {
            playerGroups
        }

        val bestGroup = allGroups.maxByOrNull { it.priority } ?: defaultGroup

        return PlayerDisplay(
            prefix = bestGroup?.prefix ?: "",
            suffix = bestGroup?.suffix ?: "",
            groupName = bestGroup?.name ?: "",
            priority = bestGroup?.priority ?: 0
        )
    }

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

    // ── Internal ────────────────────────────────────────────────

    private fun collectPermissions(
        group: PermissionGroup,
        granted: MutableSet<String>,
        negated: MutableSet<String>,
        visited: MutableSet<String>
    ) {
        if (group.name.lowercase() in visited) return
        visited.add(group.name.lowercase())

        for (parentName in group.parents) {
            val parent = getGroup(parentName) ?: continue
            collectPermissions(parent, granted, negated, visited)
        }

        for (perm in group.permissions) {
            if (perm.startsWith("-")) {
                negated.add(perm.removePrefix("-"))
            } else {
                granted.add(perm)
            }
        }
    }

    companion object {
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

                val permissions = GroupPermissions.selectAll()
                    .where { GroupPermissions.groupId eq groupId }
                    .map { it[GroupPermissions.permission] }
                    .toMutableList()

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
                    permissions = permissions,
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

                val meta = PlayerMeta.selectAll()
                    .where { PlayerMeta.playerUuid eq uuid }
                    .associate { it[PlayerMeta.key] to it[PlayerMeta.value] }
                    .toMutableMap()

                players[uuid] = PlayerEntry(name = name, groups = playerGroupNames, meta = meta)
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

            // Replace permissions
            GroupPermissions.deleteWhere { GroupPermissions.groupId eq groupId }
            for (perm in group.permissions) {
                GroupPermissions.insert {
                    it[GroupPermissions.groupId] = groupId
                    it[permission] = perm
                }
            }

            // Replace parents
            GroupParents.deleteWhere { GroupParents.groupId eq groupId }
            for (parent in group.parents) {
                GroupParents.insert {
                    it[GroupParents.groupId] = groupId
                    it[parentName] = parent
                }
            }

            // Replace meta
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
