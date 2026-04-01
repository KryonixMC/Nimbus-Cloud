package dev.kryonix.nimbus.template

import dev.kryonix.nimbus.config.ServerSoftware
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeLines
import kotlin.io.path.writeText

/**
 * Applies Aikar's JVM flags and optimized server configs for Minecraft servers.
 * Only applies to Paper/Purpur backends — proxies and modded servers are skipped.
 */
class PerformanceOptimizer {

    private val logger = LoggerFactory.getLogger(PerformanceOptimizer::class.java)

    /**
     * Returns Aikar's optimized JVM flags for Minecraft servers.
     * Flags are tuned based on allocated memory (12G+ gets adjusted G1 parameters).
     */
    fun aikarsFlags(memory: String): List<String> {
        val memoryMb = parseMemoryMb(memory)
        val largeHeap = memoryMb >= 12 * 1024

        val flags = mutableListOf(
            "-Xms$memory",
            "-XX:+UseG1GC",
            "-XX:+ParallelRefProcEnabled",
            "-XX:MaxGCPauseMillis=200",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+DisableExplicitGC",
            "-XX:+AlwaysPreTouch",
            "-XX:G1HeapWastePercent=5",
            "-XX:G1MixedGCCountTarget=4",
            "-XX:G1MixedGCLiveThresholdPercent=90",
            "-XX:G1RSetUpdatingPauseTimePercent=5",
            "-XX:SurvivorRatio=32",
            "-XX:+PerfDisableSharedMem",
            "-XX:MaxTenuringThreshold=1",
        )

        if (largeHeap) {
            flags.add("-XX:G1NewSizePercent=40")
            flags.add("-XX:G1MaxNewSizePercent=50")
            flags.add("-XX:G1HeapRegionSize=16M")
            flags.add("-XX:G1ReservePercent=15")
            flags.add("-XX:InitiatingHeapOccupancyPercent=20")
        } else {
            flags.add("-XX:G1NewSizePercent=30")
            flags.add("-XX:G1MaxNewSizePercent=40")
            flags.add("-XX:G1HeapRegionSize=8M")
            flags.add("-XX:G1ReservePercent=20")
            flags.add("-XX:InitiatingHeapOccupancyPercent=15")
        }

        return flags
    }

    /**
     * Optimizes spigot.yml and paper-world-defaults.yml for better performance.
     * Only touches settings that reduce server load without noticeably affecting gameplay.
     */
    fun optimizeServerConfigs(workDir: Path, software: ServerSoftware) {
        if (software !in listOf(ServerSoftware.PAPER, ServerSoftware.PUFFERFISH, ServerSoftware.PURPUR, ServerSoftware.FOLIA)) return

        optimizeSpigotYml(workDir)
        optimizePaperWorldDefaults(workDir)
    }

    /**
     * Optimizes spigot.yml:
     * - Increased merge radius (fewer ground item entities)
     * - Reduced entity activation ranges (less AI tick cost for distant mobs)
     */
    private fun optimizeSpigotYml(workDir: Path) {
        val file = workDir.resolve("spigot.yml")
        if (!file.exists()) {
            file.writeText(buildString {
                appendLine("world-settings:")
                appendLine("  default:")
                appendLine("    merge-radius:")
                appendLine("      item: 3.5")
                appendLine("      exp: 4.0")
                appendLine("    mob-spawn-range: 6")
                appendLine("    entity-activation-range:")
                appendLine("      animals: 16")
                appendLine("      monsters: 24")
                appendLine("      raiders: 48")
                appendLine("      misc: 8")
                appendLine("      tick-inactive-villagers: false")
            })
            logger.info("Created optimized spigot.yml")
            return
        }

        val lines = file.readLines().toMutableList()
        val patcher = YamlPatcher(lines)

        patcher.set(listOf("world-settings", "default", "merge-radius", "item"), "3.5")
        patcher.set(listOf("world-settings", "default", "merge-radius", "exp"), "4.0")
        patcher.set(listOf("world-settings", "default", "mob-spawn-range"), "6")
        patcher.set(listOf("world-settings", "default", "entity-activation-range", "animals"), "16")
        patcher.set(listOf("world-settings", "default", "entity-activation-range", "monsters"), "24")
        patcher.set(listOf("world-settings", "default", "entity-activation-range", "misc"), "8")
        patcher.set(listOf("world-settings", "default", "entity-activation-range", "tick-inactive-villagers"), "false")

        file.writeLines(patcher.lines)
        logger.info("Optimized spigot.yml (merge radius, entity activation ranges)")
    }

