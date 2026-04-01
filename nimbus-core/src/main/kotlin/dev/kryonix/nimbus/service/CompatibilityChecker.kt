package dev.kryonix.nimbus.service

import dev.kryonix.nimbus.config.NimbusConfig
import dev.kryonix.nimbus.config.ServerSoftware
import dev.kryonix.nimbus.group.GroupManager

class CompatibilityChecker(
    private val groupManager: GroupManager,
    private val config: NimbusConfig,
    private val javaResolver: JavaResolver
) {

    /**
     * Checks if an MC version is pre-1.13 (needs legacy forwarding).
     * Supports both old (1.x.x) and new (26.x) versioning schemes.
     */
    fun isLegacyVersion(mcVersion: String): Boolean {
        val parts = mcVersion.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        if (major >= 2) return false // New scheme (26.x+) is always modern
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return minor < 13
    }

    /**
     * Returns the nogui flag for a given MC version.
     * 1.14+ uses "--nogui", 1.7-1.13 uses "nogui", pre-1.7 uses nothing.
     * New scheme (26.x+) always uses "--nogui".
     */
    fun noguiFlag(mcVersion: String): String? {
        val parts = mcVersion.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return "--nogui"
        if (major >= 2) return "--nogui"
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: return "--nogui"
        return when {
            minor >= 14 -> "--nogui"
            minor >= 7 -> "nogui"
            else -> null
        }
    }

    /**
     * Determines forwarding mode based on all configured groups.
     * If ANY backend group uses a version < 1.13, legacy (BungeeCord) forwarding is required.
     * Otherwise, modern (Velocity) forwarding is used for better security.
     */
    fun determineForwardingMode(): String {
        val hasLegacyServer = groupManager.getAllGroups().any { group ->
            group.config.group.software != ServerSoftware.VELOCITY &&
                isLegacyVersion(group.config.group.version)
        }
        return if (hasLegacyServer) "legacy" else "modern"
    }

    /**
     * Checks for compatibility issues between configured groups.
     * Returns a list of warning messages to display in the console.
     */
    fun checkCompatibility(): List<CompatWarning> {
        val warnings = mutableListOf<CompatWarning>()
        val allGroups = groupManager.getAllGroups()
        val forwardingMode = determineForwardingMode()

        val legacyGroups = allGroups.filter { group ->
            group.config.group.software != ServerSoftware.VELOCITY &&
                isLegacyVersion(group.config.group.version)
        }

        val modernOnlyGroups = allGroups.filter { group ->
            group.config.group.software in listOf(ServerSoftware.FABRIC, ServerSoftware.NEOFORGE, ServerSoftware.FOLIA)
        }

        // Legacy + Fabric/NeoForge conflict
        if (legacyGroups.isNotEmpty() && modernOnlyGroups.isNotEmpty()) {
            val legacy = legacyGroups.joinToString(", ") { "${it.name} (${it.config.group.software} ${it.config.group.version})" }
            val modern = modernOnlyGroups.joinToString(", ") { "${it.name} (${it.config.group.software} ${it.config.group.version})" }
            warnings.add(CompatWarning(
                CompatWarning.Level.ERROR,
                "Forwarding mode conflict!",
                "Pre-1.13 servers force legacy forwarding: $legacy\n" +
                "These require modern forwarding and WILL NOT WORK: $modern\n" +
                "Fix: Upgrade pre-1.13 servers to 1.13+ or remove them."
            ))
        }

        // Mixed version info
        val mcVersions = allGroups
            .filter { it.config.group.software != ServerSoftware.VELOCITY }
            .map { it.config.group.version }
            .distinct()
        if (mcVersions.size > 1) {
            val versions = mcVersions.joinToString(", ")
            warnings.add(CompatWarning(
                CompatWarning.Level.INFO,
                "Multiple MC versions: $versions (forwarding: $forwardingMode)",
                if (forwardingMode == "legacy") "Via plugins (ViaVersion/ViaBackwards) recommended for cross-version support." else ""
            ))
        }

        // Forge/NeoForge without proxy mod
        for (g in allGroups.filter { it.config.group.software in listOf(ServerSoftware.FORGE, ServerSoftware.NEOFORGE) }) {
            val templateDir = java.nio.file.Path.of(config.paths.templates).resolve(g.config.group.template)
            val modsDir = templateDir.resolve("mods")
            val hasProxyMod = modsDir.toFile().listFiles()?.any {
                val name = it.name.lowercase()
                name.contains("proxy-compatible") || name.contains("bungeeforge") || name.contains("neovelocity")
            } ?: false
            if (!hasProxyMod) {
                warnings.add(CompatWarning(
                    CompatWarning.Level.WARN,
                    "Group '${g.name}' (${g.config.group.software}) has no proxy forwarding mod",
                    "Players cannot connect via Velocity. Nimbus will try to auto-install on next start."
                ))
            }
        }

        // Fabric without FabricProxy-Lite
        for (g in allGroups.filter { it.config.group.software == ServerSoftware.FABRIC }) {
            val templateDir = java.nio.file.Path.of(config.paths.templates).resolve(g.config.group.template)
            val modsDir = templateDir.resolve("mods")
            val hasProxyMod = modsDir.toFile().listFiles()?.any {
                val name = it.name.lowercase()
                name.contains("fabricproxy") || name.contains("proxy-lite")
            } ?: false
            if (!hasProxyMod) {
                warnings.add(CompatWarning(
                    CompatWarning.Level.WARN,
                    "Group '${g.name}' (FABRIC) has no FabricProxy-Lite mod",
                    "Players cannot connect via Velocity. Nimbus will try to auto-install on next start."
                ))
            }
        }

        // Java version checks
        val detected = javaResolver.getDetectedVersions()
        val backendGroups = allGroups.filter { it.config.group.software != ServerSoftware.VELOCITY }

        val missingJavas = backendGroups.mapNotNull { g ->
            val min = javaResolver.requiredJavaVersion(g.config.group.version, g.config.group.software)
            val max = javaResolver.maxJavaVersion(g.config.group.version, g.config.group.software)
            val hasCompatible = detected.keys.any { it >= min && (max == null || it <= max) }
            if (!hasCompatible) {
                val range = if (max != null) "Java $min-$max" else "Java $min+"
                "${g.name} ($range needed)"
            } else null
        }

        if (missingJavas.isNotEmpty()) {
            warnings.add(CompatWarning(
                CompatWarning.Level.WARN,
                "Missing Java versions — will auto-download on first start",
                "No compatible Java found locally for: ${missingJavas.joinToString(", ")}\n" +
                "Detected: ${if (detected.isEmpty()) "none" else detected.keys.sorted().joinToString(", ") { "Java $it" }}\n" +
                "Nimbus will download the correct JDK automatically from Adoptium."
            ))
        }

        if (detected.isNotEmpty()) {
            val javaInfo = detected.entries.sortedBy { it.key }.joinToString(", ") { "Java ${it.key}" }
            warnings.add(CompatWarning(
                CompatWarning.Level.INFO,
                "Java: $javaInfo",
                ""
            ))
        }

        return warnings
    }

    data class CompatWarning(val level: Level, val title: String, val detail: String) {
        enum class Level { INFO, WARN, ERROR }
    }
}
