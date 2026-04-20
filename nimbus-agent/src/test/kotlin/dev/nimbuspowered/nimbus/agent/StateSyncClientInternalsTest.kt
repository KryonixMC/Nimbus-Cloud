package dev.nimbuspowered.nimbus.agent

import dev.nimbuspowered.nimbus.protocol.StateManifest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Exercises StateSyncClient's private manifest + hash helpers via reflection.
 * These drive the scanLocalManifest + sha256 + compileGlob paths without
 * involving any HTTP I/O.
 */
class StateSyncClientInternalsTest {

    private val clients = mutableListOf<StateSyncClient>()

    @AfterEach
    fun cleanup() {
        clients.forEach { it.close() }
        clients.clear()
    }

    private fun newClient(): StateSyncClient {
        val c = StateSyncClient("http://127.0.0.1:1", "tok")
        clients += c
        return c
    }

    @Suppress("UNCHECKED_CAST")
    private fun scan(root: Path, excludes: List<String>): StateManifest {
        val c = newClient()
        val compile = StateSyncClient::class.java.getDeclaredMethod("compileGlob", String::class.java)
        compile.isAccessible = true
        val matchers = excludes.map { compile.invoke(c, it) as (String) -> Boolean }

        val scan = StateSyncClient::class.java.getDeclaredMethod(
            "scanLocalManifest", Path::class.java, List::class.java
        )
        scan.isAccessible = true
        return scan.invoke(c, root, matchers) as StateManifest
    }

    @Test
    fun `scanLocalManifest returns empty for missing root`(@TempDir dir: Path) {
        val manifest = scan(dir.resolve("does-not-exist"), emptyList())
        assertTrue(manifest.files.isEmpty())
    }

    @Test
    fun `scanLocalManifest hashes each file and returns relative paths`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("a.txt"), "hello")
        val sub = dir.resolve("sub")
        Files.createDirectories(sub)
        Files.writeString(sub.resolve("b.txt"), "world")

        val manifest = scan(dir, emptyList())
        assertEquals(setOf("a.txt", "sub/b.txt"), manifest.files.keys)
        // sha256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            manifest.files["a.txt"]!!.sha256
        )
        assertEquals(5L, manifest.files["a.txt"]!!.size)
    }

    @Test
    fun `scanLocalManifest respects excludes`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("keep.txt"), "x")
        Files.writeString(dir.resolve("drop.log"), "y")
        val logs = dir.resolve("logs")
        Files.createDirectories(logs)
        Files.writeString(logs.resolve("latest.log"), "z")

        val manifest = scan(dir, listOf("*.log"))
        assertEquals(setOf("keep.txt"), manifest.files.keys)
    }

    @Test
    fun `scanLocalManifest respects directory excludes`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("root.txt"), "x")
        val cache = dir.resolve("cache")
        Files.createDirectories(cache)
        Files.writeString(cache.resolve("thumb.png"), "y")
        Files.writeString(cache.resolve("nested.txt"), "z")

        val manifest = scan(dir, listOf("cache/"))
        assertEquals(setOf("root.txt"), manifest.files.keys)
    }
}