    /**
     * Optimizes paper-world-defaults.yml (Paper 1.19+):
     * - Reduced auto-save chunk throughput (less I/O spikes)
     * - Optimized explosion algorithm
     * - Extended despawn ranges (monsters despawn sooner when far away)
     */
    private fun optimizePaperWorldDefaults(workDir: Path) {
        val configDir = workDir.resolve("config")
        val file = configDir.resolve("paper-world-defaults.yml")

        if (!file.exists()) {
            if (!configDir.exists()) configDir.createDirectories()
            file.writeText(buildString {
                appendLine("chunks:")
                appendLine("  max-auto-save-chunks-per-tick: 8")
                appendLine("environment:")
                appendLine("  optimize-explosions: true")
                appendLine("  treasure-maps:")
                appendLine("    find-already-discovered:")
                appendLine("      loot-tables: true")
                appendLine("      villager-trade: true")
                appendLine("entities:")
                appendLine("  spawning:")
                appendLine("    despawn-ranges:")
                appendLine("      monster:")
                appendLine("        soft: 30")
                appendLine("        hard: 56")
            })
            logger.info("Created optimized paper-world-defaults.yml")
            return
        }

        val lines = file.readLines().toMutableList()
        val patcher = YamlPatcher(lines)

        patcher.setInSection("chunks", "max-auto-save-chunks-per-tick", value = "8")
        patcher.setInSection("environment", "optimize-explosions", value = "true")
        patcher.set(listOf("environment", "treasure-maps", "find-already-discovered", "loot-tables"), "true")
        patcher.set(listOf("environment", "treasure-maps", "find-already-discovered", "villager-trade"), "true")
        patcher.set(listOf("entities", "spawning", "despawn-ranges", "monster", "soft"), "30")
        patcher.set(listOf("entities", "spawning", "despawn-ranges", "monster", "hard"), "56")

        file.writeLines(patcher.lines)
        logger.info("Optimized paper-world-defaults.yml (chunk saves, explosions, despawn ranges)")
    }

    /**
     * Simple YAML patcher that navigates nested sections and updates leaf values.
     * Only modifies values that already exist — does not add missing keys to existing files
     * to avoid conflicts with server-generated defaults.
     */
    private class YamlPatcher(val lines: MutableList<String>) {

        fun set(path: List<String>, value: String) {
            setInSection(*path.toTypedArray(), value = value)
        }

        fun setInSection(vararg path: String, value: String) {
            if (path.isEmpty()) return
            val key = path.last()
            val sections = path.dropLast(1)

            // Find the target key within the correct section nesting
            var searchFrom = 0
            var expectedIndent = 0

            for (section in sections) {
                val idx = findKey(section, searchFrom, expectedIndent)
                if (idx < 0) return // Section not found, skip
                searchFrom = idx + 1
                expectedIndent = indentOf(lines[idx]) + 2
            }

            val idx = findKey(key, searchFrom, expectedIndent)
            if (idx < 0) return // Key not found, skip

            val line = lines[idx]
            val colonIdx = line.indexOf("$key:")
            if (colonIdx >= 0) {
                lines[idx] = line.substring(0, colonIdx) + "$key: $value"
            }
        }

        private fun findKey(key: String, fromLine: Int, minIndent: Int): Int {
            for (i in fromLine until lines.size) {
                val line = lines[i]
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                val indent = indentOf(line)
                // If we've gone back to a lower indent level, the section ended
                if (indent < minIndent && i > fromLine) return -1

                if (indent == minIndent && (trimmed == "$key:" || trimmed.startsWith("$key:"))) {
                    return i
                }
            }
            return -1
        }

        private fun indentOf(line: String): Int = line.length - line.trimStart().length
    }

    private fun parseMemoryMb(memory: String): Int {
        val value = memory.filter { it.isDigit() }.toIntOrNull() ?: return 1024
        return when {
            memory.endsWith("G", ignoreCase = true) -> value * 1024
            memory.endsWith("M", ignoreCase = true) -> value
            else -> value
        }
    }
}
