package dev.nimbuspowered.nimbus.agent

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests the pure parts of StateSyncClient that don't need a running server —
 * the glob matcher + sha256 helper. Avoids the HTTP / multipart plumbing
 * because the client's ktor CIO engine isn't injectable here.
 *
 * Uses reflection to reach into the private `compileGlob` since the
 * behavior (leading slash handling, dir-suffix handling, basename match)
 * is subtle enough that regressions would be silently destructive
 * (pushing server.jar instead of skipping it, etc.).
 */
class StateSyncClientTest {

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
    private fun glob(pattern: String): (String) -> Boolean {
        val c = newClient()
        val m = StateSyncClient::class.java.getDeclaredMethod("compileGlob", String::class.java)
        m.isAccessible = true
        return m.invoke(c, pattern) as (String) -> Boolean
    }

    @Test
    fun `blank pattern matches nothing`() {
        val g = glob("")
        assertFalse(g("anything"))
        assertFalse(g("world/region/r.0.0.mca"))
    }

    @Test
    fun `basename pattern matches in any path component`() {
        val g = glob("*.log")
        assertTrue(g("server.log"))
        assertTrue(g("logs/latest.log"))
        assertTrue(g("plugins/Whatever/foo.log"))
        assertFalse(g("world/level.dat"))
    }

    @Test
    fun `path-slash pattern matches anchored path`() {
        val g = glob("logs/latest.log")
        assertTrue(g("logs/latest.log"))
        assertFalse(g("other/logs/latest.log"))
    }

    @Test
    fun `trailing-slash pattern matches directory prefix`() {
        val g = glob("cache/")
        assertTrue(g("cache/thumb.png"))
        assertTrue(g("cache/nested/x"))
        assertFalse(g("world/cache/x"))
    }

    @Test
    fun `basename matches on filename only not whole path`() {
        val g = glob("server.jar")
        assertTrue(g("server.jar"))
        assertTrue(g("plugins/server.jar"))
        assertFalse(g("serverjar"))
    }
}
