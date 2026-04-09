package dev.nimbuspowered.nimbus.template

import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Scans a template's mods/ directory to extract mod IDs from JAR files.
 * Supports NeoForge (neoforge.mods.toml), Forge (mods.toml), Fabric (fabric.mod.json),
 * and legacy Forge (mcmod.info).
 */
object ModScanner {

    private val logger = LoggerFactory.getLogger(ModScanner::class.java)

    /** Generic/library mod IDs that every modloader includes — not useful for matching. */
    private val IGNORED_MODS = setOf(
        "minecraft", "forge", "neoforge", "fabricloader", "fabric", "fabric-api",
        "java", "fml", "mcp", "mixinbootstrap", "mixinextras"
    )

    private val MOD_ID_PATTERN = Regex("""modId\s*=\s*"([^"]+)"""")

    /**
     * Scans all .jar files in [templateDir]/mods/ and returns the set of discovered mod IDs (lowercase).
     */
    fun scanMods(templateDir: Path): Set<String> {
        val modsDir = templateDir.resolve("mods")
        if (!modsDir.exists() || !modsDir.isDirectory()) return emptySet()

        val modIds = mutableSetOf<String>()
        val jars = modsDir.listDirectoryEntries("*.jar")

        for (jar in jars) {
            try {
                val ids = scanJar(jar)
                modIds.addAll(ids)
            } catch (e: Exception) {
                logger.debug("Failed to scan mod JAR {}: {}", jar.fileName, e.message)
            }
        }

        modIds.removeAll(IGNORED_MODS)
        return modIds
    }

    private fun scanJar(jarPath: Path): Set<String> {
        ZipFile(jarPath.toFile()).use { zip ->
            // Try NeoForge / Forge mods.toml
            val neoforgeToml = zip.getEntry("META-INF/neoforge.mods.toml")
            if (neoforgeToml != null) {
                return extractModIdsFromToml(zip.getInputStream(neoforgeToml).bufferedReader().readText())
            }

            val forgeToml = zip.getEntry("META-INF/mods.toml")
            if (forgeToml != null) {
                return extractModIdsFromToml(zip.getInputStream(forgeToml).bufferedReader().readText())
            }

            // Try Fabric
            val fabricJson = zip.getEntry("fabric.mod.json")
            if (fabricJson != null) {
                return extractModIdFromFabricJson(zip.getInputStream(fabricJson).bufferedReader().readText())
            }

            // Try legacy Forge mcmod.info
            val mcmodInfo = zip.getEntry("mcmod.info")
            if (mcmodInfo != null) {
                return extractModIdsFromMcmodInfo(zip.getInputStream(mcmodInfo).bufferedReader().readText())
            }

            return emptySet()
        }
    }

    /** Extracts modId values from Forge/NeoForge mods.toml content. */
    private fun extractModIdsFromToml(content: String): Set<String> {
        return MOD_ID_PATTERN.findAll(content)
            .map { it.groupValues[1].lowercase() }
            .toSet()
    }

    /** Extracts the "id" field from a Fabric fabric.mod.json. */
    private fun extractModIdFromFabricJson(content: String): Set<String> {
        // Simple regex extraction — avoids adding a JSON dependency
        val match = Regex(""""id"\s*:\s*"([^"]+)"""").find(content) ?: return emptySet()
        return setOf(match.groupValues[1].lowercase())
    }

    /** Extracts "modid" fields from legacy Forge mcmod.info JSON array. */
    private fun extractModIdsFromMcmodInfo(content: String): Set<String> {
        return Regex(""""modid"\s*:\s*"([^"]+)"""").findAll(content)
            .map { it.groupValues[1].lowercase() }
            .toSet()
    }
}
