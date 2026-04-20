package dev.nimbuspowered.nimbus.template

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModScannerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun writeJar(dir: Path, jarName: String, entries: Map<String, String>): Path {
        val jar = dir.resolve(jarName)
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        Files.write(jar, baos.toByteArray())
        return jar
    }

    @Test
    fun `returns empty set when mods dir does not exist`() {
        assertEquals(emptySet<String>(), ModScanner.scanMods(tempDir))
    }

    @Test
    fun `returns empty set when mods dir is a file`() {
        Files.writeString(tempDir.resolve("mods"), "not a dir")
        assertEquals(emptySet<String>(), ModScanner.scanMods(tempDir))
    }

    @Test
    fun `extracts modId from fabric mod json`() {
        val mods = tempDir.resolve("mods").also { Files.createDirectories(it) }
        writeJar(
            mods, "example-fabric.jar",
            mapOf("fabric.mod.json" to """{"schemaVersion":1,"id":"exampleMod","version":"1.0"}""")
        )
        assertEquals(setOf("examplemod"), ModScanner.scanMods(tempDir))
    }

    @Test
    fun `extracts modId from neoforge mods toml`() {
        val mods = tempDir.resolve("mods").also { Files.createDirectories(it) }
        writeJar(
            mods, "neo.jar",
            mapOf(
                "META-INF/neoforge.mods.toml" to """
                    modLoader="javafml"
                    loaderVersion="[1,)"
                    [[mods]]
                    modId = "createneo"
                    version = "0.5"
                """.trimIndent()
            )
        )
        assertEquals(setOf("createneo"), ModScanner.scanMods(tempDir))
    }

    @Test
    fun `extracts modId from forge mods toml`() {
        val mods = tempDir.resolve("mods").also { Files.createDirectories(it) }
        writeJar(
            mods, "forge.jar",
            mapOf(
                "META-INF/mods.toml" to """
                    [[mods]]
                    modId = "jei"
                    [[mods]]
                    modId = "Waystones"
                """.trimIndent()
            )
        )
        // Note: modIds are lowercased
        assertEquals(setOf("jei", "waystones"), ModScanner.scanMods(tempDir))
    }

    @Test
    fun `extracts modIds from legacy mcmod info`() {
        val mods = tempDir.resolve("mods").also { Files.createDirectories(it) }
        writeJar(
            mods, "legacy.jar",
            mapOf(
                "mcmod.info" to """[{"modid":"oldMod","name":"Old","version":"1.0"}]"""
            )
        )
        assertEquals(setOf("oldmod"), ModScanner.scanMods(tempDir))
    }

    @Test
    fun `ignores generic library mod ids`() {
        val mods = tempDir.resolve("mods").also { Files.createDirectories(it) }
        writeJar(
            mods, "lib.jar",
            mapOf(
                "META-INF/mods.toml" to """
                    [[mods]]
                    modId = "forge"
                    [[mods]]
                    modId = "mixinextras"
                    [[mods]]
                    modId = "customlib"
                """.trimIndent()
            )
        )
        assertEquals(setOf("customlib"), ModScanner.scanMods(tempDir))
    }

    @Test
    fun `skips jar files with no recognizable descriptors`() {
        val mods = tempDir.resolve("mods").also { Files.createDirectories(it) }
        writeJar(mods, "weird.jar", mapOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n"))
        assertEquals(emptySet<String>(), ModScanner.scanMods(tempDir))
    }

    @Test
    fun `handles corrupted jar gracefully`() {
        val mods = tempDir.resolve("mods").also { Files.createDirectories(it) }
        Files.writeString(mods.resolve("broken.jar"), "not a real zip")
        // Still writes a valid one alongside
        writeJar(
            mods, "good.jar",
            mapOf("fabric.mod.json" to """{"id":"good"}""")
        )
        assertEquals(setOf("good"), ModScanner.scanMods(tempDir))
    }

    @Test
    fun `only scans jar files not other extensions`() {
        val mods = tempDir.resolve("mods").also { Files.createDirectories(it) }
        writeJar(mods, "scan.jar", mapOf("fabric.mod.json" to """{"id":"scanme"}"""))
        // .jar.disabled should be ignored
        writeJar(mods, "skip.jar.disabled", mapOf("fabric.mod.json" to """{"id":"skipme"}"""))
        assertEquals(setOf("scanme"), ModScanner.scanMods(tempDir))
    }

    @Test
    fun `merges mod ids across multiple jars`() {
        val mods = tempDir.resolve("mods").also { Files.createDirectories(it) }
        writeJar(mods, "a.jar", mapOf("fabric.mod.json" to """{"id":"alpha"}"""))
        writeJar(mods, "b.jar", mapOf("fabric.mod.json" to """{"id":"beta"}"""))
        assertEquals(setOf("alpha", "beta"), ModScanner.scanMods(tempDir))
    }
}
