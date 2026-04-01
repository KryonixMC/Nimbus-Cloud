package dev.kryonix.nimbus.template

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplateManagerTest {

    private lateinit var manager: TemplateManager
    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        manager = TemplateManager()
        tempDir = createTempDirectory("nimbus-template-test")
    }

    @Test
    fun `prepareService copies all files from template dir to target dir`() {
        val templatesDir = tempDir.resolve("templates")
        val templateDir = templatesDir.resolve("MyTemplate").createDirectories()
        templateDir.resolve("server.jar").writeText("fake-jar")
        templateDir.resolve("config.yml").writeText("key: value")

        val targetDir = tempDir.resolve("target")

        manager.prepareService("MyTemplate", targetDir, templatesDir)

        assertTrue(targetDir.resolve("server.jar").exists())
        assertTrue(targetDir.resolve("config.yml").exists())
        assertEquals("fake-jar", targetDir.resolve("server.jar").readText())
        assertEquals("key: value", targetDir.resolve("config.yml").readText())
    }

    @Test
    fun `prepareService creates symlinks for libraries directory`() {
        val templatesDir = tempDir.resolve("templates")
        val templateDir = templatesDir.resolve("MyTemplate").createDirectories()
        val libDir = templateDir.resolve("libraries").createDirectories()
        libDir.resolve("lib.jar").writeText("library-content")
        templateDir.resolve("server.jar").writeText("jar")

        val targetDir = tempDir.resolve("target")

        manager.prepareService("MyTemplate", targetDir, templatesDir)

        val targetLib = targetDir.resolve("libraries")
        assertTrue(targetLib.exists(), "libraries dir should exist in target")
        assertTrue(targetLib.isSymbolicLink(), "libraries should be a symlink")
        assertEquals("library-content", targetLib.resolve("lib.jar").readText())
    }

    @Test
    fun `prepareService with preserveExisting keeps existing files in target`() {
        val templatesDir = tempDir.resolve("templates")
        val templateDir = templatesDir.resolve("MyTemplate").createDirectories()
        templateDir.resolve("config.yml").writeText("template-value")
        templateDir.resolve("newfile.txt").writeText("new-content")

        val targetDir = tempDir.resolve("target").createDirectories()
        targetDir.resolve("config.yml").writeText("existing-value")

        manager.prepareService("MyTemplate", targetDir, templatesDir, preserveExisting = true)

        assertEquals("existing-value", targetDir.resolve("config.yml").readText(),
            "Existing file should not be overwritten with preserveExisting=true")
        assertEquals("new-content", targetDir.resolve("newfile.txt").readText(),
            "New files from template should still be copied")
    }

    @Test
    fun `prepareService with preserveExisting false overwrites existing files`() {
        val templatesDir = tempDir.resolve("templates")
        val templateDir = templatesDir.resolve("MyTemplate").createDirectories()
        templateDir.resolve("config.yml").writeText("template-value")

        val targetDir = tempDir.resolve("target").createDirectories()
        targetDir.resolve("config.yml").writeText("existing-value")

        manager.prepareService("MyTemplate", targetDir, templatesDir, preserveExisting = false)

        assertEquals("template-value", targetDir.resolve("config.yml").readText(),
            "Existing file should be overwritten with preserveExisting=false")
    }

    @Test
    fun `applyGlobalTemplate copies global template files over target`() {
        val globalDir = tempDir.resolve("global").createDirectories()
        globalDir.resolve("plugins").createDirectories()
        globalDir.resolve("plugins/shared-plugin.jar").writeText("plugin-data")
        globalDir.resolve("global-config.yml").writeText("global: true")

        val targetDir = tempDir.resolve("target").createDirectories()
        targetDir.resolve("existing.txt").writeText("keep-me")

        manager.applyGlobalTemplate(globalDir, targetDir)

        assertTrue(targetDir.resolve("plugins/shared-plugin.jar").exists())
        assertEquals("plugin-data", targetDir.resolve("plugins/shared-plugin.jar").readText())
        assertEquals("global: true", targetDir.resolve("global-config.yml").readText())
        assertEquals("keep-me", targetDir.resolve("existing.txt").readText(),
            "Existing files not in global template should be preserved")
    }

    @Test
    fun `applyGlobalTemplate overwrites existing files in target`() {
        val globalDir = tempDir.resolve("global").createDirectories()
        globalDir.resolve("config.yml").writeText("from-global")

        val targetDir = tempDir.resolve("target").createDirectories()
        targetDir.resolve("config.yml").writeText("original")

        manager.applyGlobalTemplate(globalDir, targetDir)

        assertEquals("from-global", targetDir.resolve("config.yml").readText())
    }

    @Test
    fun `prepareService preserves nested directory structure`() {
        val templatesDir = tempDir.resolve("templates")
        val templateDir = templatesDir.resolve("MyTemplate").createDirectories()
        templateDir.resolve("plugins/myplugin/data").createDirectories()
        templateDir.resolve("plugins/myplugin/data/config.yml").writeText("nested")
        templateDir.resolve("world/region").createDirectories()
        templateDir.resolve("world/region/r.0.0.mca").writeText("region-data")

        val targetDir = tempDir.resolve("target")

        manager.prepareService("MyTemplate", targetDir, templatesDir)

        assertTrue(targetDir.resolve("plugins/myplugin/data/config.yml").exists())
        assertEquals("nested", targetDir.resolve("plugins/myplugin/data/config.yml").readText())
        assertTrue(targetDir.resolve("world/region/r.0.0.mca").exists())
        assertEquals("region-data", targetDir.resolve("world/region/r.0.0.mca").readText())
    }

    @Test
    fun `prepareService with empty template directory creates empty target`() {
        val templatesDir = tempDir.resolve("templates")
        templatesDir.resolve("EmptyTemplate").createDirectories()

        val targetDir = tempDir.resolve("target")

        manager.prepareService("EmptyTemplate", targetDir, templatesDir)

        assertTrue(targetDir.exists(), "Target directory should be created")
        val children = Files.list(targetDir).use { it.toList() }
        assertTrue(children.isEmpty(), "Target should be empty for an empty template")
    }

    @Test
    fun `applyGlobalTemplate with nonexistent global dir does nothing`() {
        val globalDir = tempDir.resolve("nonexistent")
        val targetDir = tempDir.resolve("target").createDirectories()
        targetDir.resolve("existing.txt").writeText("safe")

        manager.applyGlobalTemplate(globalDir, targetDir)

        assertEquals("safe", targetDir.resolve("existing.txt").readText())
    }
}
