package dev.nimbuspowered.nimbus.module.resourcepacks

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists

class ResourcePackManagerTest {

    private fun mgr(dir: Path): ResourcePackManager {
        val db = buildTestDb(dir, ResourcePacks, ResourcePackAssignments)
        val storage = dir.resolve("storage")
        return ResourcePackManager(db, storage)
    }

    @Test
    fun `createUrlPack persists and retrieves`(@TempDir dir: Path) = runTest {
        val m = mgr(dir)
        val rec = m.createUrlPack("PackA", "https://x/p.zip", "a".repeat(40), "msg", false, "admin")
        assertEquals("URL", rec.source)
        assertEquals("PackA", rec.name)

        val fetched = m.getPack(rec.id)!!
        assertEquals(rec.packUuid, fetched.packUuid)
        assertEquals("admin", fetched.uploadedBy)
    }

    @Test
    fun `uploadLocalPack computes SHA1 and writes file`(@TempDir dir: Path) = runTest {
        val m = mgr(dir)
        val payload = "hello-pack".toByteArray()
        val expected = MessageDigest.getInstance("SHA-1").digest(payload)
            .joinToString("") { "%02x".format(it) }

        val rec = m.uploadLocalPack(
            name = "Local",
            input = ByteArrayInputStream(payload),
            maxBytes = 10_000,
            promptMessage = "",
            force = true,
            uploadedBy = "tester"
        )
        assertEquals("LOCAL", rec.source)
        assertEquals(expected, rec.sha1Hash)
        assertEquals(payload.size.toLong(), rec.fileSize)
        assertTrue(rec.url.startsWith("/api/resourcepacks/files/"))
        assertTrue(rec.url.endsWith(".zip"))
        assertNotNull(m.localPackFile(rec.packUuid))
    }

    @Test
    fun `uploadLocalPack rejects oversize payloads`(@TempDir dir: Path) = runTest {
        val m = mgr(dir)
        val payload = ByteArray(200_000) { 1 }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                m.uploadLocalPack(
                    name = "Big",
                    input = ByteArrayInputStream(payload),
                    maxBytes = 1_000,
                    promptMessage = "",
                    force = false,
                    uploadedBy = "x"
                )
            }
        }
    }

    @Test
    fun `deletePack removes record and local file`(@TempDir dir: Path) = runTest {
        val m = mgr(dir)
        val rec = m.uploadLocalPack("P", ByteArrayInputStream("x".toByteArray()), 100, "", false, "a")
        val filePath = m.localPackFile(rec.packUuid)
        assertNotNull(filePath)

        assertTrue(m.deletePack(rec.id))
        assertNull(m.getPack(rec.id))
        assertFalse(Files.exists(filePath!!))
    }

    @Test
    fun `resolvePacks orders GLOBAL before GROUP before SERVICE`(@TempDir dir: Path) = runTest {
        val m = mgr(dir)
        val g = m.createUrlPack("G", "https://g", "g".repeat(40), "", false, "a")
        val gr = m.createUrlPack("GR", "https://gr", "r".repeat(40), "", false, "a")
        val s = m.createUrlPack("S", "https://s", "s".repeat(40), "", false, "a")

        m.createAssignment(g.id, "GLOBAL", "", 10)
        m.createAssignment(gr.id, "GROUP", "Lobby", 20)
        m.createAssignment(s.id, "SERVICE", "Lobby-1", 30)

        val resolved = m.resolvePacks("Lobby", "Lobby-1", "https://base")
        assertEquals(listOf("G", "GR", "S"), resolved.map { it.name })
    }

    @Test
    fun `resolvePacks within same scope ordered by priority ascending`(@TempDir dir: Path) = runTest {
        val m = mgr(dir)
        val a = m.createUrlPack("A", "https://a", "a".repeat(40), "", false, "u")
        val b = m.createUrlPack("B", "https://b", "b".repeat(40), "", false, "u")
        val c = m.createUrlPack("C", "https://c", "c".repeat(40), "", false, "u")

        m.createAssignment(a.id, "GROUP", "Lobby", 50)
        m.createAssignment(b.id, "GROUP", "Lobby", 5)
        m.createAssignment(c.id, "GROUP", "Lobby", 20)

        val resolved = m.resolvePacks("Lobby", "Lobby-1", "https://base")
        assertEquals(listOf("B", "C", "A"), resolved.map { it.name })
    }

    @Test
    fun `resolvePacks filters non-matching group and service`(@TempDir dir: Path) = runTest {
        val m = mgr(dir)
        val a = m.createUrlPack("A", "https://a", "a".repeat(40), "", false, "u")
        val b = m.createUrlPack("B", "https://b", "b".repeat(40), "", false, "u")
        m.createAssignment(a.id, "GROUP", "Other", 0)
        m.createAssignment(b.id, "SERVICE", "Foo-1", 0)

        val resolved = m.resolvePacks("Lobby", "Lobby-1", "https://base")
        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `resolvePacks rewrites LOCAL url with publicBaseUrl`(@TempDir dir: Path) = runTest {
        val m = mgr(dir)
        val rec = m.uploadLocalPack("L", ByteArrayInputStream("z".toByteArray()), 100, "", false, "u")
        m.createAssignment(rec.id, "GLOBAL", "", 0)

        val resolved = m.resolvePacks("G", "S", "https://example.com/")
        assertEquals(1, resolved.size)
        assertEquals("https://example.com${rec.url}", resolved[0].url)
    }

    @Test
    fun `createAssignment returns null for non-existent pack`(@TempDir dir: Path) = runTest {
        val m = mgr(dir)
        assertNull(m.createAssignment(999, "GLOBAL", "", 0))
    }

    @Test
    fun `deleteAssignment removes entry`(@TempDir dir: Path) = runTest {
        val m = mgr(dir)
        val p = m.createUrlPack("P", "https://p", "p".repeat(40), "", false, "u")
        val a = m.createAssignment(p.id, "GLOBAL", "", 0)!!
        assertTrue(m.deleteAssignment(a.id))
        assertFalse(m.deleteAssignment(a.id))
    }
}
