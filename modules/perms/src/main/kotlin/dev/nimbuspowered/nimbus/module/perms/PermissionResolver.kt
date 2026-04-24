package dev.nimbuspowered.nimbus.module.perms

import java.time.Instant

internal class PermissionResolver(
    private val groups: Map<String, PermissionGroup>,
    private val players: Map<String, PlayerEntry>
) {

    fun getEffectivePermissions(uuid: String, server: String? = null, world: String? = null): Set<String> {
        val result = mutableSetOf<String>()
        val negated = mutableSetOf<String>()
        val now = Instant.now()

        getDefaultGroup()?.let { collectPermissionsWithContext(it, result, negated, mutableSetOf(), server, world, now) }

        val entry = players[uuid]
        if (entry != null) {
            for (groupName in entry.groups) {
                val group = getGroup(groupName) ?: continue
                val contexts = entry.groupContexts[groupName.lowercase()]
                if (contexts != null && contexts.isNotEmpty()) {
                    if (contexts.none { ctx -> contextMatches(ctx, server, world, now) }) continue
                }
                collectPermissionsWithContext(group, result, negated, mutableSetOf(), server, world, now)
            }
        }

        result.removeAll(negated)
        return result
    }

    fun hasPermission(uuid: String, permission: String, server: String? = null, world: String? = null): Boolean {
        val effective = getEffectivePermissions(uuid, server, world)
        return PermissionManager.matchesPermission(effective, permission)
    }

    fun checkPermission(uuid: String, permission: String, server: String? = null, world: String? = null): PermissionDebugResult {
        val chain = mutableListOf<DebugStep>()
        val entry = players[uuid]
        val defaultGroup = getDefaultGroup()
        val now = Instant.now()

        if (defaultGroup != null) {
            collectDebugStepsWithContext(defaultGroup, permission, chain, mutableSetOf(), "default", server, world, now)
        }

        if (entry != null) {
            for (groupName in entry.groups) {
                val group = getGroup(groupName) ?: continue
                val contexts = entry.groupContexts[groupName.lowercase()]
                if (contexts != null && contexts.isNotEmpty()) {
                    if (contexts.none { ctx -> contextMatches(ctx, server, world, now) }) {
                        chain.add(DebugStep(
                            source = groupName,
                            permission = "(group inactive — context does not match)",
                            type = "context-filtered",
                            granted = false
                        ))
                        continue
                    }
                }
                collectDebugStepsWithContext(group, permission, chain, mutableSetOf(), "assigned", server, world, now)
            }
        }

        val effective = getEffectivePermissions(uuid, server, world)
        val result = PermissionManager.matchesPermission(effective, permission)

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

    fun getPlayerDisplay(uuid: String): PermissionManager.PlayerDisplay {
        val entry = players[uuid]
        val playerGroups = entry?.groups?.mapNotNull { getGroup(it) } ?: emptyList()
        val defaultGroup = getDefaultGroup()

        val allGroups = if (playerGroups.isEmpty() && defaultGroup != null) {
            listOf(defaultGroup)
        } else {
            playerGroups
        }

        val bestGroup = allGroups.maxByOrNull { it.priority } ?: defaultGroup

        return PermissionManager.PlayerDisplay(
            prefix = bestGroup?.prefix ?: "",
            suffix = bestGroup?.suffix ?: "",
            groupName = bestGroup?.name ?: "",
            priority = bestGroup?.priority ?: 0
        )
    }

    private fun getGroup(name: String): PermissionGroup? =
        groups.values.find { it.name.equals(name, ignoreCase = true) }

    private fun getDefaultGroup(): PermissionGroup? =
        groups.values.find { it.default }

    private fun collectDebugStepsWithContext(
        group: PermissionGroup,
        permission: String,
        chain: MutableList<DebugStep>,
        visited: MutableSet<String>,
        assignmentType: String,
        server: String?,
        world: String?,
        now: Instant
    ) {
        if (group.name.lowercase() in visited) return
        visited.add(group.name.lowercase())

        for (parentName in group.parents) {
            val parent = getGroup(parentName) ?: continue
            collectDebugStepsWithContext(parent, permission, chain, visited, "inherited", server, world, now)
        }

        for (perm in group.permissions) {
            addDebugMatch(perm, permission, group.name, assignmentType, chain)
        }

        for ((perm, contexts) in group.contextualPermissions) {
            if (contexts.any { contextMatches(it, server, world, now) }) {
                addDebugMatch(perm, permission, group.name, assignmentType, chain)
            }
        }
    }

    private fun addDebugMatch(perm: String, permission: String, groupName: String, assignmentType: String, chain: MutableList<DebugStep>) {
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
                source = groupName,
                permission = perm,
                type = type,
                granted = !isNegated
            ))
        }
    }

    private fun collectPermissionsWithContext(
        group: PermissionGroup,
        granted: MutableSet<String>,
        negated: MutableSet<String>,
        visited: MutableSet<String>,
        server: String?,
        world: String?,
        now: Instant
    ) {
        if (group.name.lowercase() in visited) return
        visited.add(group.name.lowercase())

        for (parentName in group.parents) {
            val parent = getGroup(parentName) ?: continue
            collectPermissionsWithContext(parent, granted, negated, visited, server, world, now)
        }

        for (perm in group.permissions) {
            if (perm.startsWith("-")) {
                negated.add(perm.removePrefix("-"))
            } else {
                granted.add(perm)
            }
        }

        for ((perm, contexts) in group.contextualPermissions) {
            if (contexts.any { contextMatches(it, server, world, now) }) {
                if (perm.startsWith("-")) {
                    negated.add(perm.removePrefix("-"))
                } else {
                    granted.add(perm)
                }
            }
        }
    }

    private fun contextMatches(ctx: PermissionContext, server: String?, world: String?, now: Instant): Boolean {
        if (ctx.expiresAt != null) {
            try {
                if (Instant.parse(ctx.expiresAt).isBefore(now)) return false
            } catch (_: Exception) {
                return false
            }
        }
        if (ctx.server != null && server != null && !ctx.server.equals(server, ignoreCase = true)) return false
        if (ctx.world != null && world != null && !ctx.world.equals(world, ignoreCase = true)) return false
        return true
    }
}
